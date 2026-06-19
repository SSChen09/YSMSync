package com.ysmsync;

import com.ysmsync.model.ModelFileManager;
import com.ysmsync.model.ModelUploadManager;
import com.ysmsync.net.YSMChannelHandler;
import com.ysmsync.packet.YSMPacketHandler;
import com.ysmsync.state.PlayerYSMState;
import com.ysmsync.state.YSMStateManager;
import com.ysmsync.storage.YSMStorage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * YSMSync - Paper 服务端 YSM 模型同步插件。
 *
 * 核心职责：
 * 1. 监听 yes_steve_model:2_6_0 频道的 C2S 数据包
 * 2. 处理握手、模型切换、动画同步
 * 3. 广播 S2C 数据包给所有已握手的在线玩家
 * 4. 持久化存储玩家模型选择
 */
public class YSMPlugin extends JavaPlugin implements Listener {

    private YSMStorage storage;
    private YSMStateManager stateManager;
    private YSMPacketHandler packetHandler;
    private ModelFileManager modelFileManager;
    private ModelUploadManager uploadManager;
    private String ysmChannel;
    private boolean debug;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        // 初始化存储
        storage = new YSMStorage(this);
        storage.open();

        // 初始化模型文件管理器
        modelFileManager = new ModelFileManager(this);
        modelFileManager.loadAllFromDisk();

        // 初始化上传管理器
        uploadManager = new ModelUploadManager(this, modelFileManager);

        // 初始化状态管理器
        stateManager = new YSMStateManager(this, storage, modelFileManager);

        // 初始化数据包处理器
        packetHandler = new YSMPacketHandler(this, stateManager, modelFileManager, uploadManager);

        // 注册事件监听
        getServer().getPluginManager().registerEvents(this, this);

        // 注册 YSM 频道
        getServer().getMessenger().registerIncomingPluginChannel(this, ysmChannel, (channel, player, message) -> {
            handlePluginMessage(player, message);
        });
        getServer().getMessenger().registerOutgoingPluginChannel(this, ysmChannel);

        getLogger().info("YSMSync enabled. Channel: " + ysmChannel);
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
    }

    /**
     * 处理从客户端收到的插件消息。
     */
    private void handlePluginMessage(Player player, byte[] message) {
        // 检查是否是 YSM 频道
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

    public void logDebug(String message) {
        if (debug) {
            getLogger().info("[DEBUG] " + message);
        }
    }
}
