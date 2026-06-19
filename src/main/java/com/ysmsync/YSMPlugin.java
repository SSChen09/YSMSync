package com.ysmsync;

import com.ysmsync.crypto.ServerKeyManager;
import com.ysmsync.model.ModelFileManager;
import com.ysmsync.model.ModelUploadManager;
import com.ysmsync.net.YSMChannelHandler;
import com.ysmsync.packet.YSMPacketHandler;
import com.ysmsync.state.PlayerYSMState;
import com.ysmsync.state.YSMStateManager;
import com.ysmsync.storage.YSMStorage;
import com.ysmsync.update.UpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;

/**
 * YSMSync - Paper 服务端 YSM 模型同步插件。
 *
 * 核心职责：
 * 1. 监听 yes_steve_model:2_6_0 频道的 C2S 数据包
 * 2. 处理握手、模型切换、动画同步
 * 3. 广播 S2C 数据包给所有已握手的在线玩家
 * 4. 持久化存储玩家模型选择
 */
public class YSMPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private YSMStorage storage;
    private YSMStateManager stateManager;
    private YSMPacketHandler packetHandler;
    private ModelFileManager modelFileManager;
    private ModelUploadManager uploadManager;
    private ServerKeyManager serverKeyManager;
    private UpdateChecker updateChecker;
    private String ysmChannel;
    private boolean debug;
    private boolean checkUpdate;
    private boolean autoUpdate;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        // 应用待处理的更新（从 plugins/update/ 移动到 plugins/）
        applyPendingUpdate();

        // 初始化存储
        storage = new YSMStorage(this);
        storage.open();

        // 初始化服务端密钥管理器
        serverKeyManager = new ServerKeyManager(this);
        serverKeyManager.loadOrCreate();

        // 初始化模型文件管理器
        modelFileManager = new ModelFileManager(this);
        modelFileManager.setServerKey(serverKeyManager.getServerKey());
        modelFileManager.loadAllFromDisk();

        // 初始化上传管理器
        uploadManager = new ModelUploadManager(this, modelFileManager);

        // 初始化状态管理器
        stateManager = new YSMStateManager(this, storage, modelFileManager);
        stateManager.setServerKeyManager(serverKeyManager);

        // 初始化数据包处理器
        packetHandler = new YSMPacketHandler(this, stateManager, modelFileManager, uploadManager);

        // 注册事件监听
        getServer().getPluginManager().registerEvents(this, this);

        // 注册命令
        getCommand("ysmsync").setExecutor(this);

        // 注册 YSM 频道
        getServer().getMessenger().registerIncomingPluginChannel(this, ysmChannel, (channel, player, message) -> {
            handlePluginMessage(player, message);
        });
        getServer().getMessenger().registerOutgoingPluginChannel(this, ysmChannel);

        getLogger().info("YSMSync enabled. Channel: " + ysmChannel);

        // 检查更新
        if (checkUpdate) {
            updateChecker = new UpdateChecker(this);
            updateChecker.check().thenAccept(result -> {
                if (result.error() != null) {
                    logDebug("Update check failed: " + result.error());
                    return;
                }
                if (result.hasUpdate()) {
                    getLogger().warning("New version available: " + result.latestVersion()
                            + " (current: " + getDescription().getVersion() + ")");
                    if (autoUpdate && result.downloadUrl() != null) {
                        getLogger().info("Auto-downloading update...");
                        downloadAndApply(result.downloadUrl(), result.latestVersion());
                    } else {
                        getLogger().warning("Download: https://github.com/SSChen09/YSMSync/releases/latest");
                    }
                } else {
                    logDebug("You are running the latest version.");
                }
            });
        }
    }

    @Override
    public void onDisable() {
        if (uploadManager != null) {
            uploadManager.shutdown();
        }
        if (modelFileManager != null) {
            modelFileManager.shutdown();
        }
        if (storage != null) {
            storage.close();
        }
        getLogger().info("YSMSync disabled.");
    }

    private void loadConfig() {
        ysmChannel = getConfig().getString("channel", "yes_steve_model:2_6_0");
        debug = getConfig().getBoolean("debug", false);
        checkUpdate = getConfig().getBoolean("check-update", true);
        autoUpdate = getConfig().getBoolean("auto-update", false);
    }

    /**
     * 应用待处理的更新。
     * 检查 plugins/update/ 目录下是否有已下载的新版本 JAR，
     * 如果有则替换当前运行的 JAR，下次重启生效。
     */
    private void applyPendingUpdate() {
        File updateDir = new File(getDataFolder(), "update");
        if (!updateDir.exists() || !updateDir.isDirectory()) return;

        File[] jars = updateDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) return;

        File pluginsDir = getDataFolder().getParentFile();

        for (File jar : jars) {
            try {
                File target = new File(pluginsDir, jar.getName());
                Files.move(jar.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                getLogger().info("Applied update: " + jar.getName() + " (restart to activate)");
            } catch (Exception e) {
                getLogger().log(java.util.logging.Level.WARNING,
                        "Failed to apply update: " + jar.getName(), e);
            }
        }
    }

    /**
     * 获取更新下载目录（plugins/update/）。
     */
    private File getUpdateDir() {
        File dir = new File(getDataFolder(), "update");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * 处理从客户端收到的插件消息。
     */
    private void handlePluginMessage(Player player, byte[] message) {
        packetHandler.handleIncoming(player, message);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 注入 Netty 拦截器
        YSMChannelHandler.inject(player, this);

        // 延迟发送握手请求，等待客户端和 pipeline 准备好
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;

            // 创建状态（会从 SQLite 恢复之前的模型选择）
            PlayerYSMState state = stateManager.getOrCreate(player.getUniqueId());

            // 发送 S2CVersionCheckPacket 触发客户端握手
            String version = getConfig().getString("protocol-version", "2.6.0");
            String brand = getConfig().getString("brand", "open_ysm:v1");
            boolean allowUpload = getConfig().getBoolean("allow-upload", false);

            byte[] versionPacket = stateManager.buildVersionCheckPacket(version, brand, allowUpload);
            stateManager.sendYSMPayload(player, versionPacket);

            logDebug("Sent version check to " + player.getName());
        }, 20L); // 1 秒延迟
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // 移除 Netty 拦截器
        YSMChannelHandler.remove(player, this);

        PlayerYSMState state = stateManager.get(player.getUniqueId());
        if (state != null && state.hasModel()) {
            storage.saveModel(player.getUniqueId(), state.getModelId(), state.getTextureId());
        }
        stateManager.remove(player.getUniqueId());
    }

    public YSMStateManager getStateManager() { return stateManager; }
    public YSMPacketHandler getPacketHandler() { return packetHandler; }
    public String getYsmChannel() { return ysmChannel; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("ysmsync")) return false;

        if (args.length == 0 || args[0].equalsIgnoreCase("update")) {
            if (updateChecker == null) {
                updateChecker = new UpdateChecker(this);
            }
            updateChecker.check().thenAccept(result -> {
                if (result.error() != null) {
                    if ("Already checking".equals(result.error())) {
                        sender.sendMessage("[YSMSync] Update check already in progress...");
                    } else {
                        sender.sendMessage("[YSMSync] Update check failed: " + result.error());
                    }
                    return;
                }
                String current = getDescription().getVersion();
                if (result.hasUpdate()) {
                    sender.sendMessage("[YSMSync] New version available: " + result.latestVersion()
                            + " (current: " + current + ")");
                    if (result.downloadUrl() != null) {
                        sender.sendMessage("[YSMSync] Downloading update...");
                        File target = new File(getUpdateDir(), "YSMSync-" + result.latestVersion() + ".jar");
                        boolean ok = updateChecker.download(result.downloadUrl(), target);
                        if (ok) {
                            sender.sendMessage("[YSMSync] Update downloaded. Restart the server to apply.");
                        } else {
                            sender.sendMessage("[YSMSync] Download failed. Please download manually:");
                            sender.sendMessage("[YSMSync] https://github.com/SSChen09/YSMSync/releases/latest");
                        }
                    } else {
                        sender.sendMessage("[YSMSync] No download URL found. Please download manually:");
                        sender.sendMessage("[YSMSync] https://github.com/SSChen09/YSMSync/releases/latest");
                    }
                } else {
                    sender.sendMessage("[YSMSync] You are running the latest version (" + current + ").");
                }
            });
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadConfig();
            sender.sendMessage("[YSMSync] Configuration reloaded.");
            return true;
        }

        sender.sendMessage("[YSMSync] Usage: /ysmsync [update|reload]");
        return true;
    }

    public void logDebug(String message) {
        if (debug) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    /**
     * 自动下载更新到 plugins/update/ 目录，重启后生效。
     */
    private void downloadAndApply(String downloadUrl, String newVersion) {
        File target = new File(getUpdateDir(), "YSMSync-" + newVersion + ".jar");
        boolean ok = updateChecker.download(downloadUrl, target);
        if (ok) {
            getLogger().info("Update to " + newVersion + " downloaded. Restart the server to apply.");
        } else {
            getLogger().warning("Auto-update download failed. Please download manually:");
            getLogger().warning("https://github.com/SSChen09/YSMSync/releases/latest");
        }
    }
}
