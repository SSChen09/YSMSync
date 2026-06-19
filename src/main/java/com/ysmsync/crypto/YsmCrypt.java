package com.ysmsync.crypto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.security.SecureRandom;

/**
 * YSM 加密工具类。
 * 提供握手包的加解密功能（XChaCha20 + MT19937 XOR + CityHash64）。
 * 移植自 OpenYSM YsmCrypt（仅保留握手相关方法）。
 */
public class YsmCrypt {
    private static final CityHash CITY_HASH = new CityHash();
    private static final SecureRandom random = new SecureRandom();
    public static final long SEED_PACKET_VERIFICATION = 0xEE6FA63D570BD77BL;
    public static final long SEED_KEY_DERIVATION = 0xD017CBBA7B5D3581L;

    /**
     * 客户端硬编码公钥，用于握手第一步加密。
     */
    public static final byte[] publicKey = {
            0x0F, (byte) 0xC7, 0x7E, (byte) 0xF3, (byte) 0xF4, (byte) 0xB8, 0x35, 0x3A,
            (byte) 0xA2, (byte) 0xBA, 0x7F, (byte) 0xD3, 0x17, 0x79, 0x46, (byte) 0x8E,
            0x65, 0x42, (byte) 0xD0, (byte) 0x98, (byte) 0x8A, (byte) 0x9B, (byte) 0xB0, 0x19,
            (byte) 0x80, (byte) 0x4F, (byte) 0x81, 0x56, (byte) 0x36, 0x6A, 0x12, (byte) 0x62,
            (byte) 0xBE, 0x0E, (byte) 0xE5, (byte) 0xAD, 0x47, (byte) 0x01, (byte) 0xD4, 0x5E,
            (byte) 0xE4, (byte) 0xEB, (byte) 0xFB, 0x36, (byte) 0xCB, 0x47, 0x42, (byte) 0x98,
            (byte) 0xF9, (byte) 0xE5, 0x7A, 0x5C, 0x3C, (byte) 0xDB, 0x2C, 0x76
    };

    public record EncryptedPacket(byte[] data, byte[] nextKey) {}

    /**
     * 加密数据包。
     *
     * @param payload       明文数据
     * @param currentKeyIv  当前密钥（56字节：32 key + 24 iv）
     * @param appendNextKey 是否在末尾附加下一个密钥（握手第一步为 true）
     * @return 加密后的数据包 + 下一个密钥
     */
    public static EncryptedPacket encrypt(byte[] payload, byte[] currentKeyIv, boolean appendNextKey) throws Exception {
        byte[] fullPlaintext;
        byte[] nextKeyIv = null;

        if (appendNextKey) {
            nextKeyIv = new byte[56];
            random.nextBytes(nextKeyIv);
            fullPlaintext = new byte[payload.length + 56];
            System.arraycopy(payload, 0, fullPlaintext, 0, payload.length);
            System.arraycopy(nextKeyIv, 0, fullPlaintext, payload.length, 56);
        } else {
            fullPlaintext = payload;
        }

        byte[] key = Arrays.copyOfRange(currentKeyIv, 0, 32);
        byte[] iv = Arrays.copyOfRange(currentKeyIv, 32, 56);
        byte[] step1Encrypted = new XChaCha20(key, iv, 30).processBytes(fullPlaintext, 0, fullPlaintext.length);
        byte[] step2Xorred = mt19937Xor(step1Encrypted, currentKeyIv, SEED_KEY_DERIVATION);

        long hash = CITY_HASH.hash64WithSeed(step2Xorred, SEED_PACKET_VERIFICATION);

        ByteBuffer finalPacket = ByteBuffer.allocate(step2Xorred.length + 8).order(ByteOrder.LITTLE_ENDIAN);
        finalPacket.put(step2Xorred);
        finalPacket.putLong(hash);

        return new EncryptedPacket(finalPacket.array(), nextKeyIv);
    }

    /**
     * 解密数据包。
     *
     * @param packet 加密数据包（末尾8字节为 CityHash64 签名）
     * @param key    解密密钥（56字节）
     * @return 解密后的明文
     */
    public static byte[] decrypt(byte[] packet, byte[] key) throws Exception {
        if (packet.length <= 11) throw new RuntimeException("Packet too short!");

        int payloadLen = packet.length - 8;
        byte[] payload = Arrays.copyOfRange(packet, 0, payloadLen);
        long packetHash = ByteBuffer.wrap(packet, payloadLen, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();

        long calculatedHash = CITY_HASH.hash64WithSeed(payload, SEED_PACKET_VERIFICATION);
        if (calculatedHash != packetHash) {
            throw new RuntimeException("Integrity check failed");
        }

        byte[] xoredData = mt19937Xor(payload, key, SEED_KEY_DERIVATION);

        byte[] chachaKey = Arrays.copyOfRange(key, 0, 32);
        byte[] chachaIv = Arrays.copyOfRange(key, 32, 56);
        XChaCha20 chacha = new XChaCha20(chachaKey, chachaIv, 30);

        return chacha.processBytes(xoredData, 0, xoredData.length);
    }

    /**
     * MT19937 XOR 混淆。
     */
    public static byte[] mt19937Xor(byte[] data, byte[] currentKeyIv, long seedDerivation) {
        long mtSeed = CITY_HASH.hash64WithSeed(currentKeyIv, seedDerivation);
        MT19937 mt = new MT19937(mtSeed);
        byte[] result = new byte[data.length];

        int i = 0;
        while (i < data.length) {
            long rnd = mt.extract_number();
            for (int j = 0; j < 8 && i < data.length; ++j) {
                byte keystreamByte = (byte) ((rnd >>> (j * 8)) & 0xFF);
                result[i] = (byte) (data[i] ^ keystreamByte);
                i++;
            }
        }
        return result;
    }
}
