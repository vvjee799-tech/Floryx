# 真机正式版安装 · 2026-11-01

已将当前工作区打包为 Floryx 1.5.21（35），覆盖安装至用户指定的 iQOO Neo8 Pro / V2302A，串号 `10AD5J08C5000UJ`。

沿用既有发布渠道的 `scripts/retain-existing-install.init.gradle`：安装身份 `com.flowchat.app.debug`、既有证书，显示名 Floryx；实际为启用 R8 的 Release 构建，`debuggable=false`。保留历史包名用于覆盖现有安装，没有生成替代签名或卸载主应用。

## 构建与验证

- 全量单元测试 554 项：553 通过、1 项既有环境用例跳过，无失败或错误。跳过项为 `SafeTarExtractorTest.rejectsLinkTraversalThroughAnEarlierDirectoryAlias`。
- Release lint：0 错误、173 警告、1 信息；Release/R8 构建通过。
- APK 名称、包名、版本、调试关闭标志和签名检查通过。
- 覆盖前后 UID `10361`、数据目录 `/data/user/0/com.flowchat.app.debug`、首次安装时间 `2026-10-31 23:41:14` 保持一致。
- 已安装 `base.apk` 的 SHA-256 与待安装文件一致。
- 正式包启动 `Status: ok`，进程运行，进程崩溃日志中无 FATAL EXCEPTION。
- 无障碍服务重新确认已与系统绑定，`Crashed services` 为空；保留原有授权列表。

本次包含答题模式、透明顶部实时输出、保留关闭图标、仅答案提示词及悬浮球图片贴合调整。功能的真机行为验证见[答题模式说明](floating-screen-question-2026-11-01.md)。正式安装后进行版本、启动与服务检查，没有使用真实模型发送新测试问题，也没有发布 GitHub Release。

## 安装包

- 文件：`build/releases/phone-2026-11-01/Floryx-1.5.21-retain-config.apk`
- 大小：18,793,703 字节。
- SHA-256：`30b005b594949b682e3c1a54d720f25c51bc46043b0cafc9f4ea7f1714a5cdb6`
- 证书 SHA-256：`423042ed2303b81afcb8d6581541e0dab977297a9893b8906071fd2867681772`

构建、签名、安装、启动及数据身份核对记录位于忽略目录 `build/releases/phone-2026-11-01/`。`installation-verification.json` 保存最终安装证据。
