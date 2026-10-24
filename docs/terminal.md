# 命令行环境

FlowChat 1.5.0 内置 Linux 命令行，并复用 Power 模式的 Android 系统命令通道，不需要另装 Termux。

## 使用

打开侧边栏 → 设置 → **命令行环境**。

- **Linux 工作区**：首次打开会从 APK 解压 Alpine Linux；基础环境可离线准备，不需要手机 Root。点击“安装 Python · Node.js · Git”下载开发工具，包含 pip、npm、curl、bash。也可以使用 `apk add --no-cache 包名` 安装其他 Alpine 软件。
- **Android 系统**：先在设备助手开启 Power，并连接 Root 或 Shizuku。这里执行真正的 Android 系统命令；权限取决于所选连接。
- **允许 AI 执行命令**：默认关闭，开启后模型获得命令工具。AI 第一次执行系统命令时，会显示当前任务与首条命令，用户可允许本次任务或拒绝；拒绝后该任务重试也不会执行或重复询问。

在聊天中说“用 Python 计算并保存结果”“检查 Node.js 和 Git 是否可用”，AI 会按需使用命令工具。面板用于管理环境、查看执行记录和停止命令，不提供手动命令输入。需要当前模型支持工具调用。Linux 中的 root 是 PRoot 模拟的身份，不是手机超级用户。

## 工作目录与输出

未关联手机文件夹时，Linux 默认目录 `/workspace` 按会话保留文件；关闭面板、切换应用或重新打开 FlowChat 不会清除文件。Linux 发行版与安装的软件由本机所有会话共用。每条命令启动新的非交互 Shell，`cd` 和 `export` 不跨命令保留；在同一条命令中串联操作，或通过工具的 `working_directory` 指定目录。

在设置中选择手机文件夹工作区后，命令默认进入 `/workspace/phone/<workspace-key>` 本地执行目录。通过 `workspace_inputs` 按需传入手机文件，通过 `workspace_outputs` 指定需要写回的输出；两者都是相对所选文件夹的路径。只有命令成功且文件保存校验通过，结果才出现在 `workspace_saved`；失败原因在 `workspace_errors`。停止后的输出与保存失败的结果保留在本地，可继续处理或在工作区设置中补存。详细说明见 [手机文件夹工作区](workspace-2026-10-25.md)。

每条命令最长 600 秒。后台执行有前台服务通知，面板和通知都可停止命令；AI 任务结束或用户停止回复时会清理该任务尚未完成的命令。停止和超时会清理对应进程组。它用于运行脚本和批处理，当前不提供交互式终端、密码输入或永久后台服务。

从 1.5.2 开始，AI 命令由应用本地等待完成；等待期间持续更新同一条执行记录的输出和真实耗时，不为查询运行状态反复调用模型。完成、失败、停止或超时后再把结果交给模型；`elapsed_seconds` 提供实际经过的秒数。剩余长输出仍可按页读取。同一用户消息下的多段思考在聊天中合并为一个面板，已有记录也采用此显示方式，原始消息及服务商推理数据保持不变。

面板显示输出、退出码、失败、超时和停止状态。每个执行通道最多同时运行 4 条命令，内存中保留最多 24 条记录；单条输出保留最新 65,536 个字符。模型每次最多读取 12,000 个字符，并通过偏移量继续读取。进程与命令面板记录不跨应用进程重启恢复；聊天中的工具结果会保存。

命令及读取的输出会发送给当前选择的模型。PRoot 用于兼容 Linux 文件系统和程序，不是安全沙箱；Linux 程序仍在 FlowChat 的 Android 应用权限范围内运行。不同会话的工作目录用于整理文件，不提供会话间的安全隔离。ARM64、x86_64 可使用内置 Linux；32 位设备仍可使用现有 Power 命令。

## 开发工具下载

`flowchat-install-tools` 会使用 Alpine 官方源，并在连接失败或单次尝试达到总时限后重试清华、中科大镜像。每个源最多尝试 120 秒，仍使用发行版自带的签名密钥校验软件包，不修改用户的仓库配置。用于 AI 安装时请将 `timeout_seconds` 设为 `600`。失败会显示实际输出，可在网络恢复后重试。

## 工具接口

| 工具 | 用途 |
| --- | --- |
| `terminal_status` | Linux 准备情况、开发工具与 Power 连接状态 |
| `run_command` | 选择 `linux` 或 `android`，本地等待命令结束，返回退出状态、真实耗时和命令 ID |
| `read_command_output` | 使用命令 ID 和 `next_offset` 读取剩余输出，有 `has_more` 时继续翻页；未结束的命令由应用本地等待 |
| `stop_command` | 停止命令和对应进程组 |

命令 ID 的读取和停止都验证会话归属。系统命令的任务授权不替代 Android / Root / Shizuku 的系统授权。

## 构建与来源

内置 Alpine 3.24.2、Termux PRoot 5.1.107.98、libtalloc 2.5.0 和 libandroid-shmem 0.7。版本、下载地址及 SHA-256 位于 `app/src/main/assets/terminal/runtime-manifest.json`。ARM64 和 x86_64 原生文件采用 16 KB ELF 段对齐；执行文件放在 APK 原生库目录并由系统提取，以支持新版 Android 的执行限制。

恢复已锁定的运行文件（不会自动升级版本）：

```powershell
python tools/prepare_terminal_runtime.py --download-dir D:/download/FlowChat-runtime
```

Linux 基础包的 `.rootfs` 扩展名保留原始 gzip 内容，避免 Android 资源打包器改名或自动解压。首次初始化校验 SHA-256，在临时目录验证路径、链接和解压大小后激活；更新应用保留已有发行版及用户文件。

开源许可和原生组件源码说明见 [third_party/terminal/README.md](../third_party/terminal/README.md)。实现参考 [Operit](https://github.com/AAswordman/Operit) 与 [OperitTerminalCore](https://github.com/AAswordman/OperitTerminalCore) 的环境分层思路；未引入 Operit 的 Kotlin 实现或其终端 SDK。

## 验证

- 单元测试：命令参数、工具开关、输出分页与解压边界。
- `TerminalRuntimeDiagnosticTest`：MuMu 实际执行、工作文件保留、Python / Node.js / npm / Git、退出码、输出截断、超时、取消及 Root 进程组清理。
- `TerminalAgentDiagnosticTest`：本地 HTTP 模拟模型驱动实际运行 20 秒的命令，验证等待期间只有一次模型请求、完成后仅再请求一次总结、真实耗时和进度、合并思考、用户停止、任务授权、拒绝重试拦截及设置面板。不会调用付费模型。
- `TerminalArchiveDiagnosticTest`：Android 新环境解压与实际运行、符号链接路径检查。
- `TerminalRootDiagnosticTest`：实际 Root 通道与包管理器参数兼容。
- Debug 专用 `TerminalShellProbe`：以 ADB Shell UID 2000 通过 AIDL 验证 Shizuku 服务命令实现。此测试不替代真实 Shizuku 应用的连接验证。

诊断测试默认跳过；安装 Debug 与 AndroidTest APK 后，在明确的 MuMu 序列号上通过 `am instrument` 指定测试类和 `-e runTerminalDiagnostics true` 执行。Root 测试另需 `-e runRootTerminalDiagnostics true` 并已为 FlowChat Dev 授权。
