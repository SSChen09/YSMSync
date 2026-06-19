package com.ysmsync.crypto;

import com.ysmsync.YSMPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 服务端密钥管理器。
 * 生成并持久化 serverKey（56字节：32 key + 24 iv），用于加密握手。
 */
public class ServerKeyManager {

    private static final SecureRandom random = new SecureRandom();
    private final Path keyFile;
    private byte[] serverKey;

    public ServerKeyManager(YSMPlugin plugin) {
        this.keyFile = plugin.getDataFolder().toPath().resolve("server_key.dat");
    }

    /**
     * 加载或生成服务端密钥。
     */
    public void loadOrCreate() {
        if (Files.exists(keyFile)) {
            try {
                String base64 = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
                serverKey = Base64.getDecoder().decode(base64);
                if (serverKey.length == 56) {
                    return;
                }
            } catch (Exception e) {
                // 密钥损坏，重新生成
            }
        }

        // 生成新密钥
        serverKey = new byte[56];
        random.nextBytes(serverKey);

        try {
            Files.createDirectories(keyFile.getParent());
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(serverKey), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 无法持久化，仅在内存中使用
        }
    }

    /**
     * 获取服务端密钥（56字节：32 key + 24 iv）。
     */
    public byte[] getServerKey() {
        return serverKey.clone();
    }
}
