# YSMSync

> **Warning** 本项目使用 GitHub Actions 自动构建，作者也无法保证每个构建版本完全没有 bug。如遇问题请到 [Issues](https://github.com/SSChen09/YSMSync/issues) 反馈。

Paper 服务端插件，实现 [Yes Steve Model](https://modrinth.com/mod/yes-steve-model) / [Fox Model Loader](https://github.com/sdf123098/Fox-Model-Loader) 的模型同步功能。

玩家在客户端安装 YSM 模组后，服务器自动同步所有玩家的模型选择，使每个人都能看到彼此的自定义模型。

## 最近更新

### v2.0.0

- **模型缓存机制** — Packet 03 携带服务端已缓存模型的 hash1/hash2，客户端对比本地缓存决定是否跳过下载，避免每次重连重复传输模型文件
- **Packet 05 分块发送** — 客户端缓存未命中时，服务端通过加密缓存文件分块发送模型数据
- **zstd-jni 依赖** — 引入 zstd-jni 实现 YSM 魔改 Zstd 压缩/解压，shadow 插件自动 shade 到 JAR

### v1.7.0

- **存储路径重构** — 模型存储从 `models/{UUID}.ysm` 改为 `models/{UUID}/{模型名}`，支持每个玩家存储多个模型，旧格式自动迁移

### v1.6.7

- **修复模型上传存储** — 上传完成后的原始 .ysm 数据不含 C2S packetId 前缀，`convertC2S_to_S2C` 匹配失败导致文件未写入磁盘，新增 `storeRawModelData` 直接包装为 S2C 格式存储
- **修复模型上传分块解析** — `handleUploadChunk` 未读取 `writeByteArray` 的 VarInt 长度前缀，导致每个 chunk 多读 2 字节，累积后最后一个 chunk 因 overflow 被拒绝，上传数据不完整

## 功能

- **模型同步** — 玩家加入或切换模型时，自动广播给所有在线玩家
- **模型缓存** — 客户端通过 hash1/hash2 对比本地缓存，已缓存的模型跳过下载，避免重复传输
- **模型文件存储** — 服务端缓存 `.ysm` 模型文件，新玩家加入时自动推送
- **模型上传** — 客户端可将模型文件上传至服务端存储，默认启用
- **动画转发** — 转发动画/表情指令给其他玩家
- **Netty 拦截** — 在 Pipeline 层直接捕获 YSM 自定义频道数据包，兼容 Paper 26.1.x
- **SQLite 持久化** — 玩家模型选择存储在 `plugins/YSMSync/ysm_data.db`，重启不丢失

## 要求

- Paper 1.21.4+（仅测试 Paper 26.1.2 带 ViaVersion）
- Java 21+
- 客户端需安装 [Yes Steve Model](https://modrinth.com/mod/yes-steve-model) 或 [Fox Model Loader](https://github.com/sdf123098/Fox-Model-Loader) 模组

## 安装

1. 从 [Releases](https://github.com/SSChen09/YSMSync/releases) 下载 `YSMSync-x.x.x.jar`
2. 放入服务器 `plugins/` 目录
3. 重启服务器

首次启动会生成配置文件 `plugins/YSMSync/config.yml`。

## 配置

```yaml
# YSM 协议频道
channel: "yes_steve_model:2_6_0"

# 握手时发送的协议版本
protocol-version: "2.6.0"

# 品牌标识
brand: "open_ysm:v1"

# 是否允许客户端上传模型文件
allow-upload: true

# 上传设置
upload:
  # 最大上传文件大小（字节），默认 10MB
  max-size: 10485760
  # 每个分块大小（字节），默认 32KB（与 Fox Model Loader 客户端一致）
  chunk-size: 32000
  # 每 tick 最大分块数
  chunks-per-tick: 4

# 调试日志
debug: true

# 启动时检查更新
check-update: true

# 自动下载更新（需重启服务器生效）
auto-update: false
```

## 工作原理

1. 玩家加入时，插件通过 Netty Pipeline 拦截 YSM 自定义频道数据包
2. 发送版本检查包触发客户端握手
3. 版本检查通过后，启动加密握手流程（Packet 01-05），完成密钥交换
4. Packet 03 携带服务端已缓存模型的 hash1/hash2，客户端对比本地缓存决定是否跳过下载
5. 客户端缓存未命中时发送 Packet 04 请求，服务端通过 Packet 05 分块发送加密缓存文件
6. 握手完成后，同步所有已存储的模型给新玩家
7. 玩家切换模型时，广播切换信息给其他玩家
8. 模型文件存储在 `plugins/YSMSync/models/{玩家UUID}/{模型名}` 目录，加密缓存文件存储在 `plugins/YSMSync/cache/` 目录

## 更多信息

协议支持、项目结构、技术要点、版本演进与问题排查等详细内容，请查看 [DEVLOG.md](DEVLOG.md)。

## 构建

需要 Java 21+。

```bash
git clone https://github.com/SSChen09/YSMSync.git
cd YSMSync
./gradlew build
```

产物位于 `build/libs/YSMSync-x.x.x.jar`（包含 zstd-jni 依赖的完整 JAR）。

## 致谢

本项目的实现思路和协议解析参考了以下项目：

- [Fox Model Loader](https://github.com/sdf123098/Fox-Model-Loader) — 基于 OpenYSM 的 Fabric/NeoForge 客户端模组，提供了协议细节参考
- [Freesia II](https://github.com/NguyenDevs/FreesiaII) — 跨服模型同步架构设计，其实体 ID 映射与分层同步思路对本项目的 Netty 拦截方案有重要启发
- [Yes Steve Model](https://modrinth.com/mod/yes-steve-model) — 纯正的原版 YSM 模组
- [OpenYSM](https://github.com/OpenYSM/OpenYSM) — 开源的 YSM 提供了协议定义与实现（向开源组致敬）

## 许可证

本项目采用 [MIT License](LICENSE) 许可证。

***

### **你YSM什么时候能开源**

![YSM制作组未来计划通讯](https://static.wikitide.net/nmfwikiwiki/f/f9/YSM%E5%88%B6%E4%BD%9C%E7%BB%84%E6%9C%AA%E6%9D%A5%E8%AE%A1%E5%88%92%E9%80%9A%E8%AE%AF.png)
