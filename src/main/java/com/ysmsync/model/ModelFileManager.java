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
 * 将客户端上传的 .ysm 模型原始数据存储到服务端磁盘，
 * 并在新玩家加入时发送给他们。
 */
public class ModelFileManager {

    private final YSMPlugin plugin;
    private final Path modelsDir;

    /**
     * 玩家 UUID -> 模型原始数据（完整的 YSM 二进制包，包含包头）
     */
    private final Map<UUID, byte[]> playerModelData = new ConcurrentHashMap<>();

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
     * 存储玩家的模型原始数据。
     * 数据是完整的 S2CModelSyncPayload 包内容（包含 VarInt packetId + 原始 .ysm 字节）。
     *
     * @param playerUuid 玩家 UUID
     * @param rawData    客户端发送的 C2SModelSyncPayload 原始数据（已去掉频道头）
     */
    public void storeModelData(UUID playerUuid, byte[] rawData) {
        // 转换为 S2C 格式：将 C2S 包 ID (2) 替换为 S2C 包 ID (1)
        byte[] s2cData = convertC2S_to_S2C(rawData);
        if (s2cData == null) return;

        playerModelData.put(playerUuid, s2cData);

        // 异步写入磁盘
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Path file = modelsDir.resolve(playerUuid.toString() + ".ysm");
                Files.write(file, s2cData);
                plugin.logDebug("Saved model data for " + playerUuid + " (" + s2cData.length + " bytes)");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save model data for " + playerUuid, e);
            }
        });
    }

    /**
     * 获取玩家的模型原始数据（S2C 格式）。
     * 优先从内存缓存读取，缓存未命中则从磁盘加载。
     */
    public byte[] getModelData(UUID playerUuid) {
        byte[] data = playerModelData.get(playerUuid);
        if (data != null) return data;

        // 从磁盘加载
        Path file = modelsDir.resolve(playerUuid.toString() + ".ysm");
        if (Files.exists(file)) {
            try {
                data = Files.readAllBytes(file);
                playerModelData.put(playerUuid, data);
                return data;
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load model data for " + playerUuid, e);
            }
        }
        return null;
    }

    /**
     * 存储原始 .ysm 文件数据（上传协议的文件内容，不含 C2S packetId 前缀）。
     * 自动包装为 S2C 格式（packetId=1 + 原始数据）后存储。
     */
    public void storeRawModelData(UUID playerUuid, byte[] rawData) {
        if (rawData == null || rawData.length == 0) return;

        // 包装为 S2C 格式：packetId=1 + 原始 .ysm 数据
        ByteBuffer out = ByteBuffer.allocate(5 + rawData.length);
        VarIntUtil.writeVarInt(out, 1); // S2CModelSyncPayload ID
        out.put(rawData);
        out.flip();
        byte[] s2cData = new byte[out.remaining()];
        out.get(s2cData);

        playerModelData.put(playerUuid, s2cData);

        // 异步写入磁盘
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Path file = modelsDir.resolve(playerUuid.toString() + ".ysm");
                Files.write(file, s2cData);
                plugin.logDebug("Saved raw model data for " + playerUuid + " (" + s2cData.length + " bytes)");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save raw model data for " + playerUuid, e);
            }
        });
    }

    /**
     * 删除玩家的模型数据。
     */
    public void removeModelData(UUID playerUuid) {
        playerModelData.remove(playerUuid);
        Path file = modelsDir.resolve(playerUuid.toString() + ".ysm");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to delete model data for " + playerUuid, e);
        }
    }

    /**
     * 启动时从磁盘加载所有已存储的模型数据到内存。
     */
    public void loadAllFromDisk() {
        if (!Files.exists(modelsDir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modelsDir, "*.ysm")) {
            int count = 0;
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                String uuidStr = fileName.replace(".ysm", "");
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    byte[] data = Files.readAllBytes(file);
                    playerModelData.put(uuid, data);
                    count++;
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().log(Level.WARNING, "Invalid model file name: " + fileName);
                }
            }
            if (count > 0) {
                plugin.getLogger().info("Loaded " + count + " model files from disk");
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to scan models directory", e);
        }
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
            if (packetId != 2) return null; // 不是 C2SModelSyncPayload

            // 重建为 S2C 格式：packetId=1 + 剩余数据
            int remaining = c2sData.length - buf.position();
            ByteBuffer out = ByteBuffer.allocate(5 + remaining);
            VarIntUtil.writeVarInt(out, 1); // S2CModelSyncPayload ID
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

    /**
     * 启动时加载，关闭时清理内存。
     */
    public void shutdown() {
        playerModelData.clear();
    }
}
