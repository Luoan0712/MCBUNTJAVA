---
id: CORE-INDEX
title: 开发总纲
summary: MCBUNTAdmin 的文档入口、信息地图与开发接手方式
related: [PRD-SCOPE, SYS-COMMANDS, SYS-CHAT, ENG-ARCH, ENG-VERIFY, OPS-DEPLOY, REG-STATE, STD-DOC]
paths: ["docs/README.md", "README.md", "AGENTS.md", "docs/SECTIONS.md"]
---
# CORE-INDEX · 开发总纲

> **范围**：本项目唯一开发总纲，提供全局阅读入口和信息归属。
> **不在本文**：用户约束、功能细则、默认值和真实进度分别由 §1 的主题文档负责。
> **查阅**：接手先读 §0 和 §2；需要细节按 §1 定位，查小节用 SECTIONS。

## 0 结论

MCBUNTAdmin 是 Minecraft Java 的单服务器 Paper 管理插件。产品范围与用户约束由 PRD-SCOPE 规定，开发目标与运行状态分开记录。文档采用“总纲→主题”两层结构。

### 0.1 接入要点

- **开始工作**：先读 REG-STATE 的状态与任务，再读负责主题的 §0、接入要点和需要修改的小节。
- **改变需求或默认**：按 STD-DOC 找唯一权威，同批维护关联契约、配置和验收，不在总纲添加参数副本。
- **记录完成情况**：只在 REG-STATE 登记实际结果；历史材料、设计或构建成功都不能代替服务器行为验证。

## 1 信息地图

| 要回答的问题 | 权威文档 |
| --- | --- |
| 用户要什么、硬约束和本版边界是什么 | [PRD-SCOPE · 需求与边界](product/PRD-SCOPE-需求与边界.md) |
| 命令怎么用、谁能用、如何处理处罚和倒计时 | [SYS-COMMANDS · 命令与权限](design/SYS-COMMANDS-命令与权限.md) |
| 禁言覆盖哪里、防刷屏如何判断与处置 | [SYS-CHAT · 聊天治理](design/SYS-CHAT-聊天治理.md) |
| 如何构建、分配职责、保存状态、配置和记日志 | [ENG-ARCH · 架构与数据](engineering/ENG-ARCH-架构与数据.md) |
| 功能做到什么程度、怎样验收 | [ENG-VERIFY · 验收标准](engineering/ENG-VERIFY-验收标准.md) |
| 如何核实环境、安装和保证真实重启 | [OPS-DEPLOY · 部署与重启](operations/OPS-DEPLOY-部署与重启.md) |
| 哪些是用户要求或工程默认、现在做到哪、下一步做什么 | [REG-STATE · 决策与开发状态](registry/REG-STATE-决策与开发状态.md) |
| 文档怎样组织、引用与更新 | [STD-DOC · 文档写法](standards/STD-DOC-文档写法.md) |

[SECTIONS · 小节目录](SECTIONS.md) 只帮助定位，不保存第二份规则。原始资料为[疑点解释](../疑点解释.md)和[用户补充截图](sources/用户补充截图.png)，不作为现行规格或部署证明。

## 2 开发入口与变更方式

开发前按 REG-STATE 的任务顺序定位本次范围，按 PRD-SCOPE 核对用户约束，然后阅读对应系统契约；涉及落盘或线程再读 ENG-ARCH，涉及安装再读 OPS-DEPLOY。

新文件先找所属主题；代码路径尚未存在时只在架构正文标记规划，创建后再维护相关 paths。实现优先使用 Paper 原生能力，模块拆分服务当前需求，不预建扩展框架或新管理后台。

开发结果按 ENG-VERIFY 检查受影响功能。文档批只检查链接、编码、引用、归属和一致性；没有实际执行的插件检查保留未验证。涉及多名代理时明确互不重叠的写入范围，由集成人处理共享入口与收尾。
