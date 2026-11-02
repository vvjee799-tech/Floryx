# 系统助手入口：第二批

本批承接应用级任务运行时与 Root 接入基础，目标是减少每次打开完整聊天的成本，让用户可以从系统入口发起和查看任务。目标真机仍为 iQOO Neo8 Pro / Android 16 / OriginOS 6，开发包为 `com.flowchat.app.debug`（Floryx Dev）。

## 已实现的行为

- 在“设置 → 手机操作”增加默认数字助手选择、面板试用、添加快捷设置磁贴入口。状态通过系统角色和活动助手服务读取，打开选择器不视为授权成功。
- Android 12 及以上声明 VoiceInteractionService 与 VoiceInteractionSessionService。系统常驻的入口在独立 `:assistant_entry` 进程中，不注入聊天运行时，也不监听麦克风、轮询页面或启动 Root。用户唤起的会话界面在主进程复用同一个 ChatTaskRuntime。
- Android 8–11 保留 ACTION_ASSIST 活动入口；不启用要求同包语音识别服务的旧版 VoiceInteractionService。本批为文字助手，现代服务的识别器元数据显式为空，不注册或替换用户现有的语音识别器。
- 系统会话、ACTION_ASSIST、快捷磁贴和任务通知共用轻量原生面板。面板可提交请求、显示流式输出、停止任务、批准或拒绝已有设备操作确认，并打开完整聊天。
- 发送成功后关闭面板，执行仍归应用运行时所有。再次唤起可查看正在进行的任务。查看面板不切换完整聊天的会话，不消费其草稿或附件，也不触发完整聊天的手机接管逻辑。
- 面板显示期间暂时隐藏重复的悬浮输出；执行手机界面操作或截图之前让出面板。租约可重复释放，关闭界面会取消界面订阅，不取消任务。
- 冷进程默认视为完整聊天尚未进入前台。系统助手先发起的任务在用户首次打开完整聊天时同样执行接管规则；待确认状态保留原有例外。
- 锁屏状态不展示任务内容；快捷磁贴先要求系统解锁。没有添加录音权限、常开唤醒服务、开机自启或电池优化豁免。

## 当前页面上下文

仅系统助手会话使用 Android 提供的 AssistStructure / AssistContent；普通面板、通知和磁贴不自行抓取背后的应用。

页面快照只保留在本次面板的内存中，最多遍历 500 个节点、8 个窗口和 8,000 个字符，处理移至后台线程并限制等待时间。排除自身应用、不可见或屏幕外节点、被阻止的子树，以及文本/网页/数字密码框。网页地址移除查询、片段和用户信息；不读取应用提供的任意 Bundle。

用户可以先预览，再勾选“附带页面文字”。默认不选；快照 60 秒后过期，过期时保留未发送输入并要求重新获取或取消附带。发送后作为该条用户消息的附件记录，明确标记来源、历史快照和不可信页面内容。它不是持续观察，也不应被当作新的用户指令。关闭面板清除待发送页面；不持久保存未选中的上下文。本批不读取系统会话截图。

## 独立显示探测的边界

使用独立测试创建私有、仅本应用内容的虚拟显示，绘制合成画面并从 ImageReader 核对真实像素，随后释放 Presentation、显示和图像资源。没有镜像用户屏幕，也没有启动个人应用。

在 MuMu / Android 15 和真机 / Android 16 的初次探测中，均得到：

| 探测项 | 结果 |
| --- | --- |
| 系统声明支持副屏活动 | 是 |
| 创建私有虚拟显示 | 通过 |
| 本应用内容绘制与取帧 | 通过 |
| 普通应用身份对该显示的活动启动预检查 | false |
| 显示资源释放，原显示列表恢复 | 通过 |
| Root 辅助启动第三方应用、指定显示输入、熄屏运行 | 尚未验证 |

因此没有把“创建副屏”包装成可用的后台手机执行能力。下一阶段需要单独验证受控 Root 服务的显示所有权、启动权限、输入与截屏目标，以及第三方应用的兼容性，再决定是否连接模型工具。

## 验证设计与清理

