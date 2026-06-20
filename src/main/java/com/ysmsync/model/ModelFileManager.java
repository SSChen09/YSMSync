package com.ysmsync.model;

import com.ysmsync.YSMPlugin;
import com.ysmsync.crypto.YsmCrypt;
import com.ysmsync.net.YSMByteBuf;
import com.ysmsync.resource.YSMBinaryDeserializer;
import com.ysmsync.resource.YSMBinarySerializer;
import com.ysmsync.resource.pojo.RawYsmModel;
import com.ysmsync.util.VarIntUtil;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 模型文件存储管理器。
 * 目录结构：
 * - plugins/YSMSync/models/{玩家UUID}/{模型名}  — S2C 格式模型数据
 * - plugins/YSMSync/cache/{hash1_hex}{hash2_hex} — 加密缓存文件（供 Packet 05 发送）
 * 每个玩家一个子目录，支持存储多个模型。
 */
public class ModelFileManager {

    private final YSMPlugin plugin;
    private final Path modelsDir;
    private final Path cacheDir;
    private byte[] serverKey;

    /**
     * 玩家 UUID -> (模型文件名 -> S2C 格式数据)
     */
    private final Map<UUID, Map<String, byte[]>> playerModels = new ConcurrentHashMap<>();

    /**
     * 模型缓存条目：用于 Packet 03 模型列表。
     */
    public record ModelCacheEntry(String modelId, long hash1, long hash2) {}

    /**
     * 玩家 UUID -> (模型文件名 -> 缓存条目)
     */
    private final Map<UUID, Map<String, ModelCacheEntry>> playerCacheEntries = new ConcurrentHashMap<>();

    public ModelFileManager(YSMPlugin plugin) {
        this.plugin = plugin;
        this.modelsDir = plugin.getDataFolder().toPath().resolve("models");
        this.cacheDir = plugin.getDataFolder().toPath().resolve("cache");
        try {
            Files.createDirectories(modelsDir);
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create directories", e);
        }
    }

    /**
     * 设置服务端密钥（用于计算模型哈希和加密缓存）。
     */
    public void setServerKey(byte[] serverKey) {
        this.serverKey = serverKey;
    }

    /**
     * 存储玩家的模型数据（C2SModelSyncPayload 格式）。
     * 自动转换为 S2C 格式后存储，并创建加密缓存文件。
     * 如果玩家已有通过上传流程存储的命名模型，则跳过，避免覆盖。
     */
    public void storeModelData(UUID playerUuid, byte[] rawData) {
        byte[] s2cData = convertC2S_to_S2C(rawData);
        if (s2cData == null) return;

        Map<String, byte[]> models = playerModels.get(playerUuid);
        if (models != null && !models.isEmpty()) {
            // 玩家已有模型数据（通过上传流程存储），跳过 ModelSync 的覆盖写入
            return;
        }

        String name = "model";
        playerModels.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>()).put(name, s2cData);

        // 提取原始 .ysm 二进制数据，解密处理后创建缓存
        byte[] modelBinary = extractModelBinary(s2cData);
        if (modelBinary != null) {
            byte[] cacheData = decryptAndProcessYsm(modelBinary, name);
            if (cacheData != null) {
                createCacheEntry(playerUuid, name, cacheData);
            }
        }

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
     * 自动包装为 S2C 格式（packetId=1 + 原始数据）后存储，并创建加密缓存文件。
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

        // 解密 .ysm 文件后创建缓存
        byte[] cacheData = decryptAndProcessYsm(rawData, safeName);
        if (cacheData != null) {
            createCacheEntry(playerUuid, safeName, cacheData);
        }

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
     * 创建加密缓存条目（异步）。
     * 计算模型 SHA256 → hash1/hash2 → 加密缓存文件。
     */
    private void createCacheEntry(UUID playerUuid, String modelName, byte[] modelBinary) {
        if (serverKey == null) return;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String sha256 = sha256Hex(modelBinary);
                long[] hashes = YsmCrypt.calculateModelHashes(sha256, serverKey);
                long hash1 = hashes[0];
                long hash2 = hashes[1];

                String cacheFileName = String.format("%016x%016x", hash1, hash2);
                Path cacheFile = cacheDir.resolve(cacheFileName);

                // 如果缓存文件不存在，创建加密缓存
                if (!Files.exists(cacheFile)) {
                    byte[] encrypted = YsmCrypt.encryptServerCache(modelBinary, serverKey, hash1, hash2);
                    Files.write(cacheFile, encrypted);
                    plugin.logDebug("Created cache file: " + cacheFileName + " (" + encrypted.length + " bytes)");
                }

                // 存储缓存条目
                ModelCacheEntry entry = new ModelCacheEntry(modelName, hash1, hash2);
                playerCacheEntries
                        .computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>())
                        .put(modelName, entry);

