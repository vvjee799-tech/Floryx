# 后台确认返回流程修复与 MuMu 实测

## 修复

后台任务等待系统命令或设备操作确认时，切回 Floryx 原先会被当成手动接管，取消原确认并显示“继续任务／结束任务”。现在只在具体确认请求等待期间接纳这次前台返回；确认、取消或任务停止后恢复正常接管检测。旧任务已被接管时，后来的确认请求不能恢复其执行。

设备复测还在 Compose 测试驱动下触发了消息列表滚动的布局重入。流式滚动改为等待下一帧后执行，避免在父布局测量期间再次强制测量。

## 实测环境和方法

- 使用已安装的 MuMuManager 自行启动 MuMu 0，Android 15；确认实例 ADB 地址后，所有设备命令明确指定 `127.0.0.1:16384`。
- 覆盖安装 `com.flowchat.app.debug`（Floryx Dev）及测试 APK，不清除应用数据。
- `BackgroundConfirmationDiagnosticTest` 使用本地脚本模型返回工具请求，实际运行正式应用的 Activity、悬浮窗、确认框、任务控制与 Root 命令通道，不调用外部模型。
- 工具请求在应用实际进入后台后才放行；通过 Android 原生无障碍窗口确认弹窗，并注入真实触摸选择继续、取消及结束任务。最终验证不使用 Compose 虚拟测试时钟。
- 浅色和深色各完整执行一轮，分别为 `OK (1 test)`，耗时 13.136 秒、13.062 秒；已人工核对设备截图。

## 已验证结果

1. 后台浮窗显示等待确认；返回应用后保留系统命令确认框，没有出现任务接管卡片。
2. 点击继续后，两条实际 Android 命令以退出码 0 完成，`id -u` 输出 0；同一任务无需重复确认第二条命令。
3. 点击取消后没有新增 Android 命令执行。
4. 允许一个无害等待命令后，普通切走并返回会显示“继续任务／结束任务”，实际命令状态变为 Cancelled，末尾标记未执行；结束任务按钮能清除暂停卡片。
5. 测试清理合成会话和临时供应商，恢复 Power、命令、记忆、连接方式与外观设置；MuMu 保持运行。

全量单元测试为 394 项通过、1 项跳过。Debug 和 AndroidTest APK 均构建成功。

## 证据和复跑

- 最终设备日志：`build/background-confirmation/native-light.log`、`build/background-confirmation/native-dark.log`。
- 最终截图：`build/background-confirmation/native-light/`、`build/background-confirmation/native-dark/` 下的 `allow-overlay.png`、`allow-returned.png`、`cancel-returned.png`、`ordinary-return-paused.png` 等。
- 修复安装包：`app/build/outputs/apk/debug/app-debug.apk`。

安装 Debug 与 AndroidTest APK 后，在确认的 MuMu 串号上运行：

```powershell
& 'D:\ruanjian\AndroidSDK\platform-tools\adb.exe' -s 127.0.0.1:16384 shell am instrument -w -r -e class com.flowchat.app.device.BackgroundConfirmationDiagnosticTest -e runBackgroundConfirmationDiagnostics true com.flowchat.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

深色复跑额外传入 `-e backgroundAppearance dark`。需已有 Floryx Dev 悬浮窗权限和 Root 授权。
