package com.ysmsync.crypto;

import com.github.luben.zstd.Zstd;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * YSM 魔改 Zstd 压缩/解压工具。
 * YSM 协议对标准 Zstd 的 Block Header 进行了混淆（块类型映射 + 大小异或）。
 * 移植自 OpenYSM YsmZstd。
 */
public class YsmZstd {

    private static final int STD_BT_RAW = 0;
    private static final int STD_BT_RLE = 1;
    private static final int STD_BT_COMPRESSED = 2;

    /**
     * 标准 Zstd 压缩 + YSM Block Header 混淆。
     */
    public static byte[] compress(byte[] data) {
        byte[] zstdData = Zstd.compress(data, 3);
        return obfuscate(zstdData);
    }

    /**
     * YSM Block Header 洗白 + 标准 Zstd 解压。
     */
    public static byte[] decompress(byte[] data) {
        return decompress(data, 0, data.length);
    }

    /**
     * YSM Block Header 洗白 + 标准 Zstd 解压（支持偏移和长度）。
     */
    public static byte[] decompress(byte[] data, int offset, int length) {
        byte[] sub = (offset == 0 && length == data.length) ? data
                : java.util.Arrays.copyOfRange(data, offset, offset + length);
        byte[] washed = wash(sub);
        long decompressedSize = Zstd.decompressedSize(washed);
        if (decompressedSize >= 0) {
            byte[] output = new byte[(int) decompressedSize];
            Zstd.decompressByteArray(output, 0, output.length, washed, 0, washed.length);
            return output;
        }
        return Zstd.decompress(washed, (int) Zstd.getFrameContentSize(washed));
    }

    /**
     * 将标准 Zstd 数据"弄脏"为 YSM 魔改格式（块类型映射 + 大小异或 0xD4E9）。
     */
    private static byte[] obfuscate(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buffer.getInt(0);
        if (magic != 0xFD2FB528) {
            throw new IllegalArgumentException("Not a standard ZSTD frame.");
        }

        byte fhd = data[4];
        int frameHeaderSize = calculateFrameHeaderSize(fhd);
        int offset = 4 + frameHeaderSize;

        while (offset + 3 <= data.length) {
            int b0 = data[offset] & 0xFF;
            int b1 = data[offset + 1] & 0xFF;
            int b2 = data[offset + 2] & 0xFF;
            int cBlockHeader = b0 | (b1 << 8) | (b2 << 16);

            int lastBlock = cBlockHeader & 1;
            int blockTypeStd = (cBlockHeader >> 1) & 3;
            int cSize = cBlockHeader >> 3;

            int blockDataSize = (blockTypeStd == STD_BT_RLE) ? 1 : cSize;

            // 标准块类型 → YSM 块类型
            int blockTypeYSM = switch (blockTypeStd) {
                case STD_BT_RAW -> 3;
                case STD_BT_RLE -> 1;
                case STD_BT_COMPRESSED -> 0;
                default -> 2;
            };

            int rawSize = cSize ^ 0xD4E9;
            int ysmB0 = (lastBlock << 7) | (blockTypeYSM << 5) | ((rawSize >> 16) & 0x1F);
            int ysmB1 = rawSize & 0xFF;
            int ysmB2 = (rawSize >> 8) & 0xFF;

            data[offset] = (byte) ysmB0;
            data[offset + 1] = (byte) ysmB1;
            data[offset + 2] = (byte) ysmB2;

            offset += 3 + blockDataSize;
            if (lastBlock == 1) break;
        }
        return data;
    }

    /**
     * 将 YSM 魔改 Zstd 数据"洗白"为标准 Zstd 格式。
     */
    private static byte[] wash(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buffer.getInt(0);
        if (magic != 0xFD2FB528) {
            throw new IllegalArgumentException("Not a standard ZSTD Magic Number.");
        }

        byte fhd = data[4];
        // 擦除 Content Checksum 标志位
        data[4] = (byte) (fhd & 0xFB);

        int frameHeaderSize = calculateFrameHeaderSize(fhd);
        int offset = 4 + frameHeaderSize;

        while (offset + 3 <= data.length) {
            int b0 = data[offset] & 0xFF;
            int b1 = data[offset + 1] & 0xFF;
            int b2 = data[offset + 2] & 0xFF;

            int lastBlock = (b0 >> 7) & 1;
            int blockTypeYSM = (b0 >> 5) & 3;
            int rawSize = ((b0 & 0x1F) << 16) | b1 | (b2 << 8);
            int cSize = rawSize ^ 0xD4E9;

            // YSM 块类型 → 标准块类型
            int blockTypeStd = switch (blockTypeYSM) {
                case 0 -> STD_BT_COMPRESSED;
                case 1 -> STD_BT_RLE;
                case 2 -> 3; // RESERVED
                case 3 -> STD_BT_RAW;
                default -> throw new IllegalStateException("Unknown block type");
            };

            int stdHeader = lastBlock | (blockTypeStd << 1) | (cSize << 3);
            data[offset] = (byte) (stdHeader & 0xFF);
            data[offset + 1] = (byte) ((stdHeader >> 8) & 0xFF);
            data[offset + 2] = (byte) ((stdHeader >> 16) & 0xFF);

            int blockDataSize = (blockTypeStd == STD_BT_RLE) ? 1 : cSize;
            offset += 3 + blockDataSize;
            if (lastBlock == 1) break;
        }
        return data;
    }

    private static int calculateFrameHeaderSize(byte fhd) {
        int size = 1;
        boolean singleSegment = ((fhd >> 5) & 1) == 1;

        int dictIdSize = 0;
        int dictIdBits = fhd & 3;
        if (dictIdBits == 1) dictIdSize = 1;
        else if (dictIdBits == 2) dictIdSize = 2;
        else if (dictIdBits == 3) dictIdSize = 4;

        int fcsSize = 0;
        int fcsBits = (fhd >> 6) & 3;
        if (fcsBits == 0) fcsSize = singleSegment ? 1 : 0;
        else if (fcsBits == 1) fcsSize = 2;
        else if (fcsBits == 2) fcsSize = 4;
        else if (fcsBits == 3) fcsSize = 8;

        int windowDescSize = singleSegment ? 0 : 1;
        return size + windowDescSize + dictIdSize + fcsSize;
    }
}
