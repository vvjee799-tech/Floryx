<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/floryx-title-dark-v2.svg" />
    <img src="docs/assets/floryx-title-v2.svg" alt="Floryx — 让想法生长，让任务落地" width="900" />
  </picture>
</p>

<h2 align="center">Floryx · Android 端侧自主多模态 AI 智能体</h2>

<p align="center"><b>让想法生长，让任务落地。</b></p>

<p align="center">
  <code>Version 1.0.0</code>&nbsp; • &nbsp;
  <code>Android 8.0+ (API 26+)</code>&nbsp; • &nbsp;
  <code>Kotlin · Jetpack Compose</code>&nbsp; • &nbsp;
  <a href="LICENSE"><code>MIT License</code></a>
</p>

<p align="center">
  <a href="#overview">项目定位</a>&nbsp; · &nbsp;
  <a href="#features">核心特性</a>&nbsp; · &nbsp;
  <a href="#download">快速下载</a>&nbsp; · &nbsp;
  <a href="#getting-started">三步上手</a>&nbsp; · &nbsp;
  <a href="#safety">安全与隐私</a>&nbsp; · &nbsp;
  <a href="#architecture">技术架构</a>&nbsp; · &nbsp;
  <a href="#documentation">文档导航</a>
</p>

---

<a id="overview"></a>

## 📖 项目定位

**Floryx** 是一款专为 Android 打造的**端侧全自主多模态 AI 智能体（Autonomous Mobile Agent）**。

不同于只停留在文字聊天的 AI 客户端，Floryx 将多模型推理、屏幕视觉理解、系统级交互操作、本地 Linux 运行时与手机文件工作区融合进同一条任务执行链路：说清目标，即可在单次任务内完成观察、定位、输入、运算、校对与结果汇报。

| 🧠 多模型与上下文治理 | 👁️ 屏幕感知与自主操控 | 💻 本地 Linux 与工作区 | 🛡️ 多入口与安全闭环 |
| --- | --- | --- | --- |
| 10 家主流预设 + 自定义接口 | 无障碍 UI 树 + 画面截取 | PRoot 5.1 + Alpine 3.24.2 | Keystore 硬件加密密钥 |
| Gemini 原生思考与签名续轮 | 两阶段高精视觉点击 | 一键安装 Python / Node.js / Git | 密码与支付敏感页底层阻断 |
| DeepSeek 1M 窗口与余额看板 | 目标驱动自适应防过冲滚动 | SAF 文件夹挂载与字节范围读取 | 浮窗免切回审批与随时接管 |
| 任务检查点与语义压缩 | Shizuku（免 Root）/ 按需 Root | SHA-256 校验写回与冲突保留 | 聊天 / 浮窗 / 助手 / 分享统一接入 |

---

<a id="features"></a>

## ✨ 核心特性

### 1. 🧠 多模型原生集成与长上下文治理

- **丰富的模型连接**：内置 **ChatGPT、DeepSeek、Gemini、Claude、Qwen、GLM、Kimi、Grok、MiniMax、Doubao** 共 10 家预设服务商图标与配置模板，同时支持任意自定义 OpenAI 兼容接口；支持模型列表拉取、会话级可选模型筛选与思考程度调节。
- **Google Gemini 原生协议**：直接对接官方 `streamGenerateContent` 与独立 OkHttp 客户端，完整支持思考预算（`thinkingBudget` / `thinkingLevel`）、推理摘要及签名工具续轮。
- **DeepSeek 1M 上下文与余额看板**：深度适配 DeepSeek 全系列百万上下文窗口与预算预留，并提供官方 API 账户余额与请求级 Token 用量统计（含缓存命中统计）。
- **任务检查点与语义压缩**：参考结构化检查点机制对长对话进行无损边界摘要与压缩，持久化任务状态与进度，支持中断恢复与原文证据按需回查（详见 [检查点与压缩说明](docs/context-checkpoints.md)）。
- **多模态与文档解析**：支持图片（JPG / PNG / WebP）与 TXT、PDF、DOCX、XLSX、PPTX、OpenDocument 等格式附件解析，支持会话置顶、归档、重命名与 Markdown 导出（详见 [附件规范](docs/attachments.md)）。

### 2. 👁️ 屏幕感知与像人一样的自主手机操作

- **双模态页面理解**：优先通过系统无障碍读取完整控件树（支持局部根作用域与长文档正文提取）；在自绘界面、图表或缺少可靠控件 ID 的页面中调用多模态大模型理解屏幕画面（Android 11+ 支持无障碍截图，Android 14+ 支持独立窗口截取）。
- **两阶段高精度视觉点击（Visual Tap）**：采用“全局选区粗筛 ➔ 局部高分辨率裁剪重验 ➔ 精准点击/长按”流程，降低整屏粗略坐标点击小目标的偏移误差。
- **目标驱动自适应滚动**：根据页面反馈动态调整滑动距离与节奏，在目标接近边缘时微调，过冲后自动回调，减少固定幅度盲目翻页。
- **精确交互与故障自愈**：支持语义定位点击、光标/选区编辑、容器滚动、拖拽与滑块设值；支持无障碍异常重连与任务恢复门控，同时兼容 **Shizuku（免 Root）** 与 **Root（libsu 按需绑定）** 通道。

