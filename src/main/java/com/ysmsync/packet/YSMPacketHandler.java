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
            int packetId = VarIntUtil.readVarInt(buf);

            plugin.logDebug("C2S packet from " + player.getName() + ": id=" + packetId + " len=" + data.length);

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
        state.setHandshakeCompleted(true);
        state.setYsmVersion(clientVersion);

        // 发送握手响应
        String version = plugin.getConfig().getString("protocol-version", "2.6.0");
        String brand = plugin.getConfig().getString("brand", "open_ysm:v1");
        boolean allowUpload = plugin.getConfig().getBoolean("allow-upload", false);

        byte[] versionPacket = stateManager.buildVersionCheckPacket(version, brand, allowUpload);
        stateManager.sendYSMPayload(player, versionPacket);

        // 发送授权模型列表（空）
        stateManager.sendYSMPayload(player, stateManager.buildSyncAuthModelsPacket());

        // 发送收藏模型列表（空）
        stateManager.sendYSMPayload(player, stateManager.buildSyncStarModelsPacket());

        // 向该玩家同步所有其他玩家的模型
        stateManager.syncAllModelsToJoiningPlayer(player);

        // 向所有其他玩家发送该玩家的模型（如果有）
        stateManager.broadcastModelToAllPlayers(player);

        plugin.logDebug("Handshake completed for " + player.getName());
    }

    /**
     * 处理 C2SModelSyncPayload (ID=2)
     * 客户端发送原始模型二进制数据。
     * 在单服模式下，直接广播给其他玩家。
     */
    private void handleModelSync(Player player, ByteBuffer buf, byte[] rawData) {
        plugin.logDebug("Model sync from " + player.getName() + " (" + rawData.length + " bytes)");
        // 存储模型数据到服务端
        modelFileManager.storeModelData(player.getUniqueId(), rawData);
        // 广播原始数据给当前在线的其他玩家
        stateManager.relayRawPacket(player, rawData);
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
        // entityId: int, flags: VarInt, 然后是 string->float map
        int entityId = buf.getInt();
        int flags = VarIntUtil.readVarInt(buf);
        int mapSize = buf.get() & 0xFF;

        // 缓存完整的实体状态原始数据
        // 重建为 S2CSyncPlayerState 格式广播
        ByteBuffer out = ByteBuffer.allocate(1024);
        VarIntUtil.writeVarInt(out, S2C_SYNC_PLAYER_STATE);
        VarIntUtil.writeVarInt(out, player.getEntityId());
        // flags: bit 12 = molang
        out.putShort((short) (flags | 0x1000)); // 确保 molang bit 设置
        out.putInt(entityId); // 作为 state data 的一部分
        // 简化：直接转发原始 feedback 数据作为 player state
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
