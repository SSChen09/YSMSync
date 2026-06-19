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

    public PlayerYSMState(UUID playerUuid) {
        this.playerUuid = playerUuid;
        this.handshakeCompleted = false;
        this.modelId = "";
        this.textureId = "";
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

    public byte[] getLastEntityState() { return lastEntityState; }
    public void setLastEntityState(byte[] state) { this.lastEntityState = state; }

    public boolean hasModel() {
        return modelId != null && !modelId.isEmpty();
    }

    @Override
    public String toString() {
        return "PlayerYSMState{uuid=" + playerUuid + ", model=" + modelId + ", texture=" + textureId + "}";
    }
}
