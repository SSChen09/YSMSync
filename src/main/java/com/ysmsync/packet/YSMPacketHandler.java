package com.ysmsync.packet;

import com.ysmsync.YSMPlugin;
import com.ysmsync.model.ModelFileManager;
import com.ysmsync.model.ModelUploadManager;
import com.ysmsync.state.PlayerYSMState;
import com.ysmsync.state.YSMStateManager;
import com.ysmsync.util.VarIntUtil;
import org.bukkit.entity.Player;

import java.nio.ByteBuffer;
import java.util.logging.Level;

/**
 * YSM 数据包处理器。
 * 解析 C2S 数据包，执行相应逻辑，并构建 S2C 响应。
 */
public class YSMPacketHandler {

    // C2S 包 ID
    public static final int C2S_MODEL_SYNC = 2;
    public static final int C2S_REQUEST_SWITCH_MODEL = 5;
    public static final int C2S_PLAY_ANIMATION = 7;
    public static final int C2S_SET_STAR_MODEL = 9;
    public static final int C2S_COMPLETE_FEEDBACK = 15;
    public static final int C2S_REQUEST_EXECUTE_MOLANG = 17;
    public static final int C2S_SYNC_ANIMATION_EXPRESSION = 18;
    public static final int C2S_SWING_ARM = 23;
    public static final int C2S_VERSION_CHECK = 52;
    public static final int C2S_MODEL_UPLOAD_START = 70;
    public static final int C2S_MODEL_UPLOAD_CHUNK = 72;
    public static final int C2S_MODEL_UPLOAD_FINISH = 73;

    // S2C 包 ID
    public static final int S2C_MODEL_SYNC = 1;
    public static final int S2C_SET_MODEL_AND_TEXTURE = 4;
    public static final int S2C_EXECUTE_MOLANG = 3;
    public static final int S2C_SYNC_ANIMATION_EXPRESSION = 19;
    public static final int S2C_SYNC_PLAYER_STATE = 21;
    public static final int S2C_VERSION_CHECK = 51;
    public static final int S2C_MODEL_UPLOAD_START = 71;
    public static final int S2C_MODEL_UPLOAD_RESULT = 74;

    private final YSMPlugin plugin;
    private final YSMStateManager stateManager;
    private final ModelFileManager modelFileManager;
    private final ModelUploadManager uploadManager;

    public YSMPacketHandler(YSMPlugin plugin, YSMStateManager stateManager,
                            ModelFileManager modelFileManager, ModelUploadManager uploadManager) {
        this.plugin = plugin;
        this.stateManager = stateManager;
        this.modelFileManager = modelFileManager;
        this.uploadManager = uploadManager;
    }