- 单元测试覆盖外部任务的明确会话归属、草稿/附件隔离、重复提交与停止、页面过期和密码类型，以及冷启动接管规则。
- SystemAssistantDiagnosticTest 的功能验收使用本机合成 HTTP 模型和独立测试应用页面，不调用用户模型或访问个人应用。
- 真实系统会话测试临时选择助手角色，使用系统助手按键发起页面传递，核对密码、隐藏、屏幕外文字和安全窗口；结束后逐项恢复原助手角色、活动服务和语音识别器。空字符串和空值通过 Settings API 精确恢复。
- 磁贴和通知测试验证实际入口并恢复原磁贴列表。服务开关、记忆与命令开关、面板会话指针和合成会话均有持久化恢复标记，失败后也可用诊断用例恢复。
- OriginOS 使用系统启动路径和应用 ActivityLifecycleCallbacks 观察恢复，避免此前 Instrumentation.startActivitySync 的平台等待问题。独立测试页面用 Java 编写，避免独立测试 APK 缺少目标应用 Kotlin 运行库。

## 构建与验收

- 单元测试共 532 项，531 通过，1 项既有 Windows 符号链接用例跳过，0 失败。
- Debug、AndroidTest、Release/R8 构建通过；Debug Lint 为 0 错误、179 警告、1 提示。其中新增的 ViewConstructor 提示针对只在代码中创建的面板视图，不影响运行。
- 产品源码完整构建日志：`build/system-assistant-final-build.log`。后续修正仅涉及诊断入口和测试夹具，构建日志分别在 `build/system-assistant-routing-guard-build.log`、`build/system-assistant-cleanup-fixture-build.log`。
- 真机最终产品验证：`build/system-assistant-phone-final.log`，4 项通过，覆盖面板生命周期和停止、页面勾选与过期、真实系统角色/页面传递与安全窗口、私有显示绘制/取帧/释放。
- MuMu 最终批次 `build/system-assistant-mumu-final-verified.log` 中有 6 项通过：面板、页面勾选/过期、真实系统会话、安全窗口、独立显示与两项既有浮窗回归（系统会话与安全窗口合为一项）。同批磁贴/通知联合用例失败，不能将整批记为全部通过。
- 真机快捷磁贴另行通过实际系统界面点击验证，且没有开启通知权限，日志为 `build/system-assistant-phone-native-tile.log`。因此真机共 5 个不同功能用例通过；后续安装包的面板冒烟复测也通过，见 `build/system-assistant-phone-installed-smoke.log`。
- 真机使用前后逐项核对：记忆开启、设备操作功能开启、命令执行关闭，助手角色为空、活动语音助手为空，原 `com.vivo.ai.copilot/.framework.wakeup.CopilotRecognitionService` 保持一致；通知权限仍为关闭。

### 尚未通过的系统界面验证

- MuMu 快捷磁贴曾在单独用例中启动面板，但后续重复验证不能稳定通过。实际截图显示磁贴“不可用”，系统日志报告 `Unable to launch app ... AssistantTileService: user 0 is stopped`；同时用户状态查询又显示 0 为运行中。已尝试重启 Android、显式启动用户并恢复原磁贴列表，最终仍未稳定通过。真机同一产品实现的磁贴点击已通过，MuMu 兼容性问题保留为未解决项。
- MuMu 曾通过程序派发任务通知的 PendingIntent 验证了面板目标及进度状态，见 `build/system-assistant-entry-verified.log`；这与真实通知栏点击不同。OriginOS 拒绝背景应用直接派发的启动，随后通过系统通知栏寻找目标节点也超时。因此不将通知栏点击列为已通过的真机验收。
- 最后两项系统界面重试日志为 `build/system-assistant-mumu-system-ui-verified.log`、`build/system-assistant-mumu-tile-final.log`、`build/system-assistant-phone-system-ui-entry.log`，均如实保留失败结果。它们没有触发用户模型；每次均恢复通知权限、合成会话和磁贴列表。
- 真机最终 APK 哈希、助手/识别器、开关和磁贴列表的精确还原记录为 `build/system-assistant-phone-final-state.json`。最终界面截图的检查只使用合成页面。
- 聚合核对记录为 `build/system-assistant-final-results.json`：真机 5 项、MuMu 6 项通过；夹具标记已清除、任务前台服务已退出，两台设备的角色和磁贴已恢复。Debug APK SHA-256 为 `2acf90c110ad237a2c1334b4b92e84e8020780a41fb45cf76ed03efcf4ec33e3`，已与真机安装包逐字节哈希核对。