                plugin.logDebug("Cache entry created for " + playerUuid + "/" + modelName
                        + " sha256=" + sha256.substring(0, 16) + "...");
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to create cache entry for " + playerUuid + "/" + modelName, e);
            }
        });
    }

    /**
     * 获取玩家的所有模型缓存条目（用于填充 Packet 03）。
     * 如果内存中没有，尝试从磁盘加载。
     */
    public List<ModelCacheEntry> getPlayerModelEntries(UUID playerUuid) {
        Map<String, ModelCacheEntry> entries = playerCacheEntries.get(playerUuid);
        if (entries != null && !entries.isEmpty()) {
            return new ArrayList<>(entries.values());
        }

        // 尝试从磁盘加载
        loadFromDisk(playerUuid);
        entries = playerCacheEntries.get(playerUuid);
        if (entries != null && !entries.isEmpty()) {
            return new ArrayList<>(entries.values());
        }
        return Collections.emptyList();
    }

    /**
     * 根据 hash1/hash2 获取加密缓存文件数据（用于 Packet 05 发送）。
     */
    public byte[] getCacheFileData(long hash1, long hash2) {
        String cacheFileName = String.format("%016x%016x", hash1, hash2);
        Path cacheFile = cacheDir.resolve(cacheFileName);
        try {
            if (Files.exists(cacheFile)) {
                return Files.readAllBytes(cacheFile);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read cache file: " + cacheFileName, e);
        }
        return null;
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
        playerCacheEntries.remove(playerUuid);
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
     * 同时尝试重建缓存条目（如果缓存文件存在但内存条目缺失）。
     */
    public void loadAllFromDisk() {
        if (!Files.exists(modelsDir)) return;
        int count = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modelsDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();

                if (Files.isDirectory(entry)) {
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

        // 为已加载的模型重建缓存条目（如果缓存文件已存在）
        if (serverKey != null) {
            rebuildCacheEntries();
        }
    }

    /**
     * 为已加载但缺少缓存条目的模型重建条目。
     * 从 S2C 数据中提取原始 .ysm，解密处理后创建正确的缓存文件。
     */
    private void rebuildCacheEntries() {
        for (Map.Entry<UUID, Map<String, byte[]>> playerEntry : playerModels.entrySet()) {
            UUID uuid = playerEntry.getKey();
            Map<String, ModelCacheEntry> existingEntries = playerCacheEntries.getOrDefault(uuid, Collections.emptyMap());

            for (Map.Entry<String, byte[]> modelEntry : playerEntry.getValue().entrySet()) {
                String modelName = modelEntry.getKey();
                if (existingEntries.containsKey(modelName)) continue;

                byte[] s2cData = modelEntry.getValue();
                byte[] modelBinary = extractModelBinary(s2cData);
                if (modelBinary == null) continue;

                // 解密 .ysm 文件并处理格式（与 storeRawModelData 路径一致）
                byte[] cacheData = decryptAndProcessYsm(modelBinary, modelName);
                if (cacheData == null) {
                    // 解密失败时回退到原始数据
                    cacheData = modelBinary;
                }

                try {
                    String sha256 = sha256Hex(cacheData);
                    long[] hashes = YsmCrypt.calculateModelHashes(sha256, serverKey);
                    String cacheFileName = String.format("%016x%016x", hashes[0], hashes[1]);
                    Path cacheFile = cacheDir.resolve(cacheFileName);

                    // 如果缓存文件不存在，创建它
                    if (!Files.exists(cacheFile)) {
                        byte[] encrypted = YsmCrypt.encryptServerCache(cacheData, serverKey, hashes[0], hashes[1]);
                        Files.write(cacheFile, encrypted);
                        plugin.logDebug("Rebuilt cache file: " + cacheFileName);
                    }

                    ModelCacheEntry entry = new ModelCacheEntry(modelName, hashes[0], hashes[1]);
                    playerCacheEntries
                            .computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                            .put(modelName, entry);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to rebuild cache entry for " + uuid + "/" + modelName, e);
                }
            }
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
                String modelName = fileName.endsWith(".ysm") ? fileName.replace(".ysm", "") : fileName;
                try {
                    byte[] data = Files.readAllBytes(file);
                    models.put(modelName, data);

                    // 尝试为加载的模型重建缓存条目
                    if (serverKey != null && !playerCacheEntries.containsKey(playerUuid)) {
                        byte[] modelBinary = extractModelBinary(data);
                        if (modelBinary != null) {
                            byte[] cacheData = decryptAndProcessYsm(modelBinary, modelName);
                            if (cacheData != null) {
                                String sha256 = sha256Hex(cacheData);
                                long[] hashes = YsmCrypt.calculateModelHashes(sha256, serverKey);
                                ModelCacheEntry entry = new ModelCacheEntry(modelName, hashes[0], hashes[1]);
                                playerCacheEntries
                                        .computeIfAbsent(playerUuid, k2 -> new ConcurrentHashMap<>())
                                        .put(modelName, entry);
                            }
                        }
                    }

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
     * 从 S2C 格式数据中提取模型二进制数据（去掉包头 VarInt）。
     */
    private byte[] extractModelBinary(byte[] s2cData) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(s2cData);
            VarIntUtil.readVarInt(buf); // 跳过 packetId
            int remaining = buf.remaining();
            byte[] binary = new byte[remaining];
            buf.get(binary);
            return binary;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解密并处理 .ysm 原始数据，返回适合缓存的模型明文。
     * 对格式 >= 16 的模型去掉 4 字节 format DWORD；格式 < 16 的旧模型使用原始解密数据。
     *
     * @param rawYsmData 原始 .ysm 文件字节
     * @param modelName  模型名称（用于日志）
     * @return 处理后的缓存数据，失败返回 null
     */
    private byte[] decryptAndProcessYsm(byte[] rawYsmData, String modelName) {
        try {
            byte[] decryptedModel = YsmCrypt.decryptYsmFile(rawYsmData);
            if (decryptedModel.length > 4) {
                int formatDword = (decryptedModel[0] & 0xFF)
                        | ((decryptedModel[1] & 0xFF) << 8)
                        | ((decryptedModel[2] & 0xFF) << 16)
                        | ((decryptedModel[3] & 0xFF) << 24);
                if (formatDword >= 16) {
                    // 现代格式：反序列化后重新序列化为 format 32（与 Fox Model Loader 一致）
                    try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(decryptedModel)) {
                        RawYsmModel model = deserializer.deserializeKeepOpen();
                        deserializer.parseYSMFooter(model);
                        try (YSMByteBuf serialized = YSMBinarySerializer.serialize(model, 32, true)) {
                            byte[] format32Data = serialized.toArray();
                            plugin.logDebug("Decrypted " + modelName + " format=" + formatDword
                                    + " -> re-serialized to format 32, cache data=" + format32Data.length + " bytes");
                            return format32Data;
                        }
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING,
                                "Deserialize/re-serialize failed for " + modelName + ", falling back to stripped header", e);
                        byte[] cacheData = new byte[decryptedModel.length - 4];
                        System.arraycopy(decryptedModel, 4, cacheData, 0, cacheData.length);
                        return cacheData;
                    }
                } else {
                    // 旧格式（format < 16）：使用解密后的原始数据（序列化器不支持 format<16）
                    plugin.getLogger().log(Level.WARNING,
                            "Legacy format " + formatDword + " for " + modelName + ", cache may not work");
                    return decryptedModel;
                }
            }
            return decryptedModel;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to decrypt .ysm file for " + modelName + ", caching raw data", e);
            return null;
        }
    }

    /**
     * 计算 SHA256 并返回十六进制字符串。
     */
    private String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
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
        playerCacheEntries.clear();
    }
}