    /**
     * 处理从客户端收到的 YSM 原始负载数据。
     * 数据已经是去掉频道头之后的 YSM 协议内容。
     *
     * @return 是否应该继续处理（true=已处理，false=忽略）
     */
    public boolean handleIncoming(Player player, byte[] data) {
        if (data == null || data.length == 0) return false;

        try {
            ByteBuffer buf = ByteBuffer.wrap(data);

            // 先读取 VarInt 包 ID（所有 C2S 包都以 VarInt packetId 开头）
            int packetId = VarIntUtil.readVarInt(buf);

            // 根据 syncStep 处理加密握手包（Packet 02/04 都走 C2SModelSyncPayload 通道）
            PlayerYSMState state = stateManager.get(player.getUniqueId());
            if (state != null) {
                int syncStep = state.getSyncStep();
                if (syncStep == 1 && packetId == C2S_MODEL_SYNC) {
                    // 等待 HandshakePong (Packet 02)
                    byte[] remaining = new byte[buf.remaining()];
                    buf.get(remaining);
                    stateManager.handleHandshakePong(player, remaining);
                    return true;
                } else if (syncStep == 2 && packetId == C2S_MODEL_SYNC) {
                    // 等待 RequestModel (Packet 04)
                    byte[] remaining = new byte[buf.remaining()];
                    buf.get(remaining);
                    stateManager.handleRequestModel(player, remaining);
                    return true;
                }
            }

            // 正常数据包处理
            switch (packetId) {
                case C2S_VERSION_CHECK -> handleVersionCheck(player, buf);
                case C2S_MODEL_SYNC -> handleModelSync(player, buf, data);
                case C2S_REQUEST_SWITCH_MODEL -> handleRequestSwitchModel(player, buf);
                case C2S_PLAY_ANIMATION -> handlePlayAnimation(player, buf);
                case C2S_SYNC_ANIMATION_EXPRESSION -> handleSyncAnimationExpression(player, buf);
                case C2S_REQUEST_EXECUTE_MOLANG -> handleRequestExecuteMolang(player, buf);
                case C2S_COMPLETE_FEEDBACK -> handleCompleteFeedback(player, buf);
                case C2S_SWING_ARM -> handleSwingArm(player);
                case C2S_MODEL_UPLOAD_START -> handleUploadStart(player, buf);
                case C2S_MODEL_UPLOAD_CHUNK -> handleUploadChunk(player, buf);
                case C2S_MODEL_UPLOAD_FINISH -> handleUploadFinish(player, buf);
                case C2S_SET_STAR_MODEL -> {} // 收藏操作，服务端不处理
                default -> plugin.logDebug("Unknown C2S packet ID: " + packetId);
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error handling YSM packet from " + player.getName(), e);
            return false;
        }
    }

    /**
     * 处理 C2SVersionCheckPacket (ID=52)
     * 客户端回复版本确认，服务端执行完整初始化。
     */
    private void handleVersionCheck(Player player, ByteBuffer buf) {
        String clientVersion = VarIntUtil.readString(buf);
        plugin.logDebug("Version check from " + player.getName() + ": " + clientVersion);

        PlayerYSMState state = stateManager.getOrCreate(player.getUniqueId());
        state.setYsmVersion(clientVersion);

        // 仅在尚未开始握手时启动，避免 CapabilityEvent 重发 VersionCheck 导致重复握手
        if (state.getSyncStep() == 0) {
            stateManager.initiateHandshake(player);
        }
    }

    /**
     * 处理 C2SModelSyncPayload (ID=2)
     * 客户端发送原始模型二进制数据。
     * 在单服模式下，直接广播给其他玩家。
     */
    private void handleModelSync(Player player, ByteBuffer buf, byte[] rawData) {
        plugin.logDebug("Model sync from " + player.getName() + " (" + rawData.length + " bytes)");
        // 存储模型数据到服务端（内部会将 C2S 格式转换为 S2C 格式）
        modelFileManager.storeModelData(player.getUniqueId(), rawData);
        // 获取 S2C 格式的数据并广播给其他在线玩家
        byte[] s2cData = modelFileManager.getModelData(player.getUniqueId());
        if (s2cData != null) {
            stateManager.relayRawPacket(player, s2cData);
        }
    }

    /**
     * 处理 C2SRequestSwitchModelPacket (ID=5)
     * 客户端请求切换模型。服务端验证后广播给所有玩家。
     */
    private void handleRequestSwitchModel(Player player, ByteBuffer buf) {
        String modelId = VarIntUtil.readString(buf);
        String textureId = VarIntUtil.readString(buf);

        plugin.logDebug("Model switch request from " + player.getName() + ": model=" + modelId + " texture=" + textureId);

        // 广播模型切换
        stateManager.broadcastModelSwitch(player, modelId, textureId, false);
    }

    /**
     * 处理 C2SPlayAnimationPacket (ID=7)
     * 客户端请求播放动画。直接转发给其他玩家。
     */
    private void handlePlayAnimation(Player player, ByteBuffer buf) {
        // animationIndex: VarInt, category: String, entityId: VarInt
        int animIndex = VarIntUtil.readVarInt(buf);
        String category = VarIntUtil.readString(buf);
        int entityId = VarIntUtil.readVarInt(buf);

        // 重新构建包并广播
        ByteBuffer out = ByteBuffer.allocate(256);
        VarIntUtil.writeVarInt(out, S2C_EXECUTE_MOLANG); // 使用 S2C ID
        VarIntUtil.writeVarInt(out, 1); // 1 个实体
        VarIntUtil.writeVarInt(out, player.getEntityId());
        VarIntUtil.writeString(out, category + ":" + animIndex); // 简化的表达式
        out.flip();
        byte[] result = new byte[out.remaining()];
        out.get(result);

        stateManager.relayRawPacket(player, result);
    }

    /**
     * 处理 C2SSyncAnimationExpressionPacket (ID=18)
     * 客户端发送动画表达式计算结果。转发给其他玩家。
     */
    private void handleSyncAnimationExpression(Player player, ByteBuffer buf) {
        // floatData: Byte(count) + Float[] 
        int count = buf.get() & 0xFF;
        float[] floats = new float[count];
        for (int i = 0; i < count; i++) {
            floats[i] = buf.getFloat();
        }

        // 重建 S2C 包
        ByteBuffer out = ByteBuffer.allocate(64 + count * 4);
        VarIntUtil.writeVarInt(out, S2C_SYNC_ANIMATION_EXPRESSION);
        VarIntUtil.writeVarInt(out, player.getEntityId());
        out.put((byte) count);
        for (float f : floats) {
            out.putFloat(f);
        }
        out.flip();
        byte[] result = new byte[out.remaining()];
        out.get(result);

        stateManager.relayRawPacket(player, result);
    }

    /**
     * 处理 C2SRequestExecuteMolangPacket (ID=17)
     * 客户端请求广播 Molang 表达式。转发给其他玩家。
     */
    private void handleRequestExecuteMolang(Player player, ByteBuffer buf) {
        String animationName = VarIntUtil.readString(buf);
        int entityId = VarIntUtil.readVarInt(buf);

        // 重建 S2C 包
        ByteBuffer out = ByteBuffer.allocate(256);
        VarIntUtil.writeVarInt(out, S2C_EXECUTE_MOLANG);
        VarIntUtil.writeVarInt(out, 1); // 1 个实体
        VarIntUtil.writeVarInt(out, player.getEntityId());
        VarIntUtil.writeString(out, animationName);
        out.flip();
        byte[] result = new byte[out.remaining()];
        out.get(result);

        stateManager.relayRawPacket(player, result);
    }

    /**
     * 处理 C2SCompleteFeedbackPacket (ID=15)
     * 客户端反馈动画执行结果。缓存实体状态数据并广播。
     */
    private void handleCompleteFeedback(Player player, ByteBuffer buf) {
        int entityId = buf.getInt();
        int flags = VarIntUtil.readVarInt(buf);
        int mapSize = buf.get() & 0xFF;

        // 读取完整的 map 数据（string -> float pairs）并转发
        ByteBuffer out = ByteBuffer.allocate(4096);
        VarIntUtil.writeVarInt(out, S2C_SYNC_PLAYER_STATE);
        VarIntUtil.writeVarInt(out, player.getEntityId());
        out.putShort((short) (flags | 0x1000));
        out.putInt(entityId);
        out.put((byte) mapSize);
        for (int i = 0; i < mapSize; i++) {
            String key = VarIntUtil.readString(buf);
            float value = buf.getFloat();
            VarIntUtil.writeString(out, key);
            out.putFloat(value);
        }
        out.flip();
        byte[] result = new byte[out.remaining()];
        out.get(result);

        stateManager.relayRawPacket(player, result);
    }

    /**
     * 处理 C2SSwingArmPacket (ID=23)
     * 客户端自定义挥臂。转发给其他玩家。
     */
    private void handleSwingArm(Player player) {
        // 通过原版协议处理挥臂，YSM 客户端会同时发送原版动画包
        // 这里不需要额外处理
    }

    /**
     * 处理 C2SModelUploadStartPacket (ID=70)
     * 客户端请求开始模型上传。
     */
    private void handleUploadStart(Player player, ByteBuffer buf) {
        byte[] response = uploadManager.handleUploadStart(player.getUniqueId(), buf);
        stateManager.sendYSMPayload(player, response);
    }

    /**
     * 处理 C2SModelUploadChunkPacket (ID=72)
     * 客户端发送上传数据分块。
     */
    private void handleUploadChunk(Player player, ByteBuffer buf) {
        uploadManager.handleUploadChunk(buf);
    }

    /**
     * 处理 C2SModelUploadFinishPacket (ID=73)
     * 客户端通知上传完成，服务端验证并返回结果。
     */
    private void handleUploadFinish(Player player, ByteBuffer buf) {
        byte[] response = uploadManager.handleUploadFinish(buf);
        stateManager.sendYSMPayload(player, response);

        // 上传成功后，广播模型文件给所有在线玩家
        byte[] modelFile = modelFileManager.getModelData(player.getUniqueId());
        if (modelFile != null) {
            stateManager.broadcastYSMPayloadExcept(player, modelFile);
        }
    }
}
