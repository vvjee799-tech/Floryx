# 命令行环境

Floryx 内置免 Root 的 Alpine Linux 用户态命令行环境，并复用 Power 模式的 Android 系统命令通道，无需额外安装 Termux。

## 使用方式

打开「侧边栏 → 设置 → **命令行环境**」：

- **Linux 工作区**：首次初始化从 APK 离线解压 Alpine Linux 基础镜像（不需要手机 Root）。点击「安装 Python · Node.js · Git」可自动配置常用开发工具链（含 pip、npm、curl、bash）；也可直接使用 `apk add --no-cache <包名>` 安装其他 Alpine 软件包。
- **Android 系统通道**：先在「设置 → 手机操作」开启 Power 模式，并连接 Shizuku 或授予 Root。通过此通道可按任务执行 Android 系统命令，权限范围取决于所选连接方式。
- **允许 AI 执行命令**：默认关闭。开启后模型获得命令工具；首次在任务中请求 Android 系统命令时，会弹出确认卡片展示当前任务与首条命令，可在本任务内允许或拒绝。

开启浮窗助手时，后台命令触发的确认请求会自动在悬浮窗展开，支持直接「允许」「拒绝」或停止任务，无需离开当前界面。

## 工作目录与输出

- 未关联手机文件夹时，Linux 默认目录 `/workspace` 按会话保留工作文件；关闭页面或重启应用不会清除文件，安装的 Alpine 软件包由本机所有会话共用。
- 每条命令均在独立的非交互 Shell 中执行，`cd` 和 `export` 不会跨命令保留；请使用 `&&` 串联或通过 `working_directory` 参数指定路径。
- 关联手机文件夹工作区后，命令默认工作目录为该工作区对应的本地执行目录 `/workspace/phone/<workspace-key>`。通过 `workspace_inputs` 按需导入手机文件，通过 `workspace_outputs` 指定需要校验写回的结果文件。详见 [手机文件夹工作区](workspace.md)。
- 单条命令超时上限为 600 秒（默认 120 秒）。AI 命令由应用在本地等待完成并实时更新耗时与输出；通知栏与面板均支持随时停止命令并清理对应进程组。
- 命令通道最多同时运行 4 条命令，单条输出保留最新 65,536 个字符，模型按页有界读取。命令状态与进程组清理状态分别跟踪。

> **安全边界提示**：PRoot 用于提供兼容的 Linux 文件系统视图，并非安全沙箱；Linux 程序仍在 Floryx 的 Android 应用权限范围内运行。内置 Linux 当前支持 `arm64-v8a` 与 `x86_64` 架构。

## 工具接口

| 工具 | 用途 |
| --- | --- |
| `terminal_status` | 读取手机基础环境信息（型号/系统/ABI/内核/解释器路径元数据）、Linux 就绪度、Power 连接状态与本会话命令状态 |
| `run_command` | 在 `linux` 或 `android` 环境执行命令，本地等待完成并返回退出状态、真实耗时与 `command_id` |
| `read_command_output` | 根据 `command_id` 与偏移量读取剩余输出分页，支持非阻塞立即观察已有输出 |
| `stop_command` | 停止正在运行的命令及其拥有的进程组 |

## 工具链源与离线构建校验

`flowchat-install-tools` 优先使用 Alpine 官方源，在连接超时或失败时自动回退镜像源，并保留发行版签名校验。

内置核心原生组件包括 Alpine Linux 3.24.2、Termux PRoot 5.1.107.98、talloc 2.5.0 与 libandroid-shmem 0.7（版本与 SHA-256 记录于 `app/src/main/assets/terminal/runtime-manifest.json`，原生二进制对齐 16 KB ELF 段以适配 Android 15+）。

如需在本地校验并恢复锁定的运行时资产：

```powershell
python tools/prepare_terminal_runtime.py --download-dir <download-dir>
```

开源许可与对应源码声明见 [third_party/terminal/README.md](../third_party/terminal/README.md) 与 [assets/terminal/NOTICE.md](../app/src/main/assets/terminal/NOTICE.md)。
