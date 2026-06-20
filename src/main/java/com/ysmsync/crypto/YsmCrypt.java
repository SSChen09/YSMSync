package com.ysmsync.crypto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;

/**
 * YSM 加密工具类。
 * 提供握手包的加解密功能、模型缓存加密/验证功能（XChaCha20 + MT19937 XOR + CityHash64）。
 * 移植自 OpenYSM YsmCrypt。
 */
public class YsmCrypt {
    private static final CityHash CITY_HASH = new CityHash();
    private static final SecureRandom random = new SecureRandom();
    public static final long SEED_PACKET_VERIFICATION = 0xEE6FA63D570BD77BL;
    public static final long SEED_KEY_DERIVATION = 0xD017CBBA7B5D3581L;
    public static final long SEED_CACHE_DECRYPTION = 0xD1C3D1D13A99752BL;
    public static final long SEED_CACHE_VERIFICATION = 0xF346451E53A22261L;
    public static final long SEED_FILE_VERIFICATION = 0x9E5599DB80C67C29L;
    public static final long SEED_RES_VERIFICATION = 0xA62B1A2C43842BC3L;

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

    // === 模型缓存相关方法（移植自 OpenYSM） ===

    /**
     * 根据模型 SHA256 和服务端密钥计算缓存哈希（hash1, hash2）。
     *
     * @param modelSha256 模型文件的 SHA256 十六进制字符串
     * @param serverKey   服务端密钥（56字节）
     * @return long[2]: [hash1, hash2]
     */
    public static long[] calculateModelHashes(String modelSha256, byte[] serverKey) {
        byte[] data = modelSha256.getBytes(StandardCharsets.UTF_8);
        byte[] xored = mt19937Xor(data, serverKey, SEED_KEY_DERIVATION);
        long hash1 = CITY_HASH.hash64WithSeed(xored, SEED_CACHE_VERIFICATION);
        long hash2 = CITY_HASH.hash64WithSeed(xored, SEED_CACHE_DECRYPTION);
        return new long[]{hash1, hash2};
    }

    /**
     * 加密模型数据为服务端缓存格式（与 Fox Model Loader / OpenYSM 兼容）。
     * 格式：[9个VarInt头] [ChaCha加密数据] [8字节CityHash签名]
     *
     * @param clearText 模型明文数据
     * @param serverKey 服务端密钥（56字节）
     * @param hash1     缓存验证哈希
     * @param hash2     缓存解密哈希
     * @return 加密后的缓存文件数据
     */
    public static byte[] encryptServerCache(byte[] clearText, byte[] serverKey, long hash1, long hash2) throws Exception {
        byte[] zstdData = YsmZstd.compress(clearText);

        // 随机填充
        int paddingLength = 16 + random.nextInt(112);
        int randomTop6Bits = random.nextInt(64) << 10;
        int headerWord = (paddingLength & 0x3FF) | randomTop6Bits;
        byte[] payloadToEncrypt = new byte[2 + paddingLength + zstdData.length];
        payloadToEncrypt[0] = (byte) (headerWord & 0xFF);
        payloadToEncrypt[1] = (byte) ((headerWord >> 8) & 0xFF);
        byte[] padding = new byte[paddingLength];
        random.nextBytes(padding);
        System.arraycopy(padding, 0, payloadToEncrypt, 2, paddingLength);
        System.arraycopy(zstdData, 0, payloadToEncrypt, 2 + paddingLength, zstdData.length);

        // ChaCha 加密
        byte[] chachaKey = Arrays.copyOfRange(serverKey, 0, 32);
        byte[] chachaIv = Arrays.copyOfRange(serverKey, 32, 56);
        byte[] xored = mt19937Xor(payloadToEncrypt, serverKey, SEED_KEY_DERIVATION);
        byte[] encryptedPayload = modifiedChaChaEncrypt(xored, chachaKey, chachaIv, SEED_CACHE_DECRYPTION);

        // 写入 9 个 VarInt 头
        ByteBuffer headerBuf = ByteBuffer.allocate(64);
        writeVarIntToBuffer(headerBuf, 1);
        writeVarIntToBuffer(headerBuf, 0);
        writeVarIntToBuffer(headerBuf, 0);
        writeVarIntToBuffer(headerBuf, 0);
        writeVarIntToBuffer(headerBuf, 32); // format version
        writeVarIntToBuffer(headerBuf, 0);
        writeVarIntToBuffer(headerBuf, 0);
        writeVarIntToBuffer(headerBuf, 0);
        writeVarIntToBuffer(headerBuf, 0);
        headerBuf.flip();
        byte[] headers = new byte[headerBuf.remaining()];
        headerBuf.get(headers);

        // 组装最终数据
        int finalPayloadLen = headers.length + encryptedPayload.length;
        ByteBuffer finalBuf = ByteBuffer.allocate(finalPayloadLen + 8).order(ByteOrder.LITTLE_ENDIAN);
        finalBuf.put(headers);
        finalBuf.put(encryptedPayload);

        // 计算签名
        byte[] dataToHash = Arrays.copyOfRange(finalBuf.array(), 0, finalPayloadLen);
        long calculatedHash = CITY_HASH.hash64WithSeed(dataToHash, SEED_CACHE_VERIFICATION);
        long realHash = calculatedHash ^ hash1 ^ hash2;

        finalBuf.putLong(realHash);
        return finalBuf.array();
    }