### 3. 💻 内置免 Root Linux 与 SAF 手机工作区

- **开箱即用 Alpine Linux**：基于定制版 PRoot 5.1 + Alpine Linux 3.24.2（支持 **ARM64-v8a / x86_64**，原生二进制已对齐 16 KB ELF 页大小），无需 Root 即可初始化，一键安装 Python、Node.js、npm、Git 与 curl（详见 [命令行环境说明](docs/terminal.md)）。
- **真机文件夹工作区**：通过 Android SAF 挂载手机本地文件夹，支持**大文件字节范围有界读取（`byte_offset` / `max_bytes`）**、流式文本结构扫描与字面搜索；脚本处理结果写回原目录时校验 SHA-256，遇到外部改动自动保留冲突副本（详见 [手机文件夹工作区说明](docs/workspace.md)）。
- **可靠命令生命周期**：明确区分进程退出码、资源/进程组释放与业务目标核验，支持后台命令实时输出查看、600 秒长任务上限与随时停止。

### 4. 📱 多入口交互与焕新体验

- **纸感界面与花开动效**：100% Jetpack Compose 构建，适配浅色/深色主题、中英双语、大字体排版与流畅高刷启动动画；紧凑左侧抽屉整合快捷入口、会话归档与单行干净状态提示。
- **悬浮球与答题模式**：跨应用运行期间可在悬浮球查看简洁进度与耗时，高危操作支持在浮窗直接允许或拒绝；开启「答题模式」后，长按悬浮球截取当前画面并在屏幕顶部暖黑透明横幅实时输出答案。
- **系统助手与跨应用分享**：支持注册为系统默认数字助手（`ACTION_ASSIST` / `VoiceInteractionService`）、添加系统快捷设置磁贴，并支持通过系统「分享给 Floryx / 交给 Floryx」划词或转发文本快速总结与入草稿。
- **后台保活与中断恢复**：任务可在切离聊天页后通过前台服务继续执行并从通知栏停止；应用被系统中断后可保留证据并由用户选择继续或结束。

### 5. 🎙️ 原生语音接管（实验：替代 vivo 蓝心小 V）

- **复用原生唤醒与音色**：在兼容设备上通过独立 Xposed 桥接模块（[`native-voice-bridge/`](native-voice-bridge)）接入 vivo 原生「蓝心小 V」，直接复用原厂低功耗硬件唤醒词、语音转写与播报音色，将语音任务交接给 Floryx 执行（支持口述改口、同会话短跟进、跨应用操作时卡片自动让位与防双助手抢答）。

> **重要声明（Compatibility & Disclaimer）**
> - **严格实验范围**：该功能目前为实验模块，需设备具备 **Root + LSPosed（Xposed API 102）**，且仅适配官方签名的 `com.vivo.ai.copilot` **`6.11.1.1`**（`versionCode = 6110101`）；不适配其他机型或系统版本。
> - **不随 Release 成品分发**：默认正式版 `Floryx-v1.0.0.apk` **不包含**此桥接接收端点，因此 `v1.0.0` Release 页面不附带桥接成品包；有需求的用户可参考 [原生语音接管说明与自编译指南](docs/native-voice-bridge.md) 从源码配对编译 Debug 版本。
> - **免责声明**：本模块与 vivo / iQOO 官方无任何隶属或合作关系，系统升级可能导致兼容失效，请自行评估风险后使用。

> **其他实验功能与支持边界**
> - **后台独立副屏执行**：默认关闭的实验特性，需 Android 15+、Root、Power 模式与无障碍权限，且部分应用不支持副屏渲染。
> - **用量与余额看板**：账户余额查询目前仅支持 DeepSeek 官方 API。
> - **X-ASR 离线唤醒原型**：仅在显式配置外部 `sherpa-onnx` 运行时构建参数时参与 Debug 编译，**不包含在默认 Release 正式包中**。

---

<a id="download"></a>

## 📦 快速下载

