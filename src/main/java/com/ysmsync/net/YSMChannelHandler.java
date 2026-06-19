package com.ysmsync.net;

import com.ysmsync.YSMPlugin;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Netty 拦截器：在服务端 PacketDecoder 之前捕获 YSM 自定义频道数据包。
 *
 * Pipeline 顺序：FrameDecoder -> [YSMChannelHandler] -> PacketDecoder -> ...
 *
 * 原始 ByteBuf 在 FrameDecoder 之后、PacketDecoder 之前到达本 handler。
 * 本 handler 读取 VarInt packetId，如果是 YSM 频道包则直接处理，
 * 不传递给后方的 PacketDecoder，从而避免 "unknown packet id" 异常。
 */
public class YSMChannelHandler extends ChannelInboundHandlerAdapter {

    private static final String HANDLER_NAME = "ysmsync_handler";

    /** 已知的 serverbound CustomPayload packet ID（不同 MC 版本不同） */
    private static final Set<Integer> CUSTOM_PAYLOAD_IDS = new HashSet<>();
    private static int resolvedCustomPayloadId = -1;

    private static Method getHandleMethod;
    private static Method getConnectionMethod;
    private static Field getConnectionField;
    private static Field innerConnectionField; // ServerCommonPacketListenerImpl → Connection
    private static Field channelField;         // Connection → Channel
    private static boolean reflectionsInitialized = false;

    private static final Map<String, Channel> channelCache = new ConcurrentHashMap<>();

    private final YSMPlugin plugin;

    public YSMChannelHandler(YSMPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 注入到玩家 Pipeline，放在 decoder (PacketDecoder) 之前。
     */
    public static void inject(Player player, YSMPlugin plugin) {
        try {
            Channel channel = getNettyChannel(player);
            if (channel == null) return;

            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                pipeline.remove(HANDLER_NAME);
            }

            // 放在 decoder 之前，这样我们能在 PacketDecoder 之前拦截原始 ByteBuf
            pipeline.addBefore("decoder", HANDLER_NAME, new YSMChannelHandler(plugin));
            channelCache.put(player.getUniqueId().toString(), channel);

            plugin.logDebug("Injected YSM handler for " + player.getName());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to inject YSM handler for " + player.getName(), e);
        }
    }

    public static void remove(Player player, YSMPlugin plugin) {
        try {
            Channel channel = channelCache.remove(player.getUniqueId().toString());
            if (channel != null && channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf buf)) {
            super.channelRead(ctx, msg);
            return;
        }

        if (buf.readableBytes() < 1) {
            super.channelRead(ctx, msg);
            return;
        }

        int readerIndex = buf.readerIndex();

        try {
            int packetId = readVarInt(buf);

            // 检查是否是 CustomPayload serverbound packet（不同 MC 版本 ID 不同）
            if (packetId == resolvedCustomPayloadId || CUSTOM_PAYLOAD_IDS.contains(packetId)) {
                if (handleCustomPayload(ctx, buf)) {
                    buf.release(); // 已处理，释放 ByteBuf
                    return;
                }
                // 不是 YSM 频道，不 return，让 finally 恢复 reader index 后交给 PacketDecoder
            }

        } catch (Exception e) {
            // 解析失败，回退让 decoder 处理
        } finally {
            // 仅在 buffer 未被释放时恢复 reader index
            if (buf.refCnt() > 0) {
                buf.readerIndex(readerIndex);
            }
        }

        // 不是 YSM 包，传递给后方的 PacketDecoder
        super.channelRead(ctx, msg);
    }

    private boolean handleCustomPayload(ChannelHandlerContext ctx, ByteBuf buf) {
        int mark = buf.readerIndex();
        try {
            String namespace = readString(buf);
            String key = readString(buf);
            String channel = namespace + ":" + key;

            if (channel.startsWith("yes_steve_model:")) {
                // 提取 payload（channel 之后的所有字节）
                int payloadStart = buf.readerIndex();
                int payloadLen = buf.readableBytes();
                byte[] payload = new byte[payloadLen];
                buf.getBytes(payloadStart, payload);

                dispatchToPlugin(ctx, payload);
                return true;
            }

            return false;
        } catch (Exception e) {
            buf.readerIndex(mark);
            return false;
        }
    }

