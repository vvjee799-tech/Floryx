<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/floryx-title-dark-v2.svg" />
    <img src="docs/assets/floryx-title-v2.svg" alt="Floryx — 让想法生长，让任务落地" width="900" />
  </picture>
</p>

<h2 align="center">Floryx · 移动端端侧自主多模态 AI 智能体</h2>

<p align="center"><b>让想法生长，让任务落地。</b></p>

<p align="center">
  <code>Android 8.0+ (API 26+)</code>&nbsp; • &nbsp;
  <code>Kotlin · Jetpack Compose</code>&nbsp; • &nbsp;
  <code>Version 1.5.20</code>&nbsp; • &nbsp;
  <a href="LICENSE"><code>MIT License</code></a>
</p>

<p align="center">
  <a href="#overview">项目概述</a>&nbsp; · &nbsp;
  <a href="#features">特性矩阵</a>&nbsp; · &nbsp;
  <a href="#use-cases">应用场景</a>&nbsp; · &nbsp;
  <a href="#download">快速下载</a>&nbsp; · &nbsp;
  <a href="#getting-started">上手指南</a>&nbsp; · &nbsp;
  <a href="#safety">安全机制</a>&nbsp; · &nbsp;
  <a href="#development">架构开发</a>&nbsp; · &nbsp;
  <a href="#documentation">文档索引</a>
</p>

---

<a id="overview"></a>

## 📖 项目概述

**Floryx** 是一款真正面向 Android 系统深度定制的**端侧全自主智能体（Autonomous Mobile Agent）**。

它突破了传统 AI 聊天软件仅停留在“文字问答”的局限，深度融合了 Android 底层系统能力：

- 拥有**看懂屏幕的双眼**：同时集成系统 UI 控件树解析与视觉大模型，支持 Android 11+ 全屏及 14+ 独立应用窗口裁剪，动态识别 Canvas、图表及自绘界面；
- 拥有**操作手机的双手**：通过 Shizuku（免 Root）与 Root 特权通道，配合自研的两阶段高精度视觉点击（Visual Tap），在无可靠控件 ID 的界面下实现像素级精准触控；
- 拥有**本地运算的代码引擎**：内置基于 PRoot 的免 Root Alpine Linux 环境，配合 Android SAF 本地文件夹工作区，可在手机端直接运行 Python、Node.js、Git 进行文件批处理与结果写回。

| 👁️ 屏幕感知与视觉 | 🦾 手机自动化操作 | 💻 本地 Linux 与工作区 |
| --- | --- | --- |
| UI 节点树深度解析 | Shizuku / Root | PRoot + Alpine Linux 3.24 |
| 局部高保真裁剪采样 | 两阶段精准视觉点击 | 一键安装 Python / Node.js / Git |
| 动态帧差分抗干扰 | 悬浮窗免切回审批 | SAF 手机本地文件夹双向读写 |

**🧠 模型支持**：Gemini 原生 · DeepSeek 1M · OpenAI · Claude

**🛡️ 安全核心**：Keystore 硬件加密 · 敏感页面阻断 · 本地 Room 存储

---

<a id="features"></a>

## ✨ 核心特性矩阵

### 1. 👁️ 屏幕感知与亚像素级视觉交互

- **双模态感知体系**：优先解析无障碍 UI 树；在 Unity、游戏、Flutter 等自绘页面中无缝调用多模态大模型解析截屏。
- **两阶段高精点击（Visual Tap）**：采用“全局目标粗筛 ➔ 局部高分辨率切片重验 ➔ 像素级点击/长按”两阶段流程，彻底解决直接按整屏粗略坐标点击小目标的漂移问题。
- **无损画质与局部变焦**：常见手机优先采用原分辨率无损 PNG，复杂画面智能压缩且长边上限达 3840 像素；支持小字局部百分比裁剪重采样。

### 2. 💻 内置免 Root Linux 终端与 SAF 工作区

