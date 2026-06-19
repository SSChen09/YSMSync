# YSMSync 开发日志

## 项目概述

在 Spigot/Paper 服务器上移植 Yes Steve Model 的模型同步功能，支持 YSM 2.6.0 协议。

灵感来源：
- [OpenYSM](https://github.com/OpenYSM/OpenYSM) — YSM 协议定义
- [Fox Model Loader](https://github.com/sdf123098/Fox-Model-Loader) — 协议细节参考
- [Freesia II](https://github.com/NguyenDevs/FreesiaII) — 跨服架构与 Netty 拦截思路

---

## 版本演进

### v1.0.0 — 基础模型同步

**功能：**
- YSM 2.6.0 协议握手（VersionCheck 51/52）
- 玩家模型切换广播（SetModelAndTexture 1/4）
- 动画转发（PlayAnimation 7 → ExecuteMolang 3）
- 表情同步（SyncAnimationExpression 18/19）
- SQLite 持久化玩家模型选择
- 授权模型/收藏模型同步（SyncAuthModels 6, SyncStarModels 8）

**技术方案：**
- Paper API：`AsyncPacketSendEvent` + `sendPluginMessage`
- NMS 反射：`CraftPlayer.getHandle()` → `ServerPlayer.connection` → `channel`
- 存储：SQLite（`plugins/YSMSync/ysm_data.db`）

### v1.1.0 — 模型文件服务端存储

**新增功能：**
- 服务端缓存 `.ysm` 模型文件（`plugins/YSMSync/models/{UUID}`）
- 新玩家加入时自动推送已存储的模型文件
- 内存缓存 + 磁盘持久化

**关键代码：** `ModelFileManager.java`
- `storeModelData`：C2S→S2C 格式转换（packetId 2→1）+ 异步写磁盘
- `getModelData`：内存优先，miss 则从磁盘加载

### v1.2.0 — 模型上传功能

**新增功能：**
- 四步上传协议：Start(70) → Chunk(72) → Finish(73) → Result(74)
- SHA256 校验
- 可配置上传限制（文件大小、分块大小）

**关键代码：** `ModelUploadManager.java`

### v1.3.0 — Netty Pipeline 拦截器

**问题：** `DecoderException: Received unknown packet id 72`

Paper 的 `PacketDecoder` 不认识 YSM 协议内部 packet ID，导致解码失败。

**解决方案：** 在 `PacketDecoder` 之前插入自定义 Netty handler，直接拦截原始 ByteBuf。

```
Pipeline 顺序：FrameDecoder → [YSMChannelHandler] → PacketDecoder → ...
```

**关键变更：** `YSMChannelHandler.java`
- 继承 `ChannelInboundHandlerAdapter`（非 ByteToMessageDecoder）
- 通过 `addBefore("decoder", ...)` 放在 PacketDecoder 之前
- 读取 VarInt packetId，匹配 `yes_steve_model:*` 频道则直接处理，不传递给后方 decoder

### v1.3.1 — 反射兼容修复

**问题：** `NoSuchMethodException: net.minecraft.server.level.ServerPlayer.connection()`

Paper 26.1.2 中 `ServerPlayer.connection` 从方法变为字段。

**解决方案：** 添加 fallback 逻辑——先尝试 `getMethod("connection")`，失败后改用 `getDeclaredField("connection")` + `setAccessible(true)`。

### v1.5.0 — AuthMe 兼容修复

**问题：** 与 AuthMe 等登录插件冲突，玩家无法正常登录。

**解决方案：** 移除误拦截原版数据包的路径，确保 Netty 拦截器仅处理 `yes_steve_model:*` 频道。

### v1.5.1 — ByteBuf 安全修复

**修复：**
- `finally` 块不再访问已释放的 Netty ByteBuf，避免 `IllegalReferenceCountException`
- `readString` / `readVarIntArray` 增加长度校验，防止畸形包导致 OOM

### v1.6.0 — 加密握手协议

**新增功能：**
- 完整移植 OpenYSM 加密握手协议（Packet 01-05），修复客户端卡在"正在校验"的问题
- 服务端密钥（ServerKey）自动生成并持久化到 `plugins/YSMSync/server_key.dat`，重启后保持兼容
- GitHub 下载超时自动回退 `gh-proxy.org` 镜像加速

**加密实现：** `YsmCrypt.java`
- XChaCha20（30 轮）+ MT19937 XOR + CityHash64 完整性校验
- Packet 01（HandshakePing）：公钥加密，附带随机 key1
- Packet 02（HandshakePong）：key1 加密，附带 clientNextKey
- Packet 03（ModelDirectory）：clientNextKey 加密，发送服务端密钥 + 客户端密钥 + 模型列表
- Packet 04（RequestModel）：key1 加密，客户端请求缺失模型
- Packet 05（ModelData）：key1 加密，服务端发送模型文件

### v1.6.1 — 握手与同步修复

**修复：**
- `handleRequestModel` 解密密钥错误使用 `key1` → 改为 `clientNextKey`（后续在 v1.6.3 中回退）
- `relayRawPacket` 广播 C2S 格式数据导致其他客户端无法识别 → 改为广播已转换的 S2C 格式
- CustomPayload packet ID 通过 NMS 反射动态获取，兼容 1.20-1.21+ 不同 MC 版本

**安全与健壮性：**
- `ServerKeyManager` / `PlayerYSMState` 的 `byte[]` getter 防御性克隆
- `VarInt` 溢出检查从 64 位收紧至 35 位（Minecraft 标准）
- `CityHash` 从方法内 `new` 提取为 `static final` 单例
- 上传会话 5 分钟超时自动清理

### v1.6.2 — CustomPayload 修复

**问题：** 客户端卡在"正在等待 OpenYSM 服务端握手"或"正在上传到服务器"。

**根因与修复：**
- `handleCustomPayload` 错误读取两个字符串作为频道名（应为单个 ResourceLocation），导致 payload 被截断
- `handleIncoming` 中 syncStep 检查在 VarInt 读取之前，通过 `data[0]` 检查 discriminator 不可靠 → 重构为先读 VarInt packetId 再路由

### v1.6.3 — Packet 04 解密密钥修复

**问题：** 客户端重启后保留上传状态，服务端报 `Integrity check failed` → `IndexOutOfBoundsException`。

**根因：** v1.6.1 将 `handleRequestModel` 的解密密钥从 `key1` 改为 `clientNextKey`，但 Fox Model Loader 客户端确认 **Packet 04 使用 `key1` 加密**（与 Packet 05 相同）。用错误密钥解密产生垃圾数据。

**修复：**
- `handleRequestModel` 解密密钥改回 `state.getKey1()`
- `skipGarbageHeader` 增加边界检查，垃圾头部长度超限时抛出明确异常

**密钥流转（最终版）：**

| 包 | 方向 | 加/解密密钥 |
|---|---|---|
| Packet 01 | S→C | publicKey 加密，附带 key1 |
| Packet 02 | C→S | key1 加密，附带 clientNextKey |
| Packet 03 | S→C | clientNextKey 加密 |
| Packet 04 | C→S | key1 加密 |
| Packet 05 | S→C | key1 加密 |

### v1.6.4 — 握手循环修复

**问题：** 握手完成后客户端卡在"正在上传到服务器"，服务端报 `Received unknown packet id 72`。

**根因：** `handleRequestModel` 在握手完成后发送 `S2CVersionCheckPacket`，客户端收到后重新触发握手，导致握手循环。上传数据包在新握手中到达，syncStep 不匹配被拒绝。

**修复：** 移除握手完成后发送的 VersionCheck / SyncAuthModels / SyncStarModels。

### v1.6.5 — 重复握手修复

**问题：** 客户端 UI 反复闪烁"准备中"。

**根因：** `handleVersionCheck` 无条件调用 `initiateHandshake()`，而 CapabilityEvent（Fox Model Loader 组件）在 tick 200/600/1800 重发 VersionCheck 作为重试机制。每次重发都触发新的握手。

**修复：** `handleVersionCheck` 添加 `syncStep == 0` 前置检查，只在尚未开始握手时才启动。

### v1.6.6 — 模型上传配置修复

**问题：** 客户端上传模型时服务端断线，报 `DecoderException: Failed to decode packet 'serverbound/minecraft:custom_payload'`。

**根因：** `upload.chunk-size` 默认值 1048576（1MB）过大。Fox Model Loader 客户端使用 chunkSize=32000（32KB），但服务端配置为 1MB，导致单个 custom_payload 包过大，在 ViaVersion 的 Netty 管道中解码失败。

**修复：**
- `chunk-size` 默认值从 1048576 改为 32000，与 Fox Model Loader 客户端一致
- `allow-upload` 默认改为 `true`
- `debug` 默认改为 `true`

### v1.6.7 — 模型上传协议与存储修复

**问题 1：** chunk-size 修复后上传仍失败，客户端显示 `Size mismatch: expected 560917 got 544051`。

**根因：** 客户端 UploadChunk 使用 `writeByteArray(data)` 发送数据，会在数据前加 VarInt 长度前缀（2 字节）。服务端 `handleUploadChunk` 用 `buf.remaining()` 读取剩余所有字节，把长度前缀也算入数据。每 32KB chunk 多读 2 字节，18 个 chunk 共多读 36 字节。前 17 个 chunk 正常写入缓冲区，第 18 个触发 overflow 被拒绝，缓冲区缺少最后一个 chunk 的全部数据。

**问题 2：** 上传验证通过但模型文件未出现在 `plugins/YSMSync/models/` 目录。

**根因：** 上传的 `fileData` 是原始 .ysm 文件字节（无 packetId 前缀），但 `storeModelData` 内部的 `convertC2S_to_S2C` 要求数据以 VarInt `2`（C2S packetId）开头，匹配不上返回 `null`，存储被跳过。

**修复：**
- `handleUploadChunk` 改为先 `readVarInt` 读取长度前缀，再读取指定长度的数据
- `ModelFileManager` 新增 `storeRawModelData` 方法，直接将原始 .ysm 数据包装为 S2C 格式（packetId=1 + 数据）后存储
- `handleUploadFinish` 改为调用 `storeRawModelData`

### v1.7.0 — 存储路径重构

**变更：** 模型存储从扁平文件改为按玩家分目录：
- 旧格式：`models/{UUID}.ysm`（每玩家仅一个模型）
- 新格式：`models/{UUID}/{模型名}`（每玩家支持多个模型）

**实现：**
- `ModelFileManager` 内存结构从 `Map<UUID, byte[]>` 改为 `Map<UUID, Map<String, byte[]>>`
- `storeRawModelData` 新增 `modelName` 参数，用作文件名
- `loadAllFromDisk` 支持新旧格式，旧扁平文件自动迁移到子目录
- `sanitizeFileName` 清理文件名中的非法字符（`\ / : * ? " < > |`）

### v2.0.0 — 模型缓存机制

**问题：** 客户端每次重连都需要重新上传完整模型文件。Packet 03 始终发送空模型列表（`writeVarInt(0)`），客户端不知道服务端已缓存其模型，因此每次握手完成后自动发送 C2S ModelSync 重新传输。

**解决方案：** 实现与 Fox Model Loader / OpenYSM 兼容的模型缓存机制，客户端通过 hash1/hash2 对比本地缓存，已缓存的模型跳过下载。

**新增组件：**

`YsmZstd.java` — YSM 魔改 Zstd 压缩/解压：
- 标准 Zstd 块类型映射（RAW→3, RLE→1, COMPRESSED→0）
- 块大小异或 `0xD4E9` 混淆
- 依赖 `com.github.luben:zstd-jni:1.5.7-5`

`YsmCrypt.java` 新增方法：
- `calculateModelHashes(sha256, serverKey)` — SHA256 → MT19937 XOR → CityHash64 生成 hash1/hash2
- `encryptServerCache(clearText, serverKey, hash1, hash2)` — zstd 压缩 + 随机填充 + MT19937 XOR + ChaCha20 加密 + CityHash64 签名
- `verifyServerCache(cacheData, hash1, hash2)` — 验证缓存文件签名完整性
- `modifiedChaChaEncrypt(plainText, key, iv, seed)` — 根据 seed 动态调整轮数和块大小的 ChaCha 加密

**关键变更：**

`ModelFileManager.java`：
- 新增 `ModelCacheEntry` record（modelId, hash1, hash2）
- 新增 `cacheDir`（`plugins/YSMSync/cache/`）存储加密缓存文件
- `storeModelData` / `storeRawModelData` 在存储 S2C 数据的同时创建加密缓存
- `getPlayerModelEntries(UUID)` 返回玩家所有模型的 hash 条目（供 Packet 03 填充）
- `getCacheFileData(hash1, hash2)` 读取加密缓存文件（供 Packet 05 发送）
- `rebuildCacheEntries()` 启动时为已有模型重建缓存条目

`YSMStateManager.java`：
- `sendPacket03` 从空列表改为遍历所有已缓存模型，写入 hash1/hash2/modelId/isAuth/isCustomSkinModel/format 条目
- 新增 `sendPacket05` 分块发送加密缓存文件（chunkSize=30720，与 Fox Model Loader 一致）
- `handleRequestModel` 解析 Packet 04 请求的 hash 列表，触发 `sendPacket05` 发送缺失模型

`build.gradle.kts`：
- 新增 `com.github.johnrengelman.shadow` 插件，将 zstd-jni shade 并 relocate 到 `com.ysmsync.lib.zstd`

**密钥流转（v2.0.0 最终版）：**

| 包 | 方向 | 加/解密密钥 | 用途 |
|---|---|---|---|
| Packet 01 | S→C | publicKey 加密，附带 key1 | 握手发起 |
| Packet 02 | C→S | key1 加密，附带 clientNextKey | 密钥交换 |
| Packet 03 | S→C | clientNextKey 加密 | 发送模型 hash 列表 |
| Packet 04 | C→S | key1 加密 | 请求缺失模型 |
| Packet 05 | S→C | key1 加密 | 分块发送模型缓存文件 |

### v2.0.4 — 修复模型同步只能同步一次

**问题：** 客户端在所有模型缓存命中时直接调用 `onSyncComplete()`，不发送 Packet 04。服务端依赖 `handleRequestModel`（Packet 04）来标记 `handshakeCompleted = true`，导致该标志永远为 `false`，后续 `broadcastYSMPayloadExcept` 过滤掉所有广播。

**修复：** 在 `sendPacket03` 发送模型目录后立即完成握手（设置 `syncStep=3`、`handshakeCompleted=true`），并主动调用 `syncAllModelsToJoiningPlayer` 和 `broadcastModelToAllPlayers`。`handleRequestModel` 简化为仅处理缓存文件请求，不再负责握手完成和广播逻辑。

### v2.0.5 — 修复上传多模型后只保留最后一个

**问题：** `storeModelData` 使用硬编码 `"model"` 作为 Map key。上传流程通过 `storeRawModelData` 将模型 A 存为 `"model_a.ysm"`，但随后客户端发送的 C2S ModelSync 调用 `storeModelData` 会以 `"model"` key 覆盖写入。上传模型 B 后同理，最终 `"model"` key 指向最后一个模型，之前的命名模型数据被孤立。

**修复：** `storeModelData` 新增前置检查：如果玩家 `playerModels` 中已有数据（来自上传流程），直接跳过，避免 C2S ModelSync 覆盖已上传的命名模型。

### v2.0.6 — 修复模型数据未返回客户端

**问题：** `sendPacket03` 发送完毕后立即将 `syncStep` 设为 3，但 `YSMPacketHandler` 只在 `syncStep == 2` 时处理 Packet 04（RequestModel）。客户端在收到 Packet 03 合法发送的 Packet 04 被静默丢弃，导致模型二进制数据永远无法通过 Packet 05 传输到客户端。

**修复：** `YSMPacketHandler.handleIncoming` 中 Packet 04 的处理条件从 `syncStep == 2` 改为 `syncStep >= 2`，允许在握手已完成状态下仍处理模型数据请求。

### v2.0.7 — 修复模型同步完全失败

**问题：** v2.0.6 将 Packet 04 处理条件改为 `syncStep >= 2`，导致 syncStep=2 时收到的 Packet 02（HandshakePong）被错误路由到 `handleRequestModel`（尝试用 key1 解密 HandshakePong 数据），握手直接失败。

**根因：** Packet 02 和 Packet 04 都走 C2SModelSyncPayload 通道（packetId=2），服务端通过 syncStep 区分。`syncStep >= 2` 无法区分 syncStep=2 时的 HandshakePong 和 syncStep=3 时的 RequestModel。

**修复：** 恢复 `syncStep == 2` 条件。`sendPacket03` 不再立即设置 syncStep=3，改为在发送 Packet 03 后注册 5 秒延迟任务作为兜底：如果客户端未发送 Packet 04（所有模型缓存命中），延迟任务自动完成握手；如果客户端发送了 Packet 04，`handleRequestModel` 正常处理并完成握手（延迟任务检测到已完成后跳过）。

---

## 项目结构

```
YSMSync/
├── src/main/java/com/ysmsync/
│   ├── YSMPlugin.java              # 主插件入口
│   ├── crypto/
│   │   ├── ChaCha20Base.java       # ChaCha20 流密码基类
│   │   ├── CityHash.java           # CityHash64 哈希算法
│   │   ├── MT19937.java            # Mersenne Twister 伪随机数
│   │   ├── ServerKeyManager.java   # 服务端密钥管理
│   │   ├── XChaCha20.java          # XChaCha20 流密码
│   │   ├── YsmCrypt.java           # YSM 加密/解密 + 缓存加密
│   │   └── YsmZstd.java            # YSM 魔改 Zstd 压缩/解压
│   ├── model/
│   │   ├── ModelFileManager.java   # 模型文件存储 + 缓存管理
│   │   └── ModelUploadManager.java # 模型上传管理
│   ├── net/
│   │   ├── YSMByteBuf.java         # YSM 小端序 ByteBuf 包装器
│   │   └── YSMChannelHandler.java  # Netty Pipeline 拦截器
│   ├── packet/
│   │   └── YSMPacketHandler.java   # 数据包处理分发
│   ├── state/
│   │   ├── PlayerYSMState.java     # 单玩家 YSM 状态
│   │   └── YSMStateManager.java    # 全局状态管理
│   ├── storage/
│   │   └── YSMStorage.java         # SQLite 存储层
│   ├── update/
│   │   └── UpdateChecker.java      # 版本检查与自动更新
│   └── util/
│       └── VarIntUtil.java         # VarInt/VarLong 编解码
├── src/main/resources/
│   ├── plugin.yml                  # 插件元数据
│   └── config.yml                  # 默认配置
├── build.gradle.kts                # Gradle 构建脚本（含 shadow 插件）
└── DEVLOG.md                       # 开发日志
```

---

## 技术要点

### YSM 2.6.0 协议

自定义频道：`yes_steve_model:2_6_0`，共 21 种网络消息类型，使用 VarInt/VarLong 编码。

基于 [YSM 2.6.0 协议](https://github.com/OpenYSM/OpenYSM)，支持以下数据包类型：

| ID | 方向  | 名称                        |
| -- | --- | ------------------------- |
| 1  | S→C | SetModelAndTexture        |
| 2  | C→S | ModelSync                 |
| 3  | S→C | ExecuteMolang             |
| 4  | S→C | SetModelAndTexture (sync) |
| 5  | C→S | RequestSwitchModel        |
| 6  | S→C | SyncAuthModels            |
| 7  | C→S | PlayAnimation             |
| 8  | S→C | SyncStarModels            |
| 9  | C→S | StopAnimation             |
| 15 | C→S | CompleteFeedback          |
| 17 | C→S | PlayAnimation             |
| 18 | C→S | SyncAnimationExpression   |
| 19 | S→C | SyncAnimationExpression   |
| 21 | S→C | SyncPlayerState           |
| 23 | C→S | SwingArm                  |
| 51 | S→C | VersionCheck              |
| 52 | C→S | VersionCheckResponse      |
| 70 | C→S | UploadStart               |
| 71 | S→C | UploadStartResponse       |
| 72 | C→S | UploadChunk               |
| 73 | C→S | UploadFinish              |
| 74 | S→C | UploadResult              |

### Netty Pipeline 拦截

Paper 的 `PacketDecoder` 基于 Mojang 映射，只认识原版数据包 ID。YSM 协议的自定义 packet ID 会被拒绝。

解决方案是在 decoder 之前插入拦截器，直接读取 ByteBuf：
1. 读取 VarInt packetId
2. 如果是 `0x17`（CustomPayload）→ 提取 ResourceLocation channel → 匹配 `yes_steve_model:*`
3. 如果是 YSM 内部 packet ID（1-74）→ 直接提取完整数据
4. 其他 → 传递给后方 decoder

### NMS 反射

通过反射访问 NMS 内部类，避免直接依赖 NMS：
```java
CraftPlayer.getHandle()           // → ServerPlayer
ServerPlayer.connection           // → ServerCommonPacketListenerImpl（字段或方法）
ServerCommonPacketListenerImpl.channel  // → Netty Channel
```

### 模型文件格式转换

客户端发送的 ModelSync（ID=2）和服务端广播的 SetModelAndTexture（ID=1）格式不同：
- C2S（ID=2）：包含完整模型数据
- S2C（ID=1）：引用模型文件 + 纹理信息

---

## CI/CD

GitHub Actions workflow（`.github/workflows/build.yml`）：

- **触发条件：** push 到 `main` 分支
- **构建环境：** Ubuntu + JDK 21 (Temurin)
- **自动发布：** 构建成功后自动创建 GitHub Release
- **Release 描述：** 使用最后一次 commit 信息
- **产物：** `YSMSync-{version}.jar`

---

## 遇到的问题与解决

| 版本 | 问题 | 解决方案 |
|------|------|---------|
| v1.0.0 | `event.getPlayer().UniqueId` 编译错误 | 改为 `getUniqueId()` |
| v1.0.0 | `sendPluginMessage(String, byte[])` 签名错误 | Paper API 需要 `sendPluginMessage(Plugin, String, byte[])` |
| v1.0.0 | 缺少 VarIntUtil / PlayerYSMState 导入 | 添加 import |
| v1.0.0 | Shadow 插件版本不存在 | 移除 shadow 插件，简化构建 |
| v1.0.0 | Gradle 下载超时 | 等待重试成功 |
| v1.0.0 | `STATUS Denied` 变量名含空格 | 改为 `STATUS_DENIED` |
| v1.2.0 | `io.netty` 包不存在 | 添加 `compileOnly("io.netty:netty-all:4.1.118.Final")` |
| v1.2.0 | `player.getHandle()` NMS 方法不在 API 中 | 改用反射访问 |
| v1.3.0 | `DecoderException: Received unknown packet id 72` | 在 decoder 之前插入 Netty 拦截器 |
| v1.3.0 | 拦截器放在 decoder 之后无效 | 改为 `addBefore("decoder", ...)` |
| v1.3.0 | 中文变量名 `int标记` | 改为 `int mark` |
| v1.3.1 | `NoSuchMethodException: ServerPlayer.connection()` | Paper 26.1.2 中改为字段访问 |
| v1.5.0 | 与 AuthMe 等登录插件冲突 | 移除误拦截原版数据包的路径 |
| v1.5.1 | `IllegalReferenceCountException` ByteBuf 释放后访问 | finally 块不再访问已释放的 ByteBuf |
| v1.6.0 | 客户端卡在"正在校验" | 移植完整加密握手协议（Packet 01-05） |
| v1.6.1 | 安全与健壮性问题 | 防御性克隆、溢出检查、单例优化 |
| v1.6.2 | 客户端卡在"正在等待握手"/"正在上传" | 修复 CustomPayload 频道名读取 + 数据包路由 |
| v1.6.3 | `Integrity check failed` / `IndexOutOfBoundsException` | Packet 04 解密密钥改回 key1 + 边界检查 |
| v1.6.4 | 握手循环导致 `unknown packet id 72` 断线 | 移除握手后 VersionCheck 发送 |
| v1.6.5 | 客户端 UI 反复闪烁"准备中" | handleVersionCheck 添加 syncStep 前置检查 |
| v1.6.6 | `DecoderException: Failed to decode custom_payload` 上传断线 | chunk-size 从 1MB 改为 32KB，与 Fox Model Loader 客户端一致 |
| v1.6.7 | `Size mismatch: expected X got Y` 上传数据不完整 | handleUploadChunk 读取 writeByteArray 的 VarInt 长度前缀 |
| v1.6.7 | 上传成功但 models/ 目录无文件 | 新增 storeRawModelData，原始 .ysm 数据直接包装为 S2C 格式存储 |
| v1.7.0 | 存储路径不支持多模型 | models/{UUID}.ysm → models/{UUID}/{模型名}，自动迁移旧格式 |
| v2.0.0 | 客户端每次重连重复上传模型 | Packet 03 填充 hash1/hash2，客户端缓存命中跳过下载；引入 zstd-jni 实现 YSM Zstd 压缩 |
| CI | `./gradlew: Permission denied` | 添加 `chmod +x gradlew` |
