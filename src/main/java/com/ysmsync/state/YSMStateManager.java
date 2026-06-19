package com.ysmsync.state;

import com.ysmsync.YSMPlugin;
import com.ysmsync.model.ModelFileManager;
import com.ysmsync.storage.YSMStorage;
import com.ysmsync.util.VarIntUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.ByteBuffer;
import java.util.Map;
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

    public YSMStateManager(YSMPlugin plugin, YSMStorage storage, ModelFileManager modelFileManager) {
        this.plugin = plugin;
        this.storage = storage;
        this.modelFileManager = modelFileManager;
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
}
