# 更新日志 (Changelog)

本项目遵循[语义化版本规范](https://semver.org/lang/zh-CN/)。

## [1.0.0] - 2026-11-27

Floryx 首个正式发布版本。正式启用全新应用包名 **`com.floryx.app`** 与独立正式签名密钥，全面整理并收敛早期内部迭代，提供稳定、完整、开箱即用的 Android 端侧自主多模态 AI 智能体体验。

### 核心特性 (Added)

- **多模型对话与上下文治理**
  - 内置 ChatGPT、DeepSeek、Gemini、Claude、Qwen、GLM、Kimi、Grok、MiniMax、Doubao 共 10 家预设服务商及自定义 OpenAI 兼容接口支持，支持模型列表拉取、会话可选模型过滤与思考程度控制。
  - 原生对接 Google Gemini `streamGenerateContent` 接口，支持思考预算（`thinkingBudget` / `thinkingLevel`）与签名上下文工具续轮。
  - 适配 DeepSeek 1M 长上下文窗口与预算预留，提供 DeepSeek 官方账户余额实时查询与请求级 Token 用量看板。
  - 实现持久化任务检查点与语义历史压缩，支持中断恢复、原始证据回读（`read_task_history`）、独立本地摘要记忆，以及图片与 TXT / PDF / DOCX / XLSX / PPTX / ODT 附件解析。
- **端侧屏幕理解与自主手机操作**
  - 结合无障碍 UI 树解析（支持局部根作用域与长文档正文直读）与 Android 11+/14+ 屏幕画面截取，在自绘与非标准界面下自动协同视觉模型。
  - 提供两阶段高精度视觉点击（`tap_visual_target`）、语义目标重获取点击（`tap_ui_target`）、目标驱动自适应滚动（接近目标微调、过冲自动回调）、文本精确选区编辑、拖拽及滑块设值。
  - 支持 Shizuku（免 Root）与按需绑定 Root（libsu）双通道，具备无障碍异常自动重连与状态守卫。
- **内置 Linux 终端与 SAF 手机工作区**
  - 内置免 Root PRoot 5.1 + Alpine Linux 3.24.2 运行时（ARM64-v8a / x86_64，16 KB ELF 对齐），支持一键安装 Python、Node.js、npm、Git 与 curl（含镜像源回退）。
  - 基于 Android SAF 挂载手机本地文件夹，支持大文件字节范围有界读取、流式结构扫描、字面内容搜索、SHA-256 写回校验与冲突副本保留。
  - 完善命令生命周期与进程组清理验证，分离退出码、资源释放与业务完成语义，提供只读恢复守卫避免误拦常规排查。
- **多入口交互与焕新界面**
  - 采用全新浅/深色纸感主题与 100% Jetpack Compose 界面，包含「花开成形」启动动画、新版左侧抽屉、置顶/归档/重命名/Markdown 导出及紧凑任务状态面板。
  - 提供跨应用悬浮球与浮窗助手：支持后台操作进度展示、高危命令浮窗免切回确认，以及长按悬浮球触发「答题模式」在屏幕顶部实时流式展示结果。
  - 支持系统默认数字助手入口、快捷设置磁贴、跨应用「分享给 Floryx / 交给 Floryx」文本入口及后台任务前台保活。

### 安全与隐私 (Security)

- API Key 使用 Android Keystore 硬件加密保存，聊天历史、检查点与记忆均本地存储于 Room SQLite（Version 17），无任何私有遥测上报。
- 锁屏密码、支付确认、验证码与 `FLAG_SECURE` 受保护窗口在底层严格阻断截屏与自动化交互；切回 Floryx 主页面立即暂停手机操作。
- 后台高权限命令按任务请求确认，拒绝或停止后立即取消进程组并校验释放状态。

### 已知限制与实验功能 (Known Limitations)

- 「后台执行」独立副屏模式默认关闭，为实验特性，需 Android 15+、Root、Power 模式与无障碍权限，且部分应用不支持副屏显示。
- 账户余额与用量看板目前仅支持 DeepSeek 官方 API。
- 内置 Linux 终端仅支持 `arm64-v8a` 与 `x86_64` 设备。
- X-ASR 本地唤醒原型与 `native-voice-bridge`（替代 vivo 蓝心小 V 的 Xposed 实验桥接模块）仅随源码提供，不包含在默认 Release 正式安装包中；详见 [原生语音接管说明](docs/native-voice-bridge.md)。
