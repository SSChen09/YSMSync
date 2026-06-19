package com.ysmsync.state;

import java.util.UUID;

/**
 * 单个玩家的 YSM 同步状态。
 */
public class PlayerYSMState {

    private final UUID playerUuid;
    private volatile boolean handshakeCompleted;
    private volatile String ysmVersion;
    private volatile String modelId;
    private volatile String textureId;
    private volatile boolean modelDisabled;
    private volatile byte[] lastEntityState;

    // 加密握手状态
    /** 握手步骤：0=未开始, 1=等待Pong, 2=等待Request, 3=完成 */
    private volatile int syncStep;
    /** 服务端生成的会话密钥（用于解密客户端响应） */
    private volatile byte[] key1;
    /** 客户端返回的密钥（用于加密 Packet 03） */
    private volatile byte[] clientNextKey;
    /** 客户端生成的 clientKey（56字节，发送给客户端用于缓存加密） */
    private volatile byte[] clientKey;

    public PlayerYSMState(UUID playerUuid) {
        this.playerUuid = playerUuid;
        this.handshakeCompleted = false;
        this.modelId = "";
        this.textureId = "";
        this.syncStep = 0;
    }

    public UUID getPlayerUuid() { return playerUuid; }

    public boolean isHandshakeCompleted() { return handshakeCompleted; }
    public void setHandshakeCompleted(boolean handshakeCompleted) { this.handshakeCompleted = handshakeCompleted; }

    public String getYsmVersion() { return ysmVersion; }
    public void setYsmVersion(String ysmVersion) { this.ysmVersion = ysmVersion; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public String getTextureId() { return textureId; }
    public void setTextureId(String textureId) { this.textureId = textureId; }

    public boolean isModelDisabled() { return modelDisabled; }
    public void setModelDisabled(boolean disabled) { this.modelDisabled = disabled; }

    public byte[] getLastEntityState() { return lastEntityState != null ? lastEntityState.clone() : null; }
    public void setLastEntityState(byte[] state) { this.lastEntityState = state != null ? state.clone() : null; }

    public boolean hasModel() {
        return modelId != null && !modelId.isEmpty();
    }

    // 加密握手状态方法
    public int getSyncStep() { return syncStep; }
    public void setSyncStep(int syncStep) { this.syncStep = syncStep; }

    public byte[] getKey1() { return key1 != null ? key1.clone() : null; }
    public void setKey1(byte[] key1) { this.key1 = key1 != null ? key1.clone() : null; }

    public byte[] getClientNextKey() { return clientNextKey != null ? clientNextKey.clone() : null; }
    public void setClientNextKey(byte[] clientNextKey) { this.clientNextKey = clientNextKey != null ? clientNextKey.clone() : null; }

    public byte[] getClientKey() { return clientKey != null ? clientKey.clone() : null; }
    public void setClientKey(byte[] clientKey) { this.clientKey = clientKey != null ? clientKey.clone() : null; }

    @Override
    public String toString() {
        return "PlayerYSMState{uuid=" + playerUuid + ", model=" + modelId + ", texture=" + textureId + "}";
    }
}
