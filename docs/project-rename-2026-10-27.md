# Floryx 项目名称统一记录

日期：2026-10-27。随 **1.5.19（33）** 完成项目名称统一。

应用在 1.5.7 已改名为 Floryx。本次将项目、GitHub 仓库和后续发布资料统一为 Floryx，不再以旧名作为当前项目名称。

| 范围 | 原名称 | 当前名称或处理方式 |
| --- | --- | --- |
| GitHub 仓库 | `vvjee799-tech/FlowChat` | `vvjee799-tech/Floryx` |
| Git remote | `https://github.com/vvjee799-tech/FlowChat.git` | `https://github.com/vvjee799-tech/Floryx.git` |
| Gradle 根项目 | 已设为 Floryx | 确认继续使用 `Floryx` |
| 正式应用 / 开发版 | 已显示 Floryx / Floryx Dev | 继续使用这两个名称 |
| 发布产物与下载链接 | 产物已使用 Floryx，仓库路径仍是旧名 | `Floryx-1.5.19-retain-config.apk`，链接使用新仓库路径 |
| 设计规范目录 | `design-system/flowchat/` | `design-system/floryx/`，同步文档引用 |
| 第三方声明 | 旧项目名标题 | 标题使用 Floryx，第三方许可正文原样保留 |
| 停用的搜索代理源码 | `flowchat-search-proxy` / `flowchat-search` | `floryx-search-proxy` / `floryx-search`；本次没有部署该服务 |
| 搜索代理构建参数 | `flowchatSearchProxyUrl` | 主参数为 `floryxSearchProxyUrl`，旧参数作为兼容别名保留 |

## 保留的兼容标识

以下内容属于既有安装或历史证据的技术标识，不作为当前项目品牌使用，本次不做破坏性迁移：

- 发布渠道的 Android 安装标识仍是 `com.flowchat.app.debug`，标准源码 namespace / applicationId 仍是 `com.flowchat.app`。发布包为关闭调试、经 R8 优化的 Release 构建，显示名称为 Floryx。
- 继续使用既有安装证书，SHA-256 为 `423042ed2303b81afcb8d6581541e0dab977297a9893b8906071fd2867681772`。未生成或更换用户发布渠道的签名密钥。
- 保留已注册的无障碍服务组件、Application 类、AIDL、现有 Kotlin 包与相关资源标识，避免系统授权或组件引用失效。
- 保留数据库、偏好、Keystore 别名和终端内已有命令等持久化标识，避免丢失聊天、模型密钥、权限或已有工作目录。
- LICENSE 中原有贡献者版权署名、历史 APK 文件名、截图路径及技术验证记录按证据保留。旧仓库的历史标签和发布附件不重写、不删除。
- 当前本地工作目录仍为 `E:\Flowchat`，保留已绑定的 Codex 工作区和本机工具路径。新克隆默认目录将是 `Floryx`；本次未移动整个工作区或修改其他聊天的项目路径。

## 发布与后续约定

后续新文档、安装包、提交说明、发布标题及仓库链接统一使用 **Floryx**。规范打包入口为 `scripts/build-release.ps1`，完整发布步骤见[发布流程](releasing.md)。

GitHub 仓库已改为 `vvjee799-tech/Floryx`，仓库 ID 仍为 `1277327597`，默认分支仍为 `main`，原提交历史、标签和发布记录保留。本地 `origin` 已指向新地址。改名与 1.5.19 发布在同一轮完成，没有新建替代仓库。
