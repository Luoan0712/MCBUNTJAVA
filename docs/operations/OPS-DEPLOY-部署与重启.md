---
id: OPS-DEPLOY
title: 部署与重启
parent: CORE-INDEX
summary: 源码到位后的构建安装、环境核实、外部守护与维护停服步骤
related: [PRD-SCOPE, SYS-COMMANDS, ENG-ARCH, ENG-VERIFY, REG-STATE]
paths: ["docs/operations/OPS-DEPLOY-部署与重启.md"]
---
# OPS-DEPLOY · 部署与重启

> **范围**：规定插件实施后的构建、安装、重启保障与维护操作。
> **不在本文**：命令行为与倒计时参数见 [SYS-COMMANDS](../design/SYS-COMMANDS-命令与权限.md)，配置和数据存放见 [ENG-ARCH](../engineering/ENG-ARCH-架构与数据.md)，验证标准见 [ENG-VERIFY](../engineering/ENG-VERIFY-验收标准.md)。
> **查阅**：环境看 §1，构建安装看 §2，外部拉起与维护停止看 §3、§4。

## 0 结论

- 本文规定实施后的操作步骤；实际执行与环境核实结果只在 REG-STATE 登记。
- 构建目标由 PRD-SCOPE「环境与交付前提」与 ENG-ARCH「构建与职责」规定；构建工具到位后才执行本文命令。
- 插件只负责倒计时和优雅停止；进程重新启动由已核实的外部守护负责。
- 本版不新建 Web 面板、桌面 EXE、RCON 或 SSH 管理通道；已有通道是否可用不作为插件部署成功依据。

### 0.1 接入要点

- 首次安装前核实身份转发、现有插件命令占用和正常退出后的拉起策略，结果写入 [REG-STATE](../registry/REG-STATE-决策与开发状态.md)。
- 替换 JAR、修改持久化数据前正常停服并备份；不要在运行中覆盖插件文件或用热重载代替重新启动。
- 本文命令由有相应权限的运维人员在目标测试或服务器环境执行；示意路径按实际环境填写。

## 1 环境核实

| 检查 | 取值方式 | 要确认的事实 |
| --- | --- | --- |
| Paper | 服务端 `/version` 或启动日志 | 实际游戏版本、Paper build，与目标及构建依赖一致 |
| Java | 启动服务器所用 Java 的绝对路径加 `-version` | 真正运行服务端的 Java 版本；终端默认 Java 不一定相同 |
| 服务管理 | `systemctl cat mcserver`、`systemctl show mcserver -p ExecStart -p Restart -p RestartSec` | 服务是否存在、实际启动路径、正常退出是否重新拉起 |
| 登录身份 | 服务端和代理的认证/身份转发配置、两个测试玩家的实际 UUID | 中转 IP 不作为玩家身份；代理传递的身份符合部署方式 |
| 既有插件 | 插件列表与命令帮助、命名空间调用 | 是否有其他插件占用 ban、kick、mute、restart 等入口 |
| 运行目录 | 服务 unit 的 WorkingDirectory 与插件数据目录 | 安装、数据和备份操作指向实际服务器实例 |

若目标 build 暂不可取得，先记录差异，再统一调整依赖、运行目标和验收环境；不要只换一处版本号。Java 25 为目标大版本要求，不依赖文档所称补丁版本一定已安装。[Paper 环境要求](https://docs.papermc.io/paper/getting-started/)

## 2 构建与安装

下列命令在源码、Wrapper 和构建文件到位后执行；是否已验证以 REG-STATE 为准。

Windows PowerShell，在项目根目录：

```powershell
.\gradlew.bat clean build
```

Linux/macOS，在项目根目录：

```sh
./gradlew clean build
```

构建前使用目标 JDK；依赖锁定方式与输出 JAR 名称由实际构建文件确定。交付时提供可安装的管理插件 JAR，不把 sources、javadoc 或未完成构建产物当作安装包。[Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html)

安装顺序：

1. 先在测试服务器验证，再按维护流程停止目标实例。
2. 备份现有插件 JAR、插件数据与原版封禁数据；备份放在扫描加载目录之外，避免重复加载。
3. 将本版 JAR 放入实际 `plugins/`，移走旧版同插件 JAR，保留已有配置和处罚数据。
4. 使用实际服务管理方式启动，检查加载日志；首次启动生成配置的规则以 ENG-ARCH 为准。
5. 核对命令归属、权限、公共聊天和处罚持久化，按 ENG-VERIFY 执行受影响检查。
6. 记录安装版本、实际结果和未验证项；未完成真实重启专项前不标记重启已验收。

## 3 外部守护与正常退出

采用 systemd 时，核对实际 unit 的关键设置。以下仅说明需要的行为，不是完整服务文件，也不代表已在服务器设置：

```ini
[Service]
Restart=always
RestartSec=5s
```

`Restart=on-failure` 通常不覆盖退出码为 0 的正常停服；`Restart=always` 可覆盖正常退出，但仍受 unit 的退出例外与启动频率限制影响。应检查 `RestartPreventExitStatus` 等实际设置，并通过真实专项验证。[systemd 服务说明](https://www.freedesktop.org/software/systemd/man/257/systemd.service.html#Restart=)

服务应跟踪实际前台 Java 进程；插件执行优雅停止后，该进程退出，再由 systemd 启动新的服务器进程。不要同时叠加后台自循环脚本、Paper 独立重启脚本和 systemd 多个拉起方；选择一个实际负责者并在 REG-STATE 登记。

修改 unit 后执行 `sudo systemctl daemon-reload`，按维护计划启动并核实实际配置。插件安装本身不包含调整宿主机服务的动作。

## 4 维护停服与异常处理

采用已核实的 systemd 服务名 `mcserver` 时，维护停止使用：

```sh
sudo systemctl stop mcserver
systemctl is-active mcserver
```

`systemctl stop` 触发的停止不会因 `Restart=always` 自动重新拉起；游戏内 `/stop` 是服务器进程自行退出，可能被自动拉起，不能当作同等的维护停服手段。服务名不同则使用实际名称。[systemd 服务说明](https://www.freedesktop.org/software/systemd/man/257/systemd.service.html#Restart=)

确认进程停止后再替换文件或恢复备份。维护完成使用 `sudo systemctl start mcserver`，观察服务状态、启动日志及玩家进入结果。若持续启动失败，先停止服务再定位加载或配置错误，不靠反复重启覆盖错误。

日志写盘失败时按 ENG-ARCH 的降级语义处理；已生效处罚不因日志故障回滚。处罚数据损坏或持久化失败时保留现场与原文件，停止相关变更并报告，禁止用空文件覆盖后宣称恢复成功。
