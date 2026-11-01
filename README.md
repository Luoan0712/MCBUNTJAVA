# MCBUNTAdmin

Minecraft **Java / Paper** 服务器基础管理插件。  
面向单服运维：踢出、封禁、禁言、公告、在线查询、防刷屏、操作审计，以及可取消的倒计时重启。

> **当前仓库状态**：基础版 **0.1.0** 源码、Gradle 工程与自动化测试已实现；构建、本机服务端验证和待完成的玩家验收以 [开发状态](docs/registry/REG-STATE-决策与开发状态.md) 为准。

---

## 功能一览

| 功能 | 说明 |
| --- | --- |
| 踢出 | `/kick` 在线玩家，可填理由 |
| 封禁 / 解封 | 永久或临时；支持已知身份的离线玩家；`/ban`、`/pardon`、`/unban` |
| 禁言 / 解禁 | 临时或永久；重连、正常重启后保留未到期记录；`/mute`、`/unmute`、`/muteinfo` |
| 在线查询 | 人数、上限、玩家列表；`/online`、`/list` |
| 公告 | 全服在线玩家公告；`/broadcast` |
| TPS | 复用 Paper 原生 `/tps`，不另建性能采集器 |
| 防刷屏 | 公共聊天频率限制 + 重复内容检测；动作可配 |
| 操作日志 | 管理与自动处置落盘，按大小轮转 |
| 倒计时重启 | 可取消；到期优雅停服，由外部守护拉起进程 |

**明确不做（本版）**：Web 面板、桌面 EXE、SSH/RCON 服务本体、数据库后台、管理 GUI、跨服同步、配置热重载、IP 封禁、封禁历史查询。

---

## 运行环境（开发目标）

| 项 | 目标 |
| --- | --- |
| 服务端 | Paper **26.3**（构建目标 build 226-stable） |
| 游戏 | Minecraft Java **26.3** |
| Java | **25**（以实际服务进程所用 JDK 为准） |
| 部署形态 | **单服**；不兼容声明外的 Spigot / Purpur / Fabric / Forge |

`plugin.yml` 使用 `api-version: '26.3'`。具体 Paper build 与本机/远程 Java 是否已就绪，见运维核实项（ENV-01）。

---

## 安装

1. 用项目 Gradle Wrapper 构建，得到可安装的 `MCBUNTAdmin.jar`。  
2. 在测试服验证后再上生产；维护窗口内**正常停服**（不要在运行中覆盖 JAR）。  
3. 备份现有插件、插件数据与原版封禁数据。  
4. 将 JAR 放入服务端 `plugins/`，移走旧版同名插件。  
5. 启动后检查加载日志；首次启动生成 `config.yml`。  
6. 按验收标准核对命令、权限、聊天与持久化。

更完整的环境核实、systemd 守护与维护停服步骤见 [部署与重启](docs/operations/OPS-DEPLOY-部署与重启.md)。

> **重启语义**：插件只负责倒计时与优雅停服；**进程重新拉起**依赖 systemd 等外部守护（需验证正常退出仍会重启）。未配置好守护前，不要把 `/restart` 当成「停了还会自己回来」。

---

## 命令

权限前缀：`mcbunt.admin.`（下表只写后缀）。  
默认权限：全部 `default: op`。控制台与 RCON 可执行同一套管理入口。

| 命令 | 权限后缀 | 说明 |
| --- | --- | --- |
| `/kick <玩家> [理由]` | `kick` | 踢出在线玩家 |
| `/ban <玩家> [时长] [理由]` | `ban` | 封禁；省略时长默认为永久 |
| `/pardon <玩家>` / `/unban <玩家>` | `unban` | 解封，两入口等价 |
| `/mute <玩家> [时长] [理由]` | `mute` | 公共聊天禁言；省略时长默认 30 分钟 |
| `/unmute <玩家>` | `unmute` | 解除禁言 |
| `/muteinfo <玩家>` | `muteinfo` | 查询禁言状态、理由、剩余时间 |
| `/online` / `/list` | `online` | 在线人数与列表 |
| `/restart [秒]` / `/restart cancel` | `restart` | 安排或取消倒计时重启（默认 60 秒） |
| `/broadcast <正文>` | `broadcast` | 全服公告 |
| `/tps` | Paper 原生 | 不接管，使用服务端自带实现 |

另有插件命名空间入口（如 `mcbuntadmin:ban`），与短命令共用权限、业务与日志。

### 时长格式

- 有限时长：正整数 + 单位 `s` / `m` / `h` / `d`（例如 `30m`、`2h`、`1d`）  
- 永久：`perm`  
- **不支持**复合时长（如 `1h30m`）、`0`、负数、裸数字  

示例：

```text
/ban Alex 2h 恶意破坏
/ban Alex 恶意破坏
/mute Alex 15m 刷屏
/restart 120
/restart cancel
```

---

## 权限节点

| 节点 | 默认 | 说明 |
| --- | --- | --- |
| `mcbunt.admin.kick` | op | 踢出 |
| `mcbunt.admin.ban` | op | 封禁 |
| `mcbunt.admin.unban` | op | 解封 |
| `mcbunt.admin.mute` | op | 禁言 |
| `mcbunt.admin.unmute` | op | 解禁 |
| `mcbunt.admin.muteinfo` | op | 禁言查询 |
| `mcbunt.admin.online` | op | 在线查询 |
| `mcbunt.admin.restart` | op | 重启计划 |
| `mcbunt.admin.broadcast` | op | 公告 |
| `mcbunt.admin.*` | op | 上述子权限的显式集合 |
| `mcbunt.admin.antispam.bypass` | op | **仅**豁免防刷屏，**不**豁免人工禁言 |

