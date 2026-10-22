# 图片与文档附件

在输入框下方点“＋”选择图片或文件。一次附加一个；可只发附件，也可加文字。图片有缩略图，点开可预览，发送前可移除或替换。

图片通过 Android 解码后，按 EXIF 方向校正，最长边缩至 2048 像素以内，去除原始元数据，保存成不超过 2 MiB 的 PNG 或 JPEG。透明 PNG 在大小允许时保留透明度；GIF 只使用首帧。JPG、PNG、WebP 已在 Android 真正解码验证，其他格式取决于设备系统的解码能力。原始文件大小上限仍为 20 MiB。

图片保存在应用私有目录，聊天数据库仅记录路径和类型；重开页面、重启应用后可继续查看。最近五轮用户消息中包含的图片会用于追问，删除会话也会清理对应图片。移除或替换尚未发送的图片会清理其临时副本。

图片直接作为视觉内容传给所选模型，不会只发送文件名或把图片当空文本。支持 OpenAI 兼容 Chat Completions、OpenAI Responses 和 Claude 原生 Messages 格式；所选模型和上游服务必须实际支持视觉输入。不会自动切换用户模型。

DeepSeek 的快捷模型菜单已补充 `deepseek-flash`，本次使用这个型号完成真实识图验证。已有会话的模型选择保持原值，需要时可在输入框下方手动切换。

文档维持文字提取方式：TXT、Markdown、CSV、JSON、普通文字 PDF、DOCX、XLSX、PPTX、OpenDocument。扫描 PDF 不提供 OCR；图片的文字理解由视觉模型完成。附件内容不会作为摘要记忆提取输入。

协议参考：[OpenAI 图片输入](https://developers.openai.com/api/docs/guides/images-vision)、[Claude Vision](https://platform.claude.com/docs/en/build-with-claude/vision)、[DeepSeek 图像理解](https://api-docs.deepseek.com/zh-cn/guides/vision/)。

验证：三种协议的请求结构、图片历史、工具调用延续和缺图保护均有自动化检查；MuMu 使用本机模拟 HTTP 服务验证真实读文件、原生选择器、Compose 界面、持久化及图片字节。另经用户授权，使用应用内已有 DeepSeek 配置进行了少量真实文本和合成图片识图请求；未导出密钥或用户聊天。