- **开箱即用 Linux**：基于 PRoot 内置 Alpine Linux 3.24.2 运行时，无需 Root 即可离线解压，支持一键安装 Python、Node.js、Git、Bash 等工具链。
- **真机文件夹工作区**：基于 Android Storage Access Framework (SAF) 挂载手机文件夹，AI 脚本处理结果自动写回原目录并执行 SHA-256 哈希校验，冲突自动保留副本。

### 3. 🧠 顶级大模型原生深度集成

- **Google Gemini 原生适配**：采用官方 `streamGenerateContent` 原生接口与独立 OkHttp 网络客户端，完整支持思考预算（`thinkingBudget` / `thinkingLevel`）、Thinking 摘要与签名上下文工具续轮。
- **DeepSeek 1M 上下文治理**：深度适配 DeepSeek 全系列长文本窗口，精确预留 Token 预算，后台无感异步压缩历史会话。
- **真实账户余额看板**：支持 DeepSeek 官方 API 账户余额实时查询，用量精确到每一个 Token，杜绝不准确的估算费用。

### 4. 🛡️ 沉浸式浮窗与严苛的安全闭环

- **免切回浮窗直接确认**：后台执行系统高危命令时，悬浮窗自动展开显示将要运行的命令与影响范围，一键允许或拒绝，无需离开当前工作界面。
- **随时接管保护**：手机操作期间切回 Floryx 主应用立即暂停任务；底层强行阻断密码框、支付结算与验证码界面的截屏与自动化交互。
- **硬件级私钥加密**：所有 API Key 均存储在系统的 Android Keystore 硬件安全模块，聊天记录与记忆库仅存本机 Room SQLite。

---

<a id="use-cases"></a>

## 📱 应用场景

| 场景 | 典型指令 | 智能体动作分解 |
| :--- | :--- | :--- |
| **手机自动化排查** | “查看当前前台应用，并在设置里把音量和亮度调整到 50%” | 读取设备状态 ➔ 调用系统通道 ➔ 精准完成设置 ➔ 校验结果并汇报 |
| **本地数据与脚本处理** | “读取工作区中的 `data.csv`，用 Python 统计各类别平均值，绘制趋势图保存到工作区” | 挂载工作区 ➔ 调用内置 Python 环境执行脚本 ➔ 生成图表回写手机文件夹 |
| **视觉辅助跨应用** | “看看屏幕上的这张票据截图，提取关键编号并复制到剪贴板” | 截取目标窗口 ➔ 局部高清采样 ➔ 提取结构化数据 ➔ 写入系统剪贴板 |
| **后台长周期任务** | “在后台从这批 Git 仓库抓取提交记录，有异常直接通知我” | 终端后台运行 ➔ 悬浮窗实时滚屏 ➔ 遇到关键节点触发浮窗交互确认 |

---

<a id="download"></a>

## 📦 快速下载