说明：

- OP **没有**隐含的处罚豁免：有权者可以 ban / mute / kick 其他 OP。  
- 命令方块不在支持范围。  
- **不实施 IP 封禁**，也不因共享出口 IP 连带处罚玩家。

---

## 配置（`plugins/MCBUNTAdmin/config.yml`）

首次启用时生成。修改后需**重启服务端**加载（无热重载命令）。

主要配置段：

| 段 | 作用 |
| --- | --- |
| `ban` / `mute` | 省略时长时的默认处罚时长 |
| `messages.default-reasons` | ban / mute / kick 默认理由文案 |
| `restart` | 默认秒数、提醒节点列表 |
| `antispam` | 开关、窗口秒数、窗口内最大条数、重复检测、动作、自动禁言秒数 |
| `logging` | 记录类别、单文件大小上限、保留历史份数 |

防刷屏默认值（工程默认，可改）：

| 键 | 默认 |
| --- | --- |
| `antispam.enabled` | `true` |
| `antispam.window-seconds` | `5` |
| `antispam.max-messages` | `4`（窗口内第 5 条触发） |
| `antispam.duplicate-check` | `true` |
| `antispam.action` | `cancel`（另有 `warn` / `mute` / `kick`） |
| `antispam.auto-mute-seconds` | `120` |

配置类型错误、越界或未知枚举时，插件应**拒绝启用**并指出具体键，而不是静默改成危险默认值。

---

## 数据与日志

| 文件 | 用途 |
| --- | --- |
| `plugins/MCBUNTAdmin/mutes.yml` | 禁言记录（按玩家 UUID；含到期时间） |
| `plugins/MCBUNTAdmin/logs/admin.log` | 管理与自动处置日志（JSON Lines） |
| 服务端 `banned-players.json` | 封禁由 Paper 原生 Profile 封禁列表持久化 |

日志默认类别示例：`ban`、`unban`、`mute`、`unmute`、`kick`、`broadcast`、`antispam`、`restart`。  
默认约 5MB 轮转，保留若干历史文件；**不**默认记录玩家 IP、密码或完整聊天流水。

---

## 聊天治理（防刷屏）

- 作用范围：Paper **公共聊天**（`AsyncChatEvent`）。  
- **不**拦截：`/msg`、`/tell`、`/w`、`/me`，以及第三方聊天插件频道。  
- 同一条消息：先判禁言，再判防刷；按 **UUID** 计窗口，不按 IP。  
- 豁免：`mcbunt.admin.antispam.bypass` 只免防刷，不免人工禁言。

---

## 安全与身份说明（请读）

1. **处罚跟人，不跟 IP**：使用服务器提供的玩家 UUID/Profile；禁止本插件 IP 封禁。  
2. **UUID 可信度取决于环境**：正版转发正常时，改名通常不影响封禁；离线模式下 UUID 可能随名字变化，**改名可能绕过**。部署前请核实登录与转发方式。  
3. **不内置账号注册/登录**：ban 强度与 Paper 原生 UUID 封禁同级，不额外做密码账号体系。  
4. **审计**：管理操作与自动处置应有落盘记录；未落盘不得宣称审计成功。  
5. **紧急恢复**：务必保证控制台或 RCON 可用，以便误 ban 时解封。

---

## 文档

本仓库以文档为权威，入口如下：

| 文档 | 内容 |
| --- | --- |
| [开发总纲](docs/README.md) | 信息地图与接手方式 |
| [需求与边界](docs/product/PRD-SCOPE-需求与边界.md) | 做什么、不做什么、用户约束 |
| [命令与权限](docs/design/SYS-COMMANDS-命令与权限.md) | 命令契约细则 |
| [聊天治理](docs/design/SYS-CHAT-聊天治理.md) | 禁言与防刷屏 |
| [架构与数据](docs/engineering/ENG-ARCH-架构与数据.md) | 模块、配置键、持久化、日志 |
| [验收标准](docs/engineering/ENG-VERIFY-验收标准.md) | V-01～V-31 检查项 |
| [部署与重启](docs/operations/OPS-DEPLOY-部署与重启.md) | 构建安装与外部守护 |
| [决策与开发状态](docs/registry/REG-STATE-决策与开发状态.md) | **唯一**进度与决策登记处 |
| [小节目录](docs/SECTIONS.md) | 标题定位 |

原始背景材料（`疑点解释.md`、用户截图）仅作来源，**不作为**本项目已部署或已验收的证明。

---

## 构建

先将 JDK 25 设置为 `JAVA_HOME`，使用仓库内的 Gradle Wrapper：

```powershell
# Windows
.\gradlew.bat clean build
```

```sh
# Linux / macOS
./gradlew clean build
```

安装包输出为 `build/libs/MCBUNTAdmin.jar`。构建会执行自动化测试；玩家操作和生产服务器联调状态见 REG-STATE。

---

## 许可证

仓库未声明开源许可证时，默认保留作者权利；对外分发前请先与维护者确认。

---

## 仓库

https://github.com/Luoan0712/MCBUNTJAVA
