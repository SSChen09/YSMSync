package com.ysmsync.model;

import com.ysmsync.YSMPlugin;
import com.ysmsync.util.VarIntUtil;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 模型文件上传管理器。
 * 处理 YSM 四步上传流程：Start → Chunk → Finish → Result
 */
public class ModelUploadManager {

    private final YSMPlugin plugin;
    private final ModelFileManager modelFileManager;

    // uploadId -> 上传会话
    private final Map<Long, UploadSession> sessions = new ConcurrentHashMap<>();
    private long nextUploadId = 1;

    // 上传状态码
    public static final byte STATUS_OK = 0;
    public static final byte STATUS_ERROR = 1;
    public static final byte STATUS_DENIED = 2;

    // 默认配置
    private final int defaultChunkSize;
    private final int maxTotalBytes;
    private final int chunksPerTick;

    public ModelUploadManager(YSMPlugin plugin, ModelFileManager modelFileManager) {
        this.plugin = plugin;
        this.modelFileManager = modelFileManager;
        this.defaultChunkSize = plugin.getConfig().getInt("upload.chunk-size", 32000); // 32KB, 与 Fox Model Loader 一致
        this.maxTotalBytes = plugin.getConfig().getInt("upload.max-size", 10485760);     // 10MB
        this.chunksPerTick = plugin.getConfig().getInt("upload.chunks-per-tick", 4);
    }

    /**
     * 上传会话数据。
     */
    public static class UploadSession {
        public final long uploadId;
        public final UUID playerUuid;
        public final String modelId;
        public final int totalBytes;
        public final String expectedSha256;
        public final ByteArrayOutputStream buffer;
        public final long createdAt;
        public int receivedBytes;
        public boolean finished;

        public UploadSession(long uploadId, UUID playerUuid, String modelId, int totalBytes, String expectedSha256) {
            this.uploadId = uploadId;
            this.playerUuid = playerUuid;
            this.modelId = modelId;
            this.totalBytes = totalBytes;
            this.expectedSha256 = expectedSha256;
            this.buffer = new ByteArrayOutputStream(totalBytes);
            this.receivedBytes = 0;
            this.finished = false;
            this.createdAt = System.currentTimeMillis();
        }
    }

    /**
     * 处理 C2SModelUploadStartPacket (ID=70)
     *
     * @return S2CModelUploadStartPacket 响应数据
     */
    public byte[] handleUploadStart(UUID playerUuid, ByteBuffer buf) {
        String modelId = VarIntUtil.readString(buf);
        String fileName = VarIntUtil.readString(buf);
        int totalBytes = VarIntUtil.readVarInt(buf);
        String sha256 = VarIntUtil.readString(buf);

        plugin.logDebug("Upload start: player=" + playerUuid + " model=" + modelId + " size=" + totalBytes);

        // 验证
        if (!plugin.getConfig().getBoolean("allow-upload", false)) {
            return buildUploadStartResponse(0, STATUS_DENIED, 0, 0, 0, "Upload not allowed");
        }

        if (totalBytes > maxTotalBytes) {
            return buildUploadStartResponse(0, STATUS_ERROR, 0, 0, 0,
                    "File too large: " + totalBytes + " > " + maxTotalBytes);
        }

        // 检查是否已有进行中的上传
        for (UploadSession session : sessions.values()) {
            if (session.playerUuid.equals(playerUuid) && !session.finished) {
                return buildUploadStartResponse(0, STATUS_ERROR, 0, 0, 0, "Upload already in progress");
            }
        }

        // 创建会话
        long uploadId = nextUploadId++;
        UploadSession session = new UploadSession(uploadId, playerUuid, modelId, totalBytes, sha256);
        sessions.put(uploadId, session);

        plugin.logDebug("Upload session created: id=" + uploadId + " model=" + modelId);

        return buildUploadStartResponse(uploadId, STATUS_OK, defaultChunkSize, maxTotalBytes, chunksPerTick, "OK");
    }

    /**
     * 处理 C2SModelUploadChunkPacket (ID=72)
     *
     * @return true 如果接收成功
     */
    public boolean handleUploadChunk(ByteBuffer buf) {
        long uploadId = VarIntUtil.readVarLong(buf);
        int offset = VarIntUtil.readVarInt(buf);
        int dataLen = buf.remaining();
        byte[] data = new byte[dataLen];
        buf.get(data);

        UploadSession session = sessions.get(uploadId);
        if (session == null || session.finished) return false;

        if (session.receivedBytes + dataLen > session.totalBytes) {
            plugin.logDebug("Upload chunk overflow: session=" + uploadId);
            return false;
        }

        try {
            session.buffer.write(data);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to write upload chunk", e);
            return false;
        }
        session.receivedBytes += dataLen;
        return true;
    }