    /**
     * 验证服务端缓存文件的签名。
     */
    public static boolean verifyServerCache(byte[] cacheData, long hash1, long hash2) {
        if (cacheData.length < 8) return false;
        int payloadEnd = cacheData.length - 8;
        byte[] payload = Arrays.copyOfRange(cacheData, 0, payloadEnd);
        long fileSignature = ByteBuffer.wrap(cacheData, payloadEnd, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        long calculatedHash = CITY_HASH.hash64WithSeed(payload, SEED_CACHE_VERIFICATION);
        long expectedSignature = calculatedHash ^ hash1 ^ hash2;
        return fileSignature == expectedSignature;
    }

    /**
     * 修改版 ChaCha 加密（YSM 缓存专用），根据 seed 动态调整轮数和块大小。
     */
    private static byte[] modifiedChaChaEncrypt(byte[] plainText, byte[] key, byte[] iv, long seed) throws Exception {
        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);

        long hash2 = CITY_HASH.hash64WithSeed(keyIv, seed);
        int nextRoundSize = (int) (((hash2 & 0x3FL) | 0x40L) << 6);
        int rounds = (int) (10 * Long.remainderUnsigned(hash2, 3) + 10);

        XChaCha20 ctx = new XChaCha20(key, iv, rounds);
        byte[] result = new byte[plainText.length];
        int blockPointer = 0;

        while (blockPointer < plainText.length) {
            if (blockPointer + nextRoundSize > plainText.length) {
                nextRoundSize = plainText.length - blockPointer;
            }
            byte[] plainChunk = Arrays.copyOfRange(plainText, blockPointer, blockPointer + nextRoundSize);
            byte[] encChunk = ctx.processBytes(plainChunk, 0, nextRoundSize);
            System.arraycopy(encChunk, 0, result, blockPointer, nextRoundSize);
            blockPointer += nextRoundSize;

            if (blockPointer < plainText.length) {
                long resHash = CITY_HASH.hash64WithSeed(plainChunk, seed);
                nextRoundSize = ctx.updateStateYSM(resHash);
            }
        }
        return result;
    }

    /**
     * 小端序 VarInt 写入辅助方法。
     */
    private static void writeVarIntToBuffer(ByteBuffer buf, int value) {
        while ((value & -128) != 0) {
            buf.put((byte) (value & 127 | 128));
            value >>>= 7;
        }
        buf.put((byte) value);
    }

    /**
     * MT19937 XOR 原地操作（不创建新数组）。
     */
    private static void mt19937XorInPlace(byte[] data, byte[] currentKeyIv, long seedDerivation) {
        long mtSeed = CITY_HASH.hash64WithSeed(currentKeyIv, seedDerivation);
        MT19937 mt = new MT19937(mtSeed);
        int i = 0;
        while (i < data.length) {
            long rnd = mt.extract_number();
            for (int j = 0; j < 8 && i < data.length; ++j) {
                byte keystreamByte = (byte) ((rnd >>> (j * 8)) & 0xFF);
                data[i] = (byte) (data[i] ^ keystreamByte);
                i++;
            }
        }
    }

