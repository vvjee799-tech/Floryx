# 真机正式版安装 · 2026-10-29

按用户要求将第四至六批改动打包为正式构建，并于 20:51 覆盖安装到 iQOO Neo8 Pro / V2302A，串号 `10AD5J08C5000UJ`。

沿用项目既有的 `scripts/retain-existing-install.init.gradle` 发布渠道：包名 `com.flowchat.app.debug`、既有证书，显示名 **Floryx**，实际为 **Release + R8、不可调试**。包名后缀保留历史安装身份，不表示本次构建开启调试。未改为新包名、生成替代证书或卸载应用。

- 版本：1.5.20（34）。
- 文件：`build/releases/phone-2026-10-29/Floryx-1.5.20-phone-20261029.apk`，18,771,607 字节。
- SHA-256：`05bd9ab0e021474bf0f30638f4fcec07bf07b842c723ed50b914b249411143c0`，与手机当前 `base.apk` 一致。
- 签名证书 SHA-256：`423042ed2303b81afcb8d6581541e0dab977297a9893b8906071fd2867681772`，与覆盖前安装包一致。

正式构建与 Release Lint 均通过；Lint 0 错误、172 警告、1 提示。安装器已实际核对名称 Floryx，勾选风险结果确认并继续安装，ADB 返回 Success。

覆盖前后 appId 10324、数据目录和首次安装时间保持一致，调试标志已去除。实际打开应用检查 Power、Root 及后台执行屏均保持开启；后台执行屏是用户在前次验收结束后已开启的状态，本次予以保留。

Root 显示“已就绪”，无障碍实际连接正常；默认助手仍为 Floryx，通过系统助手按键实际显示会话后关闭，未发送模型请求。vivo 语音识别服务保持原值，空闲时无后台执行屏和任务唤醒锁。

聚合证据位于 `build/releases/phone-2026-10-29/installation-verification.json`，构建、Lint、安装日志在同目录。本次仅进行本地打包和真机安装，没有发布新的 GitHub 版本。