    private void dispatchToPlugin(ChannelHandlerContext ctx, byte[] payload) {
        Player player = findPlayerByChannel(ctx.channel());
        if (player == null) {
            plugin.logDebug("YSM packet received but no player found for channel");
            return;
        }

        byte[] copy = payload.clone();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            plugin.getPacketHandler().handleIncoming(player, copy);
        });
    }

    private Player findPlayerByChannel(Channel channel) {
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                Channel playerChannel = getNettyChannel(player);
                if (playerChannel == channel) {
                    return player;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String readString(ByteBuf buf) {
        int len = readVarInt(buf);
        if (len < 0 || len > buf.readableBytes()) {
            throw new IllegalArgumentException(
                    "String length " + len + " exceeds readable bytes " + buf.readableBytes());
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int readVarInt(ByteBuf buf) {
        int value = 0;
        int shift = 0;
        byte current;
        do {
            current = buf.readByte();
            value |= (current & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new RuntimeException("VarInt too big");
        } while ((current & 0x80) != 0);
        return value;
    }

    private static int varIntSize(int value) {
        int size = 0;
        do {
            value >>>= 7;
            size++;
        } while (value != 0);
        return size;
    }

    // === NMS 反射 ===

    private static Channel getNettyChannel(Player player) throws Exception {
        initReflections();
        Object serverPlayer = getHandleMethod.invoke(player);

        // ServerPlayer → ServerCommonPacketListenerImpl
        Object packetListener;
        if (getConnectionMethod != null) {
            packetListener = getConnectionMethod.invoke(serverPlayer);
        } else {
            packetListener = getConnectionField.get(serverPlayer);
        }

        // ServerCommonPacketListenerImpl → Connection
        Object connection = innerConnectionField.get(packetListener);

        // Connection → Channel
        return (Channel) channelField.get(connection);
    }

    private static synchronized void initReflections() throws Exception {
        if (reflectionsInitialized) return;

        Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
        getHandleMethod = craftPlayerClass.getMethod("getHandle");

        Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
        Class<?> serverCommonPacketListenerImpl = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl");
        Class<?> connectionClass = Class.forName("net.minecraft.network.Connection");

        // Try method first (older Paper), then field (Paper 26.1.2+)
        try {
            getConnectionMethod = serverPlayerClass.getMethod("connection");
        } catch (NoSuchMethodException e) {
            getConnectionMethod = null;
            getConnectionField = serverPlayerClass.getDeclaredField("connection");
            getConnectionField.setAccessible(true);
        }

        // ServerCommonPacketListenerImpl.connection → Connection
        innerConnectionField = serverCommonPacketListenerImpl.getDeclaredField("connection");
        innerConnectionField.setAccessible(true);

        // Connection.channel → Netty Channel
        channelField = connectionClass.getDeclaredField("channel");
        channelField.setAccessible(true);

        // 通过 NMS 反射获取 CustomPayload serverbound packet ID
        initCustomPayloadPacketId();

        reflectionsInitialized = true;
    }

    /**
     * 通过 NMS 反射获取 ServerboundCustomPayloadPacket 的 packet ID。
     * 不同 MC 版本的 ID 不同（1.20-1.20.1: 0x18, 1.20.2+: 0x17）。
     */
    private static void initCustomPayloadPacketId() {
        // 已知版本的默认值
        CUSTOM_PAYLOAD_IDS.add(0x17); // MC 1.20.2+
        CUSTOM_PAYLOAD_IDS.add(0x18); // MC 1.20-1.20.1

        try {
            Class<?> payloadPacketClass = Class.forName("net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket");
            // 尝试通过 PacketType.TYPE 字段获取 packet ID
            try {
                Field typeField = payloadPacketClass.getDeclaredField("TYPE");
                typeField.setAccessible(true);
                Object packetType = typeField.get(null);
                // PacketType.id() 返回 packet ID
                Method idMethod = packetType.getClass().getMethod("id");
                resolvedCustomPayloadId = (int) idMethod.invoke(packetType);
            } catch (Exception e) {
                // 回退：尝试通过 static int 字段获取
                try {
                    for (Field f : payloadPacketClass.getDeclaredFields()) {
                        if (f.getType() == int.class && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                            String name = f.getName().toLowerCase();
                            if (name.contains("id") || name.contains("packet")) {
                                f.setAccessible(true);
                                int id = f.getInt(null);
                                if (id > 0 && id < 256) {
                                    resolvedCustomPayloadId = id;
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }
}
