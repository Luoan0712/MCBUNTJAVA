---
id: ENG-ARCH
title: 架构与数据
parent: CORE-INDEX
summary: 构建目标、模块职责、线程边界、配置键、持久化和操作日志契约
related: [PRD-SCOPE, SYS-COMMANDS, SYS-CHAT, ENG-VERIFY, OPS-DEPLOY, REG-STATE]
paths: ["docs/engineering/ENG-ARCH-架构与数据.md", "src/main/java/mcbunt/admin/MCBUNTAdminPlugin.java", "src/main/java/mcbunt/admin/core", "src/main/java/mcbunt/admin/config", "src/main/java/mcbunt/admin/moderation", "src/main/java/mcbunt/admin/audit", "src/main/resources", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle", "gradlew", "gradlew.bat", ".gitignore", ".gitattributes"]
---
# ENG-ARCH · 架构与数据

> **范围**：规定单插件的实现边界、状态归属、配置和落盘契约；具体签名以所辖源码为准。
> **不在本文**：命令参数与权限见 [SYS-COMMANDS](../design/SYS-COMMANDS-命令与权限.md)，聊天判定见 [SYS-CHAT](../design/SYS-CHAT-聊天治理.md)，部署动作见 [OPS-DEPLOY](../operations/OPS-DEPLOY-部署与重启.md)。
> **查阅**：先读 §0；改状态读 §3，改配置读 §4，改日志读 §5。

## 0 结论

采用一个 Java 插件、一个 Gradle 构建工程，使用 Paper 公共 API 和现成 YAML/文本能力。处罚数据不建立平行封禁库；禁言有独立小文件；聊天事件只做内存判定。

### 0.1 接入要点

- **新建代码时**：按 §1 的职责归属组织；源码创建后才把真实路径登记进对应文件头。
- **接管命令时**：按 §2 验证裸命令、命名空间、玩家和控制台路径，不能只验证某一入口。
- **改持久化时**：遵守 §3 的提交顺序，文件损坏和写入失败不得伪装成成功。
- **处理聊天时**：异步监听中不做文件 I/O、网络解析或非线程安全的服务器调用。

## 1 构建与职责

工程默认采用 Java 25 toolchain、Gradle Kotlin DSL 与 Gradle wrapper。编译依赖目标为 `io.papermc.paper:paper-api:26.3.build.226-stable`，使用 Paper 官方 Maven 仓库和 compileOnly；依赖实际解析成功后在 REG-STATE 更新结果，不自动换成浮动最新版。

使用 `plugin.yml` 描述主类、权限与 `api-version: '26.3'`；通过主类的命令生命周期 API 注册命令，不在 plugin.yml 再注册一套相同命令。api-version 声明 API 基线并拒绝更旧版本，不能锁定 build 226 或保证其他平台不加载。[官方版本语义](https://docs.papermc.io/paper/dev/plugin-yml/#api-version)

| 职责 | 拥有的行为与状态 | 源码位置 |
| --- | --- | --- |
| 插件入口与配置 | 初始化、依赖装配、启动配置校验、关闭资源 | src/main/java/mcbunt/admin/MCBUNTAdminPlugin.java、config/、core/ |
| 命令入口 | 语法、权限、精确入口路由、响应；不重复处罚逻辑 | 同一包下 command/ |
| 玩家身份与处罚 | UUID 解析、Paper Profile 封禁、禁言快照与提交 | 同一包下 moderation/ |
| 聊天治理 | 按 UUID 的滑窗状态、重复判定、读取禁言快照 | 同一包下 chat/ |
| 重启计划 | 唯一倒计时、取消、到期优雅停服 | 同一包下 restart/ |
| 审计 | 单一日志写入者、转义、轮转、关闭 flush | 同一包下 audit/ |
| 资源 | plugin.yml、config.yml | src/main/resources/ |

这些是职责而非必须一一对应的类或接口。小函数可以共处一个文件，不引入依赖注入框架、通用事件总线、数据库或自建权限系统。

## 2 命令与线程边界

优先通过 `LifecycleEvents.COMMANDS` 的公开注册器注册。Paper 主命令标签会覆盖已有标签，普通 alias 不会覆盖已有命令；因此 pardon/list 等已有入口作为主标签注册并共享同一执行逻辑，不能仅列为 alias。[注册 API](https://jd.papermc.io/paper/26.3/io/papermc/paper/command/brigadier/Commands.html)

原生管理命名空间按 SYS-COMMANDS 的入口表，通过公开注册器替换对应主标签，共用业务执行器；运行时 execute 的内层调用也应进入同一处理。玩家命令、控制台和远程命令事件只精确路由表内标签，不拦截任意命令、不递归重派、不靠反射修改服务端内部表。注册后记录成功标签，缺失的必需入口视为该功能未完成，不能只展示插件命名空间来通过验收。启动阶段已解析并缓存原生节点的数据包函数不属于本版的统一审计承诺。

命令服务和服务器状态变更在主线程执行。控制台/RCON 进入服务前也校验来源和权限；程序内部再次调用同一服务，避免把权限只放在玩家事件监听器。

AsyncChatEvent 监听同步完成取消决定，读取不可变禁言/权限快照，并在每个 UUID 的同步边界内更新滑窗。需要反馈、踢人或自动禁言时调度到主线程；不持有聊天锁执行服务器调用。退出重连不提前清空有效窗口，具体回收语义见 SYS-CHAT。[官方异步说明](https://docs.papermc.io/paper/dev/chat-events/)

反刷豁免权限在玩家加入时由主线程取得，并用一个主线程周期任务每秒刷新在线玩家快照；普通管理命令权限始终在执行时实时检查。权限变更最多在下一次刷新后影响反刷，不能因此绕过禁言。

## 3 身份、持久化与生命周期

### 3.1 玩家身份与封禁

以服务器提供的 UUID/Profile 为玩家身份。先查在线玩家，再查 Paper 已有且可确认身份的历史记录；允许直接输入 UUID，但记录必须能构成目标 API 接受的 Profile。未知名字明确失败，不猜测正版 UUID、不把查不到的名字自动转换成另一种离线身份，也不在主线程做网络查询。

使用类型安全的 Profile 封禁入口，按目标 API 核对 `io.papermc.paper.ban.BanListType.PROFILE` 和 Profile 类型。旧的 BanList.Type.NAME 已弃用，不作为新代码方案。[官方弃用说明](https://jd.papermc.io/paper/26.3/org/bukkit/BanList.Type.html)

封禁持久性由 Paper 的 Profile 封禁列表负责，原版 `banned-players.json` 是服务端数据文件；插件只调用公开 API，不直接编辑、不另存一份 ban 真值。在线封禁先成功写入封禁状态，再断开目标连接。离线模式改名可能改变身份，只有 UUID 保持不变时才能承诺处罚跟随改名。

### 3.2 禁言文件

运行时文件为 `plugins/MCBUNTAdmin/mutes.yml`。格式版本为 1，根字段为 `schema-version` 和 `mutes`；mutes 以 UUID 为键，每条字段如下。

| 字段 | 类型与含义 |
| --- | --- |
| last-known-name | 最近已知玩家名，仅展示，不作为匹配主键 |
| expires-at-ms | UTC epoch 毫秒整数；null 表示永久 |
| reason | 非空字符串，已应用默认理由 |
| actor | 操作者标识：玩家 UUID、CONSOLE、RCON 或 SYSTEM |
| created-at-ms | 本次处罚生效的 UTC epoch 毫秒整数 |

处理 mute/unmute 时，在主线程形成下一份小型状态，序列化到同目录临时文件，成功原子替换目标文件后再发布不可变快照、回复和公告。文件系统不支持原子替换时明确拒绝本次提交，保留原文件与旧快照，不声称普通替换具有原子性；其他写入异常同样不得先发布新状态。此处低频小文件同步提交，不扩建异步事务系统。

正常启动先校验文件版本、UUID、字段和时间范围，清理到期记录再发布快照。结构损坏或未知版本时保留原文件、报告明确错误并拒绝启用插件，不能覆盖成空表或宣称禁言已加载；上线流程按失败处理。到期判定使用绝对时间，停服期间仍计入处罚时长。

解除或新增禁言落盘失败时，保留原内存状态并返回失败。到期记录的物理清理失败不延长处罚：判断时按到期时间放行，同时报告清理失败，稍后重试。正常关闭时保存必要状态并释放资源；不承诺强杀或硬件断电下尚未完成的写入。

### 3.3 启停与时钟

启动顺序为配置校验、数据加载、审计初始化、事件与命令注册；任一步失败报告实际缺失能力。关闭时停止定时任务、取消尚未执行的重启计划、完成已接受的状态写入并 flush 日志；onDisable 不主动关闭服务器。

处罚到期采用 UTC 墙上时钟；聊天窗口和重启经过时间采用单调时钟。服务器卡顿不能把调度 tick 数直接当成真实秒数；到期停服仍只能在主线程恢复执行时发生。

## 4 配置契约

运行时 `plugins/MCBUNTAdmin/config.yml` 随首次启用生成，基础版通过完整重启加载配置，不增加热重载命令。以下表规定配置键；分发默认值在 src/main/resources/config.yml，设计参数以“默认值权威”指向处为准，调整时同批维护。

| 配置键 | 类型 | 默认值权威或固定值 | 约束 |
| --- | --- | --- | --- |
| schema-version | 整数 | 1 | 不支持的版本报错 |
| ban.default-duration | 时长字符串 | SYS-COMMANDS §时长与理由 | 遵循同一时长解析器 |
| mute.default-duration | 时长字符串 | SYS-COMMANDS §时长与理由 | 遵循同一时长解析器 |
| messages.default-reasons | 映射 | SYS-COMMANDS §时长与理由 | ban、mute、kick 三个子键均为非空纯文本 |
| restart.default-seconds | 整数 | SYS-COMMANDS §倒计时重启 | 在命令允许范围内 |
| restart.announce-seconds | 整数列表 | SYS-COMMANDS §倒计时重启 | 正数、去重、降序处理 |
| antispam.enabled | 布尔 | true | 关闭不影响手动禁言 |
| antispam.window-seconds | 正整数 | SYS-CHAT §规则默认值 | 大于 0 |
| antispam.max-messages | 正整数 | SYS-CHAT §规则默认值 | 大于 0 |
| antispam.duplicate-check | 布尔 | SYS-CHAT §规则默认值 | 开关只影响重复检测 |
| antispam.action | 枚举 | SYS-CHAT §规则默认值 | cancel / warn / mute / kick |
| antispam.auto-mute-seconds | 正整数 | SYS-CHAT §规则默认值 | action=mute 时使用 |
| logging.record | 类别列表 | §操作日志 | 只接受已定义类别 |
| logging.max-size-mb | 正整数 | §操作日志 | 单位按 1024×1024 字节解释 |
| logging.keep-files | 非负整数 | §操作日志 | 历史文件数量，不含当前文件 |

缺少可选键时采用已登记默认值；类型错误、越界时长、未知动作或未知类别报出具体键并拒绝启用，不静默改成永久处罚或其他动作。上线基线保留默认管理日志类别；主动删减类别属于管理员配置变更，应在实际验收范围中说明。

## 5 操作日志

工程默认启用类别为 `ban / unban / mute / unmute / kick / broadcast / antispam / restart`。pardon 归 unban，自动禁言同时产生 antispam 与 mute 两类不同事件；一项服务调用不因别名或重复入口监听而重复记同一结果。TPS、在线列表、muteinfo 等只读查询默认不写管理日志。

日志使用 UTF-8 JSON Lines，一行一个对象，位置为 `plugins/MCBUNTAdmin/logs/admin.log`。字段如下。

| 字段 | 含义 |
| --- | --- |
| timestamp | 带 Z 的 ISO 8601 UTC 时间 |
| operationId | 一次管理请求或防刷触发的标识；同次自动处罚记录共享该值 |
| action / phase | 上述操作类别；重启另区分 SCHEDULE、CANCEL、EXECUTE |
| actor / source | 玩家 UUID 或 CONSOLE/RCON/SYSTEM；来源通道 |
| targetUuid / targetName | 玩家目标；非玩家操作为 null |
| durationSeconds / expiresAt | 有效处罚时长与绝对到期时间；永久使用 null |
| reason / content | 处罚理由或公告正文；不相关字段省略 |
| result / detail | APPLIED、NO_CHANGE、DENIED、INVALID 或 FAILED，以及原因 |

不存玩家 IP、凭证或完整聊天流水。防刷日志记录触发原因与处置结果，不默认记录消息正文。理由和公告由 JSON 序列化器转义控制字符，不能将换行注入成伪造日志行。原始命令字符串不替代结构化结果。

当前文件即将超过 `logging.max-size-mb=5` 时轮转，保留 `logging.keep-files=10` 个历史文件；.1 为最近历史，超出数量删除最旧历史，当前文件另计。一条事件不在轮转时截断。

采用一个有界 FIFO 写入队列和单一写入者，队列上限为 1024 条；关闭最多等待 3 秒完成已接受记录。队列满或落盘失败时向服务端日志报告丢失数量与原因，并通知仍在线的操作者；不静默丢条。正常回执只确认管理动作结果，不把单纯入队写成“已落盘”；审计故障单独报告，不为补日志重复踢人、重放处罚或撤销已完成的封禁。

重启 EXECUTE 表示已请求优雅关闭，不表示外部已重新拉起。到期先提交必要处罚状态，失败则取消本次计划；成功后尝试有界 flush 并调用服务端优雅关闭。单纯日志写入失败报告实际结果，不能等待日志无限阻止停服。

## 6 技术依据与实现验证

Paper 依赖与 Java 目标见[官方工程配置](https://docs.papermc.io/paper/dev/project-setup/)；命令注册、聊天线程、Profile 封禁的依据在相应小节。文档中的 API 选择仍需以锁定依赖编译和目标服务端联调确认，验证结果写入 REG-STATE。
