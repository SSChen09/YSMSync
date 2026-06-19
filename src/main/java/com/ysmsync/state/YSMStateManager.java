package com.ysmsync.state;

import com.ysmsync.YSMPlugin;
import com.ysmsync.crypto.ServerKeyManager;
import com.ysmsync.crypto.YsmCrypt;
import com.ysmsync.model.ModelFileManager;
import com.ysmsync.net.YSMByteBuf;
import com.ysmsync.storage.YSMStorage;
import com.ysmsync.util.VarIntUtil;
import io.netty.buffer.Unpooled;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理所有在线玩家的 YSM 状态。
 * 负责状态的创建、查询、更新和广播。
 */
public class YSMStateManager {

    private final YSMPlugin plugin;
    private final Map<UUID, PlayerYSMState> states = new ConcurrentHashMap<>();
    private final YSMStorage storage;
    private final ModelFileManager modelFileManager;
    private final SecureRandom random = new SecureRandom();
    private ServerKeyManager serverKeyManager;

    public YSMStateManager(YSMPlugin plugin, YSMStorage storage, ModelFileManager modelFileManager) {
        this.plugin = plugin;
        this.storage = storage;
        this.modelFileManager = modelFileManager;
    }

    public void setServerKeyManager(ServerKeyManager serverKeyManager) {
        this.serverKeyManager = serverKeyManager;
    }

    public PlayerYSMState getOrCreate(UUID uuid) {
        return states.computeIfAbsent(uuid, id -> {
            PlayerYSMState state = new PlayerYSMState(id);
            // 从存储中恢复
            storage.loadModel(id).ifPresent(data -> {
                state.setModelId(data.modelId());
                state.setTextureId(data.textureId());
            });
            return state;
        });
    }

    public PlayerYSMState get(UUID uuid) {
        return states.get(uuid);
    }

    public void remove(UUID uuid) {
        states.remove(uuid);
    }

    /**
     * 玩家加入时，向该玩家发送所有其他玩家的模型数据。
     */
    public void syncAllModelsToJoiningPlayer(Player joiner) {
        PlayerYSMState joinerState = getOrCreate(joiner.getUniqueId());
        if (!joinerState.isHandshakeCompleted()) return;

        for (Map.Entry<UUID, PlayerYSMState> entry : states.entrySet()) {
            if (entry.getKey().equals(joiner.getUniqueId())) continue;
            PlayerYSMState other = entry.getValue();
            if (!other.hasModel()) continue;

            // 先发送模型文件数据（让客户端能渲染模型）
            byte[] modelFile = modelFileManager.getModelData(entry.getKey());
            if (modelFile != null) {
                sendYSMPayload(joiner, modelFile);
            }

            // 再发送模型和纹理设置
            Player otherPlayer = Bukkit.getPlayer(entry.getKey());
            if (otherPlayer == null || !otherPlayer.isOnline()) continue;

            byte[] packet = buildSetModelAndTexturePacket(
                    otherPlayer.getEntityId(),
                    other.getModelId(),
                    other.getTextureId(),
                    other.isModelDisabled()
            );
            sendYSMPayload(joiner, packet);

            // 如果有缓存的实体状态数据，也一并发送
            if (other.getLastEntityState() != null) {
                sendYSMPayload(joiner, other.getLastEntityState());
            }
        }
    }

    /**
     * 玩家加入时，向所有在线玩家发送该玩家的模型数据。
     */
    public void broadcastModelToAllPlayers(Player joiner) {
        PlayerYSMState joinerState = get(joiner.getUniqueId());
        if (joinerState == null || !joinerState.hasModel()) return;

        // 先发送模型文件数据给所有其他玩家
        byte[] modelFile = modelFileManager.getModelData(joiner.getUniqueId());
        if (modelFile != null) {
            broadcastYSMPayloadExcept(joiner, modelFile);
        }

        // 再发送模型和纹理设置
        byte[] packet = buildSetModelAndTexturePacket(
                joiner.getEntityId(),
                joinerState.getModelId(),
                joinerState.getTextureId(),
                joinerState.isModelDisabled()
        );
        broadcastYSMPayloadExcept(joiner, packet);

        if (joinerState.getLastEntityState() != null) {
            broadcastYSMPayloadExcept(joiner, joinerState.getLastEntityState());
        }
    }