### 回归测试隔离问题与修复

一次合并运行旧 BackgroundConfirmationDiagnosticTest 时，夹具假设新建会话会自动被选中。应用级运行时已存在，因此合成的 `ROOT_ALLOW_QA run two harmless system commands` 误发到了 MuMu 中已有的 Gemini 会话。该回合产生了两段模型回复和只读 Linux 命令 `uname -a && id` 的工具记录，没有执行 Android 系统修改。它不属于本地模型验收，不能据此声称本批完全没有外部模型请求；实际外部请求不能通过本地清理撤回。

已将旧夹具改为显式选择并核对合成会话 ID，再允许发送，修复后的合并回归通过。清理工具仅接受已核对的会话和 4 个确切消息 ID，校验完整回合与任务归属，在事务内删除本轮合成消息及对应任务日志，保留原有 5 条消息；不清除整段会话或服务商。该回合没有本地用量记录，不能据此推断外部计费。备份转存至忽略目录 `build/system-assistant-misroute-backup.json`，设备端副本已清除；清理证据为 `build/system-assistant-mumu-accepted.log` 中的 `MISROUTED_ROUND_REMOVED`。

磁贴夹具还修正了现代系统对私有设置的读取限制，并使用 SystemUI 的 remove-tile / set-tiles 接口恢复实际列表，避免只改设置而未同步活动磁贴。上述早期失败不计入通过结果。真机的本地模型验收和合成页面验证未使用这个旧夹具。

## 默认助手按钮修复与真机复测

真机上曾复现点击“选择默认助手”后停留在原页面；当时未选中 Floryx，代码走 RoleManager 的角色请求分支。尚未记录该次请求的返回码，因此不能据此确定厂商拒绝的具体原因。

按钮现统一打开已在 OriginOS 验证可用的 `ACTION_VOICE_INPUT_SETTINGS`，如入口不存在或被系统拒绝，再尝试 `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`；两个入口均失败时显示手动设置提示。打开时清除过期提示，返回或恢复前台时重新读取系统角色和活动助手服务。

本次按用户最新要求直接在 iQOO 真机验收，未运行 MuMu 回归，也未更改用户已选定的 Floryx 角色：

- 从应用内实际点击按钮，成功打开系统“数字助理应用”页面，再进入候选列表，确认 Floryx Dev 为选中状态。
- 返回应用后仍显示“已选择 Floryx”。通过系统助手按键（KEYCODE_ASSIST）唤起轻量面板，核对真实系统会话为已绑定、已显示，随后关闭空面板；没有发送模型请求。
- Debug 构建与 Lint 通过，Lint 为 0 错误。备用设置入口的故障回退分支未在真机强制制造异常验证。
- 核对安装 APK 与本地构建 SHA-256 一致：`4ff62573f7eca0086613fc18af5e0dd0a93cc0c88f488803a43f40261242aaf0`。记录为 `build/assistant-settings-phone-verification.json`，构建日志为 `build/assistant-settings-fix-build.log`。

用户在早期验证之后手动将 Floryx Dev 设为默认助手，本次保留该选择。因此上文早期报告中的“角色为空”是当时恢复结果，不是目前手机状态；不得再使用旧快照覆盖用户新选择。原 vivo 语音识别服务保持不变，任务前台服务未运行。项目设备测试约定已同步改为默认使用该真机。

## 使用限制

首次使用仍需在系统设置中选择 Floryx，或手动添加快捷磁贴；诊断用例临时改变的选择会恢复原状，用户自行选定的默认助手予以保留。厂商电源键、语音唤醒、Root 持久 Binder 传输、后台跨应用执行屏、长时间熄屏和真实功耗尚不属于本批已完成能力。

参考：[Android VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService)、[AOSP 助手元数据校验](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/service/voice/VoiceInteractionServiceInfo.java)、[副屏活动启动限制](https://source.android.com/docs/core/display/multi_display/activity-launch)。
