---
id: PRD-SCOPE
title: 需求与边界
parent: CORE-INDEX
summary: 插件目标、用户约束、功能范围和环境前提
related: [SYS-COMMANDS, SYS-CHAT, ENG-ARCH, ENG-VERIFY, OPS-DEPLOY, REG-STATE]
paths: ["docs/product/PRD-SCOPE-需求与边界.md", "疑点解释.md", "docs/sources/用户补充截图.png"]
---
# PRD-SCOPE · 需求与边界

> **范围**：规定基础版管理插件做什么、服务于谁，以及不能违反的用户约束。
> **不在本文**：命令细则见 [SYS-COMMANDS](../design/SYS-COMMANDS-命令与权限.md)，聊天判定见 [SYS-CHAT](../design/SYS-CHAT-聊天治理.md)，开发现状见 [REG-STATE](../registry/REG-STATE-决策与开发状态.md)。
> **查阅**：先读 §0；功能覆盖看 §2，环境前提看 §3。

## 0 结论

为 Minecraft Java 服务端开发一个 Paper 管理插件，沿用资料中的项目名 MCBUNTAdmin；交付可构建源码、可安装 JAR、默认配置和安装说明。功能边界以本文为准，尚未实现的设计不视为现有能力。

### 0.1 接入要点

- **处理玩家处罚时**：遵守 §1 的账号身份与中转 IP 约束。
- **增加功能时**：先检查 §2；新后台、跨服系统和额外客户端不属于本次范围。
- **引用环境或完成状态时**：查 REG-STATE，不把历史说明中的完成表述当成验证结果。

## 1 用户约束

| 编码 | 约束 | 来源 |
| --- | --- | --- |
| INV-IDENTITY | 服务器使用中转 IP；处罚针对玩家身份，禁止本插件注册、调用或以其他方式实施 IP 封禁，也不按共享 IP 连带处罚 | 用户补充截图 |
| INV-ENTRY | 直接使用并接管约定的现有命令入口；不能要求管理员改用一套 m 前缀命令才能得到增强行为和日志 | 用户补充截图 |
| INV-AUDIT | 插件负责的管理操作和聊天防刷屏处置留有操作记录，不能在未落盘时宣称审计已成功 | 用户初始需求 |

中转 IP 只说明网络路径，不能据此推断是否存在 Velocity/BungeeCord、是否开启正版验证或 UUID 是否经过可信转发。账号身份的实际来源在部署时核实。

## 2 基础版范围

| 需求编码 | 能力 | 行为权威 |
| --- | --- | --- |
| REQ-KICK | 踢出在线玩家，允许填写理由 | SYS-COMMANDS §命令与权限表 |
| REQ-BAN | 永久或临时封禁玩家，支持已知身份的离线玩家 | SYS-COMMANDS §玩家身份、时长与处罚结果 |
| REQ-UNBAN | 解封；同时提供用户要求的 unban 和原有 pardon 入口 | SYS-COMMANDS §命令与权限表 |
| REQ-MUTE | 临时或永久禁言，重连和正常重启后保留未到期记录 | SYS-COMMANDS §玩家身份、时长与处罚结果；SYS-CHAT §判定顺序 |
| REQ-UNMUTE | 解除禁言；补充 muteinfo 查询目标状态 | SYS-COMMANDS §命令与权限表 |
| REQ-TPS | 提供可用的 TPS 查询，复用 Paper 原生实现 | SYS-COMMANDS §命令接管与入口一致性 |
| REQ-ONLINE | 查询在线人数、容量和玩家名；保留 online 与 list | SYS-COMMANDS §命令与权限表 |
| REQ-RESTART | 可取消的倒计时重启，时间到后优雅停服并由外部机制拉起 | SYS-COMMANDS §倒计时重启；[OPS-DEPLOY §外部守护与正常退出](../operations/OPS-DEPLOY-部署与重启.md) |
| REQ-BROADCAST | 面向全服在线玩家发送公告 | SYS-COMMANDS §命令与权限表 |
| REQ-ANTISPAM | 公共聊天频率限制与重复内容检测，支持指定处置档位和反刷豁免 | SYS-CHAT |
| REQ-LOG | 记录管理结果、自动处置和重启阶段，并按大小轮转 | [ENG-ARCH §操作日志](../engineering/ENG-ARCH-架构与数据.md) |
| REQ-ACCESS | 插件命令有权限检查，支持玩家、服务器控制台和既有 RCON 入口 | SYS-COMMANDS §命令与权限表 |

基础版只管理当前这一台 Paper 服务器。禁言、防刷屏先覆盖公共聊天；私聊、/me、第三方频道、Discord 和代理层跨服聊天不在该覆盖承诺内。

不开发 Web 面板、桌面 EXE、SSH 服务、RCON 服务、数据库后台或管理 GUI；可由现有控制台/RCON 调用插件。/reload、IP 封禁、跨服同步和封禁历史查询不作为本版新增功能。

## 3 环境与交付前提

开发目标采用原材料指定的 Paper 26.3 build 226 与 Java 25；这是兼容目标，不代表本机或远程服务器已安装，也不承诺兼容 Spigot、Purpur、Folia、Fabric 或 Forge。

原材料提到的 Temurin 补丁版本、systemd 服务名、既有插件和成品状态，均需按 REG-STATE 的环境项核实。开发可以先按目标 API 进行；身份专项和真实重启验收需等待对应环境信息。

交付包括 Gradle 工程与 wrapper、MCBUNTAdmin.jar、随 JAR 分发的默认配置、本文档集，以及 ENG-VERIFY 对应的实际验证结果。构建产物只有在构建成功后才能登记为已交付。

## 4 来源与默认值

原始资料为[疑点解释](../../疑点解释.md)和[用户补充截图](../sources/用户补充截图.png)。用户文字和截图明确的要求标为“用户确认”；原始说明被采纳的规则和为补齐行为而选择的参数标为“工程默认”；远程安装、托管与验证结果标为“待核实环境”。每项决定及其正文落点登记在 REG-STATE。

工程默认可直接支撑开发，不等于用户逐项确认。后续用户要求改变某项默认时，同批修改其权威正文、相关配置和受影响验收场景。
