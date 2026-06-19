package com.ysmsync.model;

import com.ysmsync.YSMPlugin;
import com.ysmsync.util.VarIntUtil;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 模型文件存储管理器。
 * 目录结构：plugins/YSMSync/models/{玩家UUID}/{模型名}
 * 每个玩家一个子目录，支持存储多个模型。
 */
public class ModelFileManager {

    private final YSMPlugin plugin;
    private final Path modelsDir;

    /**
     * 玩家 UUID -> (模型文件名 -> S2C 格式数据)
     */
    private final Map<UUID, Map<String, byte[]>> playerModels = new ConcurrentHashMap<>();

    public ModelFileManager(YSMPlugin plugin) {
        this.plugin = plugin;
        this.modelsDir = plugin.getDataFolder().toPath().resolve("models");
        try {
            Files.createDirectories(modelsDir);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create models directory", e);
        }
    }

    /**
     * 存储玩家的模型数据（C2SModelSyncPayload 格式）。
     * 自动转换为 S2C 格式后存储。
     *
     * @param playerUuid 玩家 UUID
     * @param rawData    客户端发送的 C2SModelSyncPayload 原始数据
     */
    public void storeModelData(UUID playerUuid, byte[] rawData) {
        byte[] s2cData = convertC2S_to_S2C(rawData);
        if (s2cData == null) return;

        String name = "model";
        playerModels.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>()).put(name, s2cData);

        Path playerDir = modelsDir.resolve(playerUuid.toString());
        Path file = playerDir.resolve(name);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Files.createDirectories(playerDir);
                Files.write(file, s2cData);
                plugin.logDebug("Saved model data for " + playerUuid + "/" + name + " (" + s2cData.length + " bytes)");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save model data for " + playerUuid, e);
            }
        });
    }

    /**
     * 存储上传的原始 .ysm 文件数据。
     * 自动包装为 S2C 格式（packetId=1 + 原始数据）后存储。
     *
     * @param playerUuid 玩家 UUID
     * @param modelName  模型名称（用作文件名）
     * @param rawData    原始 .ysm 文件字节
     */
    public void storeRawModelData(UUID playerUuid, String modelName, byte[] rawData) {
        if (rawData == null || rawData.length == 0) return;

        String safeName = sanitizeFileName(modelName);

        // 包装为 S2C 格式：packetId=1 + 原始 .ysm 数据
        ByteBuffer out = ByteBuffer.allocate(5 + rawData.length);
        VarIntUtil.writeVarInt(out, 1); // S2CModelSyncPayload ID
        out.put(rawData);
        out.flip();
        byte[] s2cData = new byte[out.remaining()];
        out.get(s2cData);

        playerModels.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>()).put(safeName, s2cData);

        Path playerDir = modelsDir.resolve(playerUuid.toString());
        Path file = playerDir.resolve(safeName);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Files.createDirectories(playerDir);
                Files.write(file, s2cData);
                plugin.logDebug("Saved raw model data for " + playerUuid + "/" + safeName + " (" + s2cData.length + " bytes)");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save raw model data for " + playerUuid + "/" + safeName, e);
            }
        });
    }

    /**
     * 获取玩家的模型数据（S2C 格式）。
     * 返回第一个可用的模型数据，兼容现有协议流程。
     */
    public byte[] getModelData(UUID playerUuid) {
        Map<String, byte[]> models = playerModels.get(playerUuid);
        if (models != null && !models.isEmpty()) {
            return models.values().iterator().next();
        }

        // 从磁盘加载
        loadFromDisk(playerUuid);
        models = playerModels.get(playerUuid);
        if (models != null && !models.isEmpty()) {
            return models.values().iterator().next();
        }
        return null;
    }

    /**
     * 删除玩家的所有模型数据。
     */
    public void removeModelData(UUID playerUuid) {
        playerModels.remove(playerUuid);
        Path playerDir = modelsDir.resolve(playerUuid.toString());
        try {
            if (Files.isDirectory(playerDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(playerDir)) {
                    for (Path file : stream) {
                        Files.deleteIfExists(file);
                    }
                }
                Files.deleteIfExists(playerDir);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to delete model data for " + playerUuid, e);
        }
    }

    /**
     * 启动时从磁盘加载所有已存储的模型数据到内存。
     * 支持两种目录结构：
     * - 新格式：models/{UUID}/{模型名}
     * - 旧格式：models/{UUID}.ysm（自动迁移到新格式）
     */
    public void loadAllFromDisk() {
        if (!Files.exists(modelsDir)) return;
        int count = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modelsDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();

                if (Files.isDirectory(entry)) {
                    // 新格式：子目录
                    try {
                        UUID uuid = UUID.fromString(name);
                        count += loadFromDisk(uuid);
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().log(Level.WARNING, "Invalid player directory: " + name);
                    }
                } else if (name.endsWith(".ysm")) {
                    // 旧格式：扁平文件，自动迁移到子目录
                    String uuidStr = name.replace(".ysm", "");
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        byte[] data = Files.readAllBytes(entry);
                        Path playerDir = modelsDir.resolve(uuid.toString());
                        Files.createDirectories(playerDir);
                        Path target = playerDir.resolve("model");
                        Files.move(entry, target, StandardCopyOption.REPLACE_EXISTING);
                        playerModels.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put("model", data);
                        count++;
                        plugin.logDebug("Migrated model file: " + name + " -> " + uuid + "/model");
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().log(Level.WARNING, "Invalid model file name: " + name);
                    } catch (IOException e) {
                        plugin.getLogger().log(Level.WARNING, "Failed to migrate model file: " + name, e);
                    }
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to scan models directory", e);
        }

        if (count > 0) {
            plugin.getLogger().info("Loaded " + count + " model files from disk");
        }
    }

    /**
     * 从磁盘加载指定玩家的所有模型文件。
     *
     * @return 加载的文件数量
     */
    private int loadFromDisk(UUID playerUuid) {
        Path playerDir = modelsDir.resolve(playerUuid.toString());
        if (!Files.isDirectory(playerDir)) return 0;

        int count = 0;
        Map<String, byte[]> models = playerModels.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>());

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(playerDir)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) continue;
                String fileName = file.getFileName().toString();
                // 旧格式带 .ysm 后缀，迁移后去除；新格式无后缀
                String modelName = fileName.endsWith(".ysm") ? fileName.replace(".ysm", "") : fileName;
                try {
                    byte[] data = Files.readAllBytes(file);
                    models.put(modelName, data);
                    count++;
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to load model file: " + file, e);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to scan player directory: " + playerDir, e);
        }
        return count;
    }

    /**
     * 清理文件名中的非法字符。
     */
    private String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "model";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /**
     * 将 C2S 原始数据转换为 S2C 格式。
     * C2S 包 ID=2 的第一个 VarInt 是 2，需要替换为 1。
     */
    private byte[] convertC2S_to_S2C(byte[] c2sData) {
        if (c2sData == null || c2sData.length == 0) return null;
        try {
            ByteBuffer buf = ByteBuffer.wrap(c2sData);
            int packetId = VarIntUtil.readVarInt(buf);
            if (packetId != 2) return null;

            int remaining = c2sData.length - buf.position();
            ByteBuffer out = ByteBuffer.allocate(5 + remaining);
            VarIntUtil.writeVarInt(out, 1);
            out.put(c2sData, buf.position(), remaining);
            out.flip();
            byte[] result = new byte[out.remaining()];
            out.get(result);
            return result;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to convert C2S to S2C model data", e);
            return null;
        }
    }

    public void shutdown() {
        playerModels.clear();
    }
}