    /**
     * 处理 C2SModelUploadFinishPacket (ID=73)
     *
     * @return S2CModelUploadResultPacket 响应数据
     */
    public byte[] handleUploadFinish(ByteBuffer buf) {
        long uploadId = VarIntUtil.readVarLong(buf);

        UploadSession session = sessions.get(uploadId);
        if (session == null) {
            return buildUploadResult(uploadId, STATUS_ERROR, "", 0, 0, "Invalid upload ID");
        }

        if (session.finished) {
            return buildUploadResult(uploadId, STATUS_ERROR, "", 0, 0, "Upload already completed");
        }

        session.finished = true;
        byte[] fileData = session.buffer.toByteArray();

        // 验证大小
        if (fileData.length != session.totalBytes) {
            sessions.remove(uploadId);
            return buildUploadResult(uploadId, STATUS_ERROR, session.modelId, 0, 0,
                    "Size mismatch: expected " + session.totalBytes + " got " + fileData.length);
        }

        // 验证 SHA256
        String actualSha256 = sha256(fileData);
        if (session.expectedSha256 != null && !session.expectedSha256.isEmpty()
                && !session.expectedSha256.equalsIgnoreCase(actualSha256)) {
            sessions.remove(uploadId);
            return buildUploadResult(uploadId, STATUS_ERROR, session.modelId, 0, 0,
                    "SHA256 mismatch");
        }

        // 存储模型文件
        modelFileManager.storeModelData(session.playerUuid, fileData);

        // 计算哈希值
        long h1 = hash1(fileData);
        long h2 = hash2(fileData);

        sessions.remove(uploadId);

        plugin.logDebug("Upload completed: model=" + session.modelId + " size=" + fileData.length);

        return buildUploadResult(uploadId, STATUS_OK, session.modelId, h1, h2, "Upload successful");
    }

    /**
     * 清理超时会话（5分钟）和已完成会话。
     */
    public void cleanupSessions() {
        long now = System.currentTimeMillis();
        long timeoutMs = 5 * 60 * 1000L;
        sessions.entrySet().removeIf(entry -> {
            UploadSession session = entry.getValue();
            if (session.finished) return true;
            if (now - session.createdAt > timeoutMs) {
                plugin.logDebug("Upload session timed out: id=" + session.uploadId);
                return true;
            }
            return false;
        });
    }

    /**
     * 关闭所有会话。
     */
    public void shutdown() {
        sessions.clear();
    }

    // === 包构建方法 ===

    private byte[] buildUploadStartResponse(long uploadId, byte status, int chunkSize,
                                            int maxTotal, int chunksPerTick, String message) {
        ByteBuffer buf = ByteBuffer.allocate(256);
        VarIntUtil.writeVarInt(buf, 71); // S2CModelUploadStartPacket
        VarIntUtil.writeVarLong(buf, uploadId);
        buf.put(status);
        VarIntUtil.writeVarInt(buf, chunkSize);
        VarIntUtil.writeVarInt(buf, maxTotal);
        VarIntUtil.writeVarInt(buf, chunksPerTick);
        VarIntUtil.writeString(buf, message);
        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    private byte[] buildUploadResult(long uploadId, byte status, String modelId,
                                     long h1, long h2, String message) {
        ByteBuffer buf = ByteBuffer.allocate(256);
        VarIntUtil.writeVarInt(buf, 74); // S2CModelUploadResultPacket
        VarIntUtil.writeVarLong(buf, uploadId);
        buf.put(status);
        VarIntUtil.writeString(buf, modelId);
        VarIntUtil.writeVarLong(buf, h1);
        VarIntUtil.writeVarLong(buf, h2);
        VarIntUtil.writeString(buf, message);
        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    // === 工具方法 ===

    private String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private long hash1(byte[] data) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : data) {
            hash ^= b;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private long hash2(byte[] data) {
        long hash = 0x517cc1b727220a95L;
        for (byte b : data) {
            hash = (hash << 5) + hash + (b & 0xFF);
        }
        return hash;
    }
}
