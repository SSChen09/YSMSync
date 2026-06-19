# YSMSync 开发日志

## 项目概述

在 Spigot/Paper 服务器上移植 Yes Steve Model 的模型同步功能，支持 YSM 2.6.0 协议。

灵感来源：
- [OpenYSM / YesSteveModel](https://github.com/OpenYSM/YesSteveModel) — YSM 协议定义
- [Fox Model Loader](https://github.com/AntonyBlayze/Fox-Model-Loader-main) — 协议细节参考
- [Freesia II](https://github.com/FreesiaTeam/Freesia) — 跨服架构与 Netty 拦截思路

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
- 服务端缓存 `.ysm` 模型文件（`plugins/YSMSync/models/{UUID}.ysm`）
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

---

## 项目结构

```
YSMSync/
├── src/main/java/com/ysmsync/
│   ├── YSMPlugin.java              # 主插件入口
│   ├── model/
│   │   ├── ModelFileManager.java   # 模型文件存储管理
│   │   └── ModelUploadManager.java # 模型上传管理
│   ├── net/
│   │   └── YSMChannelHandler.java  # Netty Pipeline 拦截器
│   ├── packet/
│   │   └── YSMPacketHandler.java   # 数据包处理分发
│   ├── state/
│   │   ├── PlayerYSMState.java     # 单玩家 YSM 状态
│   │   └── YSMStateManager.java    # 全局状态管理
│   ├── storage/
│   │   └── YSMStorage.java         # SQLite 存储层
│   └── util/
│       └── VarIntUtil.java         # VarInt/VarLong 编解码
├── src/main/resources/
│   ├── plugin.yml                  # 插件元数据
│   └── config.yml                  # 默认配置
├── .github/workflows/build.yml     # CI/CD 自动构建发布
└── build.gradle.kts                # Gradle 构建脚本
```

---

## 技术要点

### YSM 2.6.0 协议

自定义频道：`yes_steve_model:2_6_0`，共 21 种网络消息类型，使用 VarInt/VarLong 编码。

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
| CI | `./gradlew: Permission denied` | 添加 `chmod +x gradlew` |
