# 答题模式：顶部实时横幅

设置中的“答题模式”使用一个总开关。开启后，长按悬浮球截取手机主显示屏当前画面并提交给已配置的图片模型；收到答案正文时，直接在屏幕顶部显示并持续更新。输出区域背景完全透明，无面板、边框、标题或附加按钮文字，仅保留 AI 正文及关闭图标。点击正文可打开完整会话。球体保持收起，不自动展开侧边内容面板。

原有“顶部显示答案”独立开关及完成后发送系统通知的路径已移除，顶部输出与答题模式统一启停。横幅是 `TYPE_APPLICATION_OVERLAY` 窗口，需要悬浮窗权限，不依赖系统通知授权或通知渠道；实际在手机保留勿扰状态时验证可见。

## 行为与边界

- 截图入口明确读取默认显示屏，开启后台执行设置也截取手机前台。
- 新一次长按会清除旧答案。所有屏幕截图期间隐藏顶部横幅与悬浮球，避免截入 Floryx 内容。
- 横幅只接收当前识屏任务的正文流，不展示思考内容，不接受其他任务或旧任务的迟到更新。
- 关闭横幅后，该题后续输出和最终结果不会使其重新出现；下次识屏可正常显示新横幅。
- 总开关关闭会立即清除横幅，并禁止长按截图。重新开启不复活旧答案。
- 回到 Floryx 或打开系统助手面板时隐藏横幅；查看完整会话会定位至横幅所属会话。
- 长答案区域可以滚动。正文流只在内存中投影，完整图片、答案和错误继续保存在会话。
- 正文和关闭图标使用黑色并加细白色字缘，保持背景透明，同时帮助在不同底色上辨认内容。
- 答题请求不提供或执行手机操作工具。截图受限、权限缺失或纯文本模型不适用时明确提示。
- 截图符合附件预算时保留原始图片字节与分辨率，超出预算时复用已有附件压缩流程。

## 悬浮球

圆形可触摸区域保留 48 dp；内部图片铺满同一圆形区域，外框直接贴着图片边缘绘制。移除原先 28 dp 图片外的大块底色和多重进度环，执行状态通过边框颜色变化表达。单击、拖动、长按原有分工保留。

## 验证

设备为用户指定的 `10AD5J08C5000UJ` / vivo V2302A（iQOO Neo8 Pro），安装目标为 Floryx Dev / `com.flowchat.app.debug`。

构建 `:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 通过。相关单元测试 91 项通过：

- `ChatTaskRuntimeTest` 68。
- `FloatingAssistantBridgeTest` 11。
- `FloatingAssistantContractTest` 7。
- `AnswerBannerStateTest` 3，覆盖任务隔离、关闭后不重新弹出、关闭模式后的迟到响应。
- `AppSettingsStoreTest` 2。

真机 `ScreenQuestionDiagnosticTest` 验证统一开关持久化、图片提交、完成前可见的流式顶部正文、完成后的内容保留、球体不展开侧栏、正文所属会话跳转、横幅关闭不重开、开关关闭即移除、敏感页面拒绝截图、草稿保留及断网结果保存。通过球体半径 40% 处四个方向的像素取样，确认图片已填充到原先留白的区域，并保存截图进行视觉核对。

测试使用含两道数学题及蓝色图形的自绘页面，以及本地可控 SSE 服务。页面题目没有暴露到控件文本树，测试答案用于验证图像与输出流程，不代表付费模型解题质量评测。测试数据和设置恢复后，宿主机另行恢复并验证无障碍服务。

运行：

```powershell
& .\scripts\verify-answer-mode.ps1
```

视觉核对截图：

- `app/build/answer-mode-qa/answer-mode-fitted-bubble.png`
- `app/build/answer-mode-qa/answer-mode-live-banner.png`

## 无障碍测试收尾

该手机会在 instrumentation 结束时停止目标进程。此前退出记录为 `stop com.flowchat.app.debug due to finished inst`，系统无障碍开关仍启用，但服务位于 `Crashed services`。

`scripts/verify-answer-mode.ps1` 在宿主机等待测试完全结束后，通过 `finally` 调用 `scripts/restore-phone-accessibility.ps1`，只重新绑定原本已获授权的指定服务，保留其他无障碍服务及主开关值。检查指定组件的 `requested=true received=true hasBound=true` 系统连接、无崩溃条目，并延迟复查。手机的 `Bound services` 仅显示服务名称，不能只通过其中搜索包名判断连接状态。测试进程内的连接检查不能替代宿主机收尾。
