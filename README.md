# YSMSync

Paper 服务端插件，实现 [Yes Steve Model](https://github.com/OpenYSM/YesSteveModel) / [Fox Model Loader](https://github.com/AntonyBlayze/Fox-Model-Loader-main) 的模型同步功能。

玩家在客户端安装 YSM 模组后，服务器自动同步所有玩家的模型选择，使每个人都能看到彼此的自定义模型。

## 功能

- **模型同步** — 玩家加入或切换模型时，自动广播给所有在线玩家
- **模型文件存储** — 服务端缓存 `.ysm` 模型文件，新玩家加入时自动推送
- **模型上传** — 客户端可将模型文件上传至服务端存储（可选，需在配置中启用）
- **动画转发** — 转发动画/表情指令给其他玩家
- **Netty 拦截** — 在 Pipeline 层直接捕获 YSM 自定义频道数据包，兼容 Paper 26.1.x
- **SQLite 持久化** — 玩家模型选择存储在 `plugins/YSMSync/ysm_data.db`，重启不丢失

## 要求

- Paper 1.21.4+（已测试 Paper 26.1.2）
- Java 21+
- 客户端需安装 [Yes Steve Model](https://github.com/OpenYSM/YesSteveModel) 或 [Fox Model Loader](https://github.com/AntonyBlayze/Fox-Model-Loader-main) 模组

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
allow-upload: false

# 上传设置
upload:
  # 最大上传文件大小（字节），默认 10MB
  max-size: 10485760
  # 每个分块大小（字节），默认 1MB
  chunk-size: 1048576
  # 每 tick 最大分块数
  chunks-per-tick: 4

# 调试日志
debug: false
```

## 工作原理

1. 玩家加入时，插件通过 Netty Pipeline 拦截 YSM 自定义频道数据包
2. 发送版本检查包触发客户端握手
3. 握手完成后，同步所有已存储的模型给新玩家
4. 玩家切换模型时，广播切换信息给其他玩家
5. 模型文件存储在 `plugins/YSMSync/models/` 目录（按玩家 UUID 命名）

## 协议支持

基于 [YSM 2.6.0 协议](https://github.com/OpenYSM/YesSteveModel)，支持以下数据包类型：

| ID | 方向 | 名称 |
|----|------|------|
| 1 | S→C | SetModelAndTexture |
| 2 | C→S | ModelSync |
| 3 | S→C | ExecuteMolang |
| 4 | S→C | SetModelAndTexture (sync) |
| 5 | C→S | RequestSwitchModel |
| 6 | S→C | SyncAuthModels |
| 7 | C→S | PlayAnimation |
| 8 | S→C | SyncStarModels |
| 9 | C→S | StopAnimation |
| 15 | C→S | SyncAnimationExpression |
| 17 | C→S | Unknown17 |
| 18 | C→S | SyncAnimationExpression |
| 19 | S→C | SyncAnimationExpression |
| 21 | S→C | Unknown21 |
| 23 | C→S | Unknown23 |
| 51 | S→C | VersionCheck |
| 52 | C→S | VersionCheckResponse |
| 70 | C→S | UploadStart |
| 71 | S→C | UploadStartResponse |
| 72 | C→S | UploadChunk |
| 73 | C→S | UploadFinish |
| 74 | S→C | UploadResult |

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
└── build.gradle.kts                # Gradle 构建脚本
```

## 构建

需要 Java 21+。

```bash
git clone https://github.com/SSChen09/YSMSync.git
cd YSMSync
./gradlew build
```

产物位于 `build/libs/YSMSync-x.x.x.jar`。

## 许可证

MIT License