    /**
     * 修改版 ChaCha 解密（YSM 文件专用），根据 seed 动态调整轮数和块大小。
     * 与 modifiedChaChaEncrypt 互为逆操作。
     */
    private static byte[] modifiedChaChaDecrypt(byte[] data, int dataOff, int dataLen,
            byte[] key, byte[] iv, long seed) throws Exception {
        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);

        long hash2 = CITY_HASH.hash64WithSeed(keyIv, seed);
        int nextRoundSize = (int) (((hash2 & 0x3FL) | 0x40L) << 6);
        int rounds = (int) (10 * Long.remainderUnsigned(hash2, 3) + 10);

        XChaCha20 ctx = new XChaCha20(key, iv, rounds);
        byte[] result = new byte[dataLen];
        int blockPointer = 0;

        while (blockPointer < dataLen) {
            if (blockPointer + nextRoundSize > dataLen) {
                nextRoundSize = dataLen - blockPointer;
            }
            byte[] chunk = Arrays.copyOfRange(data, dataOff + blockPointer, dataOff + blockPointer + nextRoundSize);
            byte[] decChunk = ctx.processBytes(chunk, 0, nextRoundSize);
            System.arraycopy(decChunk, 0, result, blockPointer, nextRoundSize);
            blockPointer += nextRoundSize;

            if (blockPointer < dataLen) {
                long resHash = CITY_HASH.hash64WithSeed(result, blockPointer - nextRoundSize, nextRoundSize, seed);
                nextRoundSize = ctx.updateStateYSM(resHash);
            }
        }
        return result;
    }

    /**
     * 解密 .ysm 文件，返回解密并解压后的模型明文数据。
     * 移植自 Fox Model Loader YsmCrypt.decryptYsmFile。
     *
     * @param fileData 原始 .ysm 文件字节
     * @return 解密解压后的模型明文（可直接传入 encryptServerCache）
     */
    public static byte[] decryptYsmFile(byte[] fileData) throws Exception {
        if (fileData.length < 8 + 24 + 32 + 8) {
            throw new RuntimeException("Invalid YSM file: File too short.");
        }

        // 找到文本头终止符 0x00
        int headerLength = 0;
        while (headerLength < fileData.length && fileData[headerLength] != 0x00) {
            headerLength++;
        }

        // 从文件尾部提取 key(32B) + iv(24B) + fileHash(8B)
        int tailOffset = fileData.length - 64;
        byte[] key = Arrays.copyOfRange(fileData, tailOffset, tailOffset + 32);
        byte[] iv = Arrays.copyOfRange(fileData, tailOffset + 32, tailOffset + 56);
        long fileHash = ByteBuffer.wrap(fileData, tailOffset + 56, 8)
                .order(ByteOrder.LITTLE_ENDIAN).getLong();

        // 用 CityHash64 验证签名
        long calculatedHash = CITY_HASH.hash64WithSeed(fileData, 0, fileData.length - 8, SEED_FILE_VERIFICATION);
        if (calculatedHash != fileHash) {
            throw new RuntimeException("Corrupted YSM file: File hash mismatch.");
        }

        // 读取 crypto 版本号 (4 字节 LE int32)
        int ptrBinaryData = headerLength + 1;
        int crypto = ByteBuffer.wrap(fileData, ptrBinaryData, 4)
                .order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (crypto != 3) {
            throw new RuntimeException("Invalid YSM file: Crypto version is not 3.");
        }
        ptrBinaryData += 4;

        // modifiedChaChaDecrypt 解密
        byte[] chachaDecrypted = modifiedChaChaDecrypt(fileData, ptrBinaryData,
                tailOffset - ptrBinaryData, key, iv, SEED_RES_VERIFICATION);

        // MT19937 XOR 洗白
        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);
        mt19937XorInPlace(chachaDecrypted, keyIv, SEED_KEY_DERIVATION);

        // 读取 padding 长度并跳过，然后 zstd 解压
        int n = ((chachaDecrypted[0] & 0xFF) | ((chachaDecrypted[1] & 0xFF) << 8)) & 0x3FF;
        int zstdOffset = 2 + n;
        return YsmZstd.decompress(chachaDecrypted, zstdOffset, chachaDecrypted.length - zstdOffset);
    }
}