    /**
     * 广播模型切换给所有在线玩家。
     */
    public void broadcastModelSwitch(Player player, String modelId, String textureId, boolean disabled) {
        PlayerYSMState state = getOrCreate(player.getUniqueId());
        state.setModelId(modelId);
        state.setTextureId(textureId);
        state.setModelDisabled(disabled);

        // 持久化
        storage.saveModel(player.getUniqueId(), modelId, textureId);

        byte[] packet = buildSetModelAndTexturePacket(
                player.getEntityId(), modelId, textureId, disabled
        );
        broadcastYSMPayloadExcept(player, packet);

        plugin.logDebug("Broadcast model switch for " + player.getName() + ": model=" + modelId);
    }

    /**
     * 广播原始 YSM 数据包给除发送者外的所有在线玩家。
     */
    public void relayRawPacket(Player sender, byte[] ysmPayload) {
        broadcastYSMPayloadExcept(sender, ysmPayload);
    }

    /**
     * 向单个玩家发送 YSM 原始负载数据。
     */
    public void sendYSMPayload(Player target, byte[] payload) {
        if (!target.isOnline()) return;
        target.sendPluginMessage(plugin, plugin.getYsmChannel(), payload);
    }

    /**
     * 向除指定玩家外的所有在线玩家发送 YSM 负载。
     */
    public void broadcastYSMPayloadExcept(Player except, byte[] payload) {
        String channel = plugin.getYsmChannel();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getUniqueId().equals(except.getUniqueId())) continue;
            // 只向已握手的玩家发送
            PlayerYSMState state = get(player.getUniqueId());
            if (state != null && state.isHandshakeCompleted()) {
                player.sendPluginMessage(plugin, channel, payload);
            }
        }
    }

    /**
     * 向所有已握手的在线玩家广播 YSM 负载。
     */
    public void broadcastYSMPayload(byte[] payload) {
        String channel = plugin.getYsmChannel();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerYSMState state = get(player.getUniqueId());
            if (state != null && state.isHandshakeCompleted()) {
                player.sendPluginMessage(plugin, channel, payload);
            }
        }
    }

    /**
     * 构建 S2C SetModelAndTexture 数据包 (ID=4)。
     * 格式: [packetId:VarInt][entityId:VarInt][modelId:String][textureId:String][disabled:boolean]
     *
     * 注意：这个包没有嵌套 SyncPlayerState，因为 Spigot 端不维护 Molang 变量。
     */
    public byte[] buildSetModelAndTexturePacket(int entityId, String modelId, String textureId, boolean disabled) {
        // 预估大小
        ByteBuffer buf = ByteBuffer.allocate(4096);
        VarIntUtil.writeVarInt(buf, 4); // packet ID
        VarIntUtil.writeVarInt(buf, entityId);
        VarIntUtil.writeString(buf, modelId != null ? modelId : "");
        VarIntUtil.writeString(buf, textureId != null ? textureId : "");
        buf.put((byte) (disabled ? 1 : 0));
        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    /**
     * 构建 S2C VersionCheck 数据包 (ID=51)。
     */
    public byte[] buildVersionCheckPacket(String version, String brand, boolean allowUpload) {
        ByteBuffer buf = ByteBuffer.allocate(256);
        VarIntUtil.writeVarInt(buf, 51); // packet ID
        VarIntUtil.writeString(buf, version);
        VarIntUtil.writeString(buf, brand);
        buf.put((byte) (allowUpload ? 1 : 0));
        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    /**
     * 构建 S2C SyncAuthModels 数据包 (ID=6)。
     * 空列表表示没有需要授权的模型。
     */
    public byte[] buildSyncAuthModelsPacket() {
        ByteBuffer buf = ByteBuffer.allocate(64);
        VarIntUtil.writeVarInt(buf, 6); // packet ID
        VarIntUtil.writeVarInt(buf, 0); // 空集合
        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    /**
     * 构建 S2C SyncStarModels 数据包 (ID=8)。
     * 空列表表示没有收藏模型。
     */
    public byte[] buildSyncStarModelsPacket() {
        ByteBuffer buf = ByteBuffer.allocate(64);
        VarIntUtil.writeVarInt(buf, 8); // packet ID
        VarIntUtil.writeVarInt(buf, 0); // 空集合
        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    /**
     * 发送 S2C ModelSyncPayload（discriminator=1）。
     * 对于加密握手包，payload 是加密后的数据，需要在前面加上 discriminator 字节。
     */
    public void sendModelSyncPayload(Player target, byte[] encryptedData) {
        if (!target.isOnline()) return;
        // S2CModelSyncPayload discriminator = 1
        byte[] payload = new byte[1 + encryptedData.length];
        payload[0] = 1; // discriminator for ModelSyncPayload
        System.arraycopy(encryptedData, 0, payload, 1, encryptedData.length);
        target.sendPluginMessage(plugin, plugin.getYsmChannel(), payload);
    }

    /**
     * 发送 S2C ModelSyncPayload (discriminator=1) 给所有已握手玩家（除发送者外）。
     */
    public void broadcastModelSyncPayloadExcept(Player except, byte[] encryptedData) {
        String channel = plugin.getYsmChannel();
        byte[] payload = new byte[1 + encryptedData.length];
        payload[0] = 1;
        System.arraycopy(encryptedData, 0, payload, 1, encryptedData.length);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getUniqueId().equals(except.getUniqueId())) continue;
            PlayerYSMState state = get(player.getUniqueId());
            if (state != null && state.isHandshakeCompleted()) {
                player.sendPluginMessage(plugin, channel, payload);
            }
        }
    }

    /**
     * 启动加密握手：发送 HandshakePing (Packet 01)。
     * 在版本检查完成后调用。
     */
    public void initiateHandshake(Player player) {
        PlayerYSMState state = getOrCreate(player.getUniqueId());
        byte[] serverKey = serverKeyManager.getServerKey();

        // 生成 clientKey（固定值，与 OpenYSM 兼容）
        byte[] clientKey = new byte[56];
        new Random(114514).nextBytes(clientKey);
        state.setClientKey(clientKey);

        try {
            // 构建垃圾头部 + 0x01 类型标识
            int garbageLen = 16 + random.nextInt(48);
            byte[] garbage = new byte[garbageLen];
            random.nextBytes(garbage);

            try (YSMByteBuf outBuf = new YSMByteBuf(Unpooled.buffer())) {
                outBuf.writeGarbageHeader(garbageLen, garbage);
                outBuf.writeByte((byte) 0x01); // Packet 01 类型标识

                // 用 publicKey 加密，附加 nextKey
                YsmCrypt.EncryptedPacket result = YsmCrypt.encrypt(outBuf.toArray(), YsmCrypt.publicKey, true);
                state.setKey1(result.nextKey());
                state.setSyncStep(1); // 等待 Pong

                sendModelSyncPayload(player, result.data());
                plugin.logDebug("Sent HandshakePing to " + player.getName());
            }
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to send HandshakePing to " + player.getName(), e);
        }
    }

    /**
     * 处理 HandshakePong (Packet 02)。
     * 客户端回复加密的 clientNextKey。
     */
    public void handleHandshakePong(Player player, byte[] rawData) {
        PlayerYSMState state = get(player.getUniqueId());
        if (state == null || state.getSyncStep() != 1) return;

        try {
            byte[] decrypted = YsmCrypt.decrypt(rawData, state.getKey1());
            if (decrypted == null || decrypted.length < 56) return;

            // 提取 clientNextKey（最后56字节）
            state.setClientNextKey(java.util.Arrays.copyOfRange(decrypted, decrypted.length - 56, decrypted.length));

            // 验证内部类型标识
            byte[] payload = java.util.Arrays.copyOfRange(decrypted, 0, decrypted.length - 56);
            try (YSMByteBuf buf = new YSMByteBuf(Unpooled.wrappedBuffer(payload))) {
                buf.skipGarbageHeader();
                if (buf.getRawBuf().readByte() != 0x02) return;
            }

            // 进入步骤2，发送 Packet 03（模型目录）
            state.setSyncStep(2);
            sendPacket03(player, state);
            plugin.logDebug("Handled HandshakePong from " + player.getName() + ", sent ModelDirectory");
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to handle HandshakePong from " + player.getName(), e);
        }
    }

    /**
     * 发送 Packet 03 (ModelDirectory) - 空模型列表。
     * 客户端收到后会发送 Packet 04 请求模型。
     */
    private void sendPacket03(Player player, PlayerYSMState state) {
        try {
            byte[] serverKey = serverKeyManager.getServerKey();
            byte[] clientKey = state.getClientKey();

            int garbageLen = 16 + random.nextInt(48);
            byte[] garbage = new byte[garbageLen];
            random.nextBytes(garbage);

            try (YSMByteBuf outBuf = new YSMByteBuf(Unpooled.buffer())) {
                outBuf.writeGarbageHeader(garbageLen, garbage);
                outBuf.writeVarInt(3); // Packet 03 类型
                outBuf.writeVarLong(0L); // cache 文件夹名

                // 写入 serverKey 和 clientKey（各56字节原始数据）
                outBuf.getRawBuf().writeBytes(serverKey);
                outBuf.getRawBuf().writeBytes(clientKey);

                // 模型列表（空）
                outBuf.writeVarInt(0);

                // Pack 列表（空）
                outBuf.writeVarInt(0);

                // 终止符
                outBuf.writeVarInt(0);

                // 用 clientNextKey 加密
                YsmCrypt.EncryptedPacket result = YsmCrypt.encrypt(outBuf.toArray(), state.getClientNextKey(), false);
                sendModelSyncPayload(player, result.data());
            }
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to send Packet03 to " + player.getName(), e);
        }
    }

    /**
     * 处理 RequestModel (Packet 04)。
     * 客户端请求模型文件。目前模型列表为空，所以无需发送 Packet 05。
     * 握手完成。
     */
    public void handleRequestModel(Player player, byte[] rawData) {
        PlayerYSMState state = get(player.getUniqueId());
        if (state == null || state.getSyncStep() != 2) return;

        try {
            byte[] decrypted = YsmCrypt.decrypt(rawData, state.getClientNextKey());
            if (decrypted == null) return;

            try (YSMByteBuf buf = new YSMByteBuf(Unpooled.wrappedBuffer(decrypted))) {
                buf.skipGarbageHeader();
                if (buf.getRawBuf().readByte() != 0x04) return;

                // 读取请求的模型哈希列表（暂不处理）
                int numRequests = buf.readVarInt();
                plugin.logDebug("Received RequestModel from " + player.getName() + " with " + numRequests + " requests");
            }

            // 握手完成
            state.setSyncStep(3);
            state.setHandshakeCompleted(true);

            // 发送版本响应、授权模型列表、收藏模型列表
            String version = plugin.getConfig().getString("protocol-version", "2.6.0");
            String brand = plugin.getConfig().getString("brand", "open_ysm:v1");
            boolean allowUpload = plugin.getConfig().getBoolean("allow-upload", false);

            sendYSMPayload(player, buildVersionCheckPacket(version, brand, allowUpload));
            sendYSMPayload(player, buildSyncAuthModelsPacket());
            sendYSMPayload(player, buildSyncStarModelsPacket());

            // 向该玩家同步所有其他玩家的模型
            syncAllModelsToJoiningPlayer(player);
            // 向所有其他玩家发送该玩家的模型
            Player otherPlayer = Bukkit.getPlayer(player.getUniqueId());
            if (otherPlayer != null) {
                broadcastModelToAllPlayers(otherPlayer);
            }

            plugin.logDebug("Handshake completed for " + player.getName());
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to handle RequestModel from " + player.getName(), e);
        }
    }
}