从 [GitHub Releases](https://github.com/vvjee799-tech/Floryx/releases/latest) 获取正式版安装包与校验文件：

- **正式版 APK**：[⬇️ 下载 Floryx v1.0.0 (`Floryx-v1.0.0.apk`)](https://github.com/vvjee799-tech/Floryx/releases/download/v1.0.0/Floryx-v1.0.0.apk)
- **SHA-256 校验文件**：[SHA256SUMS.txt](https://github.com/vvjee799-tech/Floryx/releases/download/v1.0.0/SHA256SUMS.txt)
- **包名与版本**：`com.floryx.app` · `v1.0.0 (1)`（Release + R8 构建，非调试包）
- **系统与架构**：Android 8.0 (API 26) 及以上（屏幕截图需 Android 11+，内置 Linux 支持 `arm64-v8a` / `x86_64`）

> 💡 若你此前安装过早期 `com.flowchat.app` 或 `com.flowchat.app.debug` 测试包，`v1.0.0` 正式版使用全新独立包名 **`com.floryx.app`** 与正式签名，可在手机上**共存安装、互不覆盖**。

---

<a id="getting-started"></a>

## 🚀 三步上手

1. **配置大模型服务商**
   打开「侧边栏 → 设置 → 服务商」，选择预设服务商（如 DeepSeek、Gemini、OpenAI、Claude 等）或自定义兼容接口，填入 API Key 并勾选需要使用的模型。
2. **按需授予设备与悬浮权限**
   - 仅纯对话、文件分析与 Linux 脚本处理：**无需**开启设备操控权限；
   - 如需操作手机界面：在「设置 → 手机操作」中开启无障碍服务，并配置 [Shizuku](https://shizuku.rikka.app/)（免 Root）或授予 Root；
   - 如需跨应用进度条、后台浮窗审批或长按答题：在「设置 → 浮窗助手」开启悬浮窗权限。
3. **初始化终端与工作区（可选）**
   - 在「设置 → 命令行环境」初始化内置 Alpine Linux，并按需安装 Python / Node.js / Git；
   - 在「设置 → 工作区」授权一个手机常用文件夹用于 AI 读写文件。

---

<a id="safety"></a>

## 🛡️ 安全与隐私基线

- **硬件级私钥加密**：API Key 等凭据通过 Android Keystore 加密保存，不上传任何开发者服务器或第三方遥测。
- **纯本地数据存储**：对话记录、任务检查点、摘要记忆与用量记录仅保存于本机 Room SQLite 数据库。
- **敏感界面底层阻断**：锁屏密码框、支付确认、验证码输入与系统 `FLAG_SECURE` 保护窗口禁止截屏与自动化交互。
- **随时接管与任务授权**：手机操作期间切回 Floryx 主页面立即暂停任务；高风险系统命令按任务粒度请求确认，不在浮窗外默默扩大权限范围。
- **安全汇报**：漏洞披露与安全策略见 [SECURITY.md](SECURITY.md)。

---

<a id="architecture"></a>

## 🛠️ 技术架构与本地构建

- **UI 与主题**：Kotlin + Jetpack Compose + Material 3（动态浅/深色、矢量启动动画）
- **状态与持久化**：Hilt 依赖注入 + Room SQLite（Database v17，完整增量迁移）+ DataStore Preferences
- **网络与协议**：Ktor + OkHttp 双引擎、SSE 流式解析、Kotlinx Serialization
- **端侧运行时**：PRoot 5.1.107.98 + talloc 2.5.0 + libandroid-shmem 0.7 + Alpine Linux 3.24.2（16 KB ELF 对齐）
- **系统桥接**：AccessibilityService + Shizuku AIDL + libsu Root Service
- **质量保障**：159 个单元测试套件 / 1,108 项本地单元测试 + Android Lint 增量基线守卫（[tools/check_lint_delta.py](tools/check_lint_delta.py)）

### 本地编译与验证

环境要求：**JDK 21**、**Android SDK 35**（在 `local.properties` 配置 `sdk.dir` 或设置 `ANDROID_HOME`）。

```powershell
# 运行全量本地单元测试
.\gradlew.bat :app:testDebugUnitTest

# 运行 Android Lint 与增量基线检查
.\gradlew.bat :app:lintDebug :app:lintRelease
python tools/check_lint_delta.py

# 编译 Debug APK
.\gradlew.bat :app:assembleDebug
```

正式 Release 构建需要本地签名配置 `keystore.properties`（已被 `.gitignore` 忽略），完整构建与校验步骤见 [正式版发布指南](docs/releasing.md)。

---

<a id="documentation"></a>

## 📑 文档导航

- 💻 [内置 Linux 命令行环境](docs/terminal.md)
- 📂 [手机文件夹工作区（SAF 与有界读取）](docs/workspace.md)
- 🧠 [会话检查点与长历史压缩](docs/context-checkpoints.md)
- 🖼️ [图片与文档附件解析](docs/attachments.md)
- 🗂️ [本地摘要式记忆机制](docs/memory.md)
- 🎙️ [原生语音接管：替代 vivo 蓝心小 V（实验）](docs/native-voice-bridge.md)
- 📦 [正式版构建与发布流程](docs/releasing.md)
- 📋 [更新日志 (CHANGELOG)](CHANGELOG.md)

---

## ⚖️ 开源协议与致谢

Floryx 基于 **[MIT License](LICENSE)** 开源。

### 致谢

- **[OpenAI Codex](https://github.com/openai/codex)**：为任务检查点与长对话滑动压缩机制提供了核心设计灵感。
- **[Operit](https://github.com/AAswordman/Operit)**：为基于 PRoot 的 Android Linux 环境分层与原生资产打包提供了重要参考。
- **[Roubao](https://github.com/Turbo1123/roubao)**：为 Android 端侧设备自动化设计提供了有益启发。
- **[Shizuku](https://github.com/RikkaApps/Shizuku)** & **[libsu](https://github.com/topjohnwu/libsu)**：提供可靠的免 Root / Root 系统通道能力。

第三方开源组件与 GPL/LGPL 对应源码说明详见 [NOTICE](NOTICE) 与 [assets/terminal/NOTICE.md](app/src/main/assets/terminal/NOTICE.md)。
