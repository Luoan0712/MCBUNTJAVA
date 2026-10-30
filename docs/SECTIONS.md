# 小节目录

本目录只定位现行文档的小节；规则以目标文档正文为准。标题或路径变化时同批更新本目录。

## [CORE-INDEX · 开发总纲](README.md)

- [0 结论](README.md#0-结论)
  - [0.1 接入要点](README.md#01-接入要点)
- [1 信息地图](README.md#1-信息地图)
- [2 开发入口与变更方式](README.md#2-开发入口与变更方式)

## [PRD-SCOPE · 需求与边界](product/PRD-SCOPE-需求与边界.md)

- [0 结论](product/PRD-SCOPE-需求与边界.md#0-结论)
  - [0.1 接入要点](product/PRD-SCOPE-需求与边界.md#01-接入要点)
- [1 用户约束](product/PRD-SCOPE-需求与边界.md#1-用户约束)
- [2 基础版范围](product/PRD-SCOPE-需求与边界.md#2-基础版范围)
- [3 环境与交付前提](product/PRD-SCOPE-需求与边界.md#3-环境与交付前提)
- [4 来源与默认值](product/PRD-SCOPE-需求与边界.md#4-来源与默认值)

## [SYS-COMMANDS · 命令与权限](design/SYS-COMMANDS-命令与权限.md)

- [0 结论](design/SYS-COMMANDS-命令与权限.md#0-结论)
  - [0.1 接入要点](design/SYS-COMMANDS-命令与权限.md#01-接入要点)
- [1 命令与权限表](design/SYS-COMMANDS-命令与权限.md#1-命令与权限表)
- [2 命令接管与入口一致性](design/SYS-COMMANDS-命令与权限.md#2-命令接管与入口一致性)
- [3 玩家身份、时长与处罚结果](design/SYS-COMMANDS-命令与权限.md#3-玩家身份时长与处罚结果)
  - [3.1 身份解析](design/SYS-COMMANDS-命令与权限.md#31-身份解析)
  - [3.2 时长与理由](design/SYS-COMMANDS-命令与权限.md#32-时长与理由)
  - [3.3 变更与反馈](design/SYS-COMMANDS-命令与权限.md#33-变更与反馈)
- [4 倒计时重启](design/SYS-COMMANDS-命令与权限.md#4-倒计时重启)

## [SYS-CHAT · 聊天治理](design/SYS-CHAT-聊天治理.md)

- [0 结论](design/SYS-CHAT-聊天治理.md#0-结论)
  - [0.1 接入要点](design/SYS-CHAT-聊天治理.md#01-接入要点)
- [1 作用范围](design/SYS-CHAT-聊天治理.md#1-作用范围)
- [2 判定与窗口](design/SYS-CHAT-聊天治理.md#2-判定与窗口)
  - [2.1 判定顺序](design/SYS-CHAT-聊天治理.md#21-判定顺序)
  - [2.2 规则默认值](design/SYS-CHAT-聊天治理.md#22-规则默认值)
- [3 触发动作与审计](design/SYS-CHAT-聊天治理.md#3-触发动作与审计)
- [4 异步接入边界](design/SYS-CHAT-聊天治理.md#4-异步接入边界)

## [ENG-ARCH · 架构与数据](engineering/ENG-ARCH-架构与数据.md)

- [0 结论](engineering/ENG-ARCH-架构与数据.md#0-结论)
  - [0.1 接入要点](engineering/ENG-ARCH-架构与数据.md#01-接入要点)
- [1 构建与职责](engineering/ENG-ARCH-架构与数据.md#1-构建与职责)
- [2 命令与线程边界](engineering/ENG-ARCH-架构与数据.md#2-命令与线程边界)
- [3 身份、持久化与生命周期](engineering/ENG-ARCH-架构与数据.md#3-身份持久化与生命周期)
  - [3.1 玩家身份与封禁](engineering/ENG-ARCH-架构与数据.md#31-玩家身份与封禁)
  - [3.2 禁言文件](engineering/ENG-ARCH-架构与数据.md#32-禁言文件)
  - [3.3 启停与时钟](engineering/ENG-ARCH-架构与数据.md#33-启停与时钟)
- [4 配置契约](engineering/ENG-ARCH-架构与数据.md#4-配置契约)
- [5 操作日志](engineering/ENG-ARCH-架构与数据.md#5-操作日志)
- [6 技术依据与实现验证](engineering/ENG-ARCH-架构与数据.md#6-技术依据与实现验证)

## [ENG-VERIFY · 验收标准](engineering/ENG-VERIFY-验收标准.md)

- [0 结论](engineering/ENG-VERIFY-验收标准.md#0-结论)
  - [0.1 接入要点](engineering/ENG-VERIFY-验收标准.md#01-接入要点)
- [1 环境与快检](engineering/ENG-VERIFY-验收标准.md#1-环境与快检)
- [2 命令与处罚](engineering/ENG-VERIFY-验收标准.md#2-命令与处罚)
- [3 聊天治理与权限](engineering/ENG-VERIFY-验收标准.md#3-聊天治理与权限)
- [4 重启专项](engineering/ENG-VERIFY-验收标准.md#4-重启专项)
- [5 数据、日志与交付](engineering/ENG-VERIFY-验收标准.md#5-数据日志与交付)
- [6 需求覆盖](engineering/ENG-VERIFY-验收标准.md#6-需求覆盖)

## [OPS-DEPLOY · 部署与重启](operations/OPS-DEPLOY-部署与重启.md)

- [0 结论](operations/OPS-DEPLOY-部署与重启.md#0-结论)
  - [0.1 接入要点](operations/OPS-DEPLOY-部署与重启.md#01-接入要点)
- [1 环境核实](operations/OPS-DEPLOY-部署与重启.md#1-环境核实)
- [2 构建与安装](operations/OPS-DEPLOY-部署与重启.md#2-构建与安装)
- [3 外部守护与正常退出](operations/OPS-DEPLOY-部署与重启.md#3-外部守护与正常退出)
- [4 维护停服与异常处理](operations/OPS-DEPLOY-部署与重启.md#4-维护停服与异常处理)

## [REG-STATE · 决策与开发状态](registry/REG-STATE-决策与开发状态.md)

- [0 结论](registry/REG-STATE-决策与开发状态.md#0-结论)
  - [0.1 接入要点](registry/REG-STATE-决策与开发状态.md#01-接入要点)
- [1 生效决定](registry/REG-STATE-决策与开发状态.md#1-生效决定)
- [2 当前状态](registry/REG-STATE-决策与开发状态.md#2-当前状态)
- [3 开发任务与接手顺序](registry/REG-STATE-决策与开发状态.md#3-开发任务与接手顺序)
- [4 待核实环境](registry/REG-STATE-决策与开发状态.md#4-待核实环境)
- [5 验证结果记录](registry/REG-STATE-决策与开发状态.md#5-验证结果记录)

## [STD-DOC · 文档写法](standards/STD-DOC-文档写法.md)

- [0 结论](standards/STD-DOC-文档写法.md#0-结论)
  - [0.1 接入要点](standards/STD-DOC-文档写法.md#01-接入要点)
- [1 信息归属](standards/STD-DOC-文档写法.md#1-信息归属)
- [2 形状与表达](standards/STD-DOC-文档写法.md#2-形状与表达)
- [3 来源与生效](standards/STD-DOC-文档写法.md#3-来源与生效)
- [4 最少必要维护](standards/STD-DOC-文档写法.md#4-最少必要维护)