从 [GitHub Releases 页面](https://github.com/vvjee799-tech/Floryx/releases/latest) 下载经过全量单元测试与真机验证的构建：

- **正式版安装包**：[⬇️ 下载 Floryx 1.5.20 APK](https://github.com/vvjee799-tech/Floryx/releases/download/v1.5.20/Floryx-1.5.20-retain-config.apk)
- **架构支持**：ARM64-v8a / x86_64
- **系统要求**：Android 8.0 (API 26) 及以上
- **升级兼容**：支持直接覆盖旧版本，保留所有历史会话、工作区配置与加密 Key。

---

<a id="getting-started"></a>

## 🚀 上手指南

1. **配置大模型**：进入「设置 → 服务商设置」，填写 API Key（支持 Gemini 原生、DeepSeek、OpenAI、Claude 等），并勾选启用的模型。
2. **授予设备权限**：
   - **Power 模式**：推荐配置 [Shizuku](https://shizuku.rikka.app/)（免 Root）或授予 Root 权限；
   - **基础服务**：按系统引导开启「无障碍服务」与「悬浮窗权限」。
3. **初始化终端与工作区**：
   - 进入「设置 → 命令行环境」，开启「允许 AI 执行命令」并点击安装 Python / Node.js 运行环境；
   - 进入「设置 → 工作区」，授权一个手机常用文件夹作为本地文件交互目录。

---

<a id="safety"></a>

## 🛡️ 安全与隐私基线

- **浮窗直接确认**：后台命令执行触发确认时，悬浮窗自动展开，支持直接授权或拒绝，全程在后台平滑完成。
- **切回即暂停**：用户切回 Floryx 主应用时，所有自动化任务立刻暂停并进入等待接管状态。
- **敏感区域硬件阻断**：锁屏密码、支付确认、验证码输入界面以及 Floryx 核心设置，系统从底层阻断截屏与触控。
- **本地化隐私**：API Key 保存在系统 Android Keystore，会话与上下文仅保存在本机 Room 数据库，不上传私有遥测数据。

---

<a id="development"></a>

## 🛠️ 技术架构与开发

Floryx 采用严格的现代 Android 架构设计模式（Modern Android Architecture）：

- **UI 呈现**：100% 采用 Jetpack Compose 构建，包含「花开成形」矢量启动动效，适配深浅色与大字体排版；
- **网络与传输**：Ktor + OkHttp 双栈引擎，支持 HTTP/2、TLS 握手自愈与 SSE 流式解析；
- **终端运行时**：定制版 PRoot 5.1 + Alpine Linux 3.24 根镜像，二进制原生对齐 16KB ELF 规范以适配 Android 15+；
- **系统桥接**：Shizuku AIDL + Android AccessibilityService 双通道分发引擎；
- **数据持久化**：Room 数据库（已迭代至 Version 15，平滑迁移） + Android Keystore。

### 本地编译指令

```powershell
# 准备环境: JDK 21 + Android SDK 35
# 在 local.properties 中配置 sdk.dir 路径后执行：

# 运行全量单元测试 (520+ 测试用例)
.\gradlew.bat :app:testDebugUnitTest

# 编译开发版 APK
.\gradlew.bat :app:assembleDebug
```

生成文件路径：`app/build/outputs/apk/debug/app-debug.apk`。

---

<a id="documentation"></a>

## 📑 技术文档索引

- 📱 **屏幕与视觉**：[视觉定位点击](docs/visual-tap-2026-10-26.md) · [视觉精度校验](docs/visual-precision-2026-10-26.md) · [动态画面识别](docs/screen-vision-motion-2026-10-25.md)
- 💻 **终端与工作区**：[内置 Linux 终端说明](docs/terminal.md) · [手机文件夹工作区](docs/workspace-2026-10-25.md) · [附件规范](docs/attachments.md)
- 🧠 **模型与上下文**：[Gemini 原生协议适配](docs/gemini-adaptation-2026-10-27.md) · [DeepSeek 1M 上下文](docs/deepseek-context-2026-10-26.md) · [任务检查点机制](docs/context-checkpoints.md)
- 🎨 **界面与交互**：[浮窗后台直接审批](docs/floating-confirmation-2026-10-26.md) · [花开成形启动页说明](docs/bloom-splash-2026-10-27.md) · [用量与余额查询](docs/usage-balance-2026-10-24.md)

---

## ⚖️ 开源协议与致谢

Floryx 基于 **[MIT License](LICENSE)** 开源。

### 核心项目致谢

- **[OpenAI Codex](https://github.com/openai/codex)**：启迪了任务检查点与长历史滑动压缩机制。
- **[Operit](https://github.com/AAswordman/Operit)**：为基于 PRoot 的 Android Linux 环境分层与原生资产打包提供了思路。

第三方开源组件与原生库许可详见 [NOTICE](NOTICE)。
