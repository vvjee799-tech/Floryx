# 原生语音接管：替代 vivo 蓝心小 V（实验模块）

`native-voice-bridge/` 是 Floryx 提供的一个**可选实验性 Xposed 桥接模块**，用于在已 Root 并安装 LSPosed 的兼容设备上，复用系统原生语音助手的低功耗硬件唤醒、语音识别与原厂音色播报，将语音任务无缝交接给 Floryx 执行。

---

## 核心能力

- **复用原生低功耗唤醒**：无需后台长期自持麦克风录音，沿用系统原生唤醒词与熄屏硬件唤醒链路，降低待机功耗与误唤醒。
- **直接口述并无缝交接**：唤醒后直接说出需求（如“把媒体音量查一下”“在工作区读取便签”“打开计算器算一下 64 乘 9”），桥接模块将转写文本通过签名校验的 `ContentProvider` 传递给 Floryx 执行任务。
- **自然交互与面板让位**：
  - 支持自然口语问答、口述中途改口、同一会话短跟进；
  - 当 Floryx 需要操作前台其他应用时，会通知原生卡片自动让位，避免遮挡目标界面；
  - 仅由 Floryx 受理执行并回传结果，防止双助手同时抢答；
  - 支持通话/音频冲突中断与语音/按键停止。

---

## ⚠️ 严格兼容性与前置要求

使用本实验模块必须**同时满足**以下全部条件：

1. **系统环境**：设备已解锁并具备 **Root 权限**，且已安装支持 **Xposed API 102** 的框架（如 LSPosed）。
2. **目标宿主版本**：仅适配并严格校验官方签名的 `com.vivo.ai.copilot` 版本 **`6.11.1.1`**（`versionCode = 6110101`）。其它版本或其它品牌系统原生助手**无法工作**，校验不匹配时模块会自动安全跳过，不会接管。
3. **同签名 Debug 宿主要求**：出于安全校验，桥接模块（`com.floryx.nativevoicebridge`）与 Floryx 宿主之间强制校验签名一致（`PackageManager.SIGNATURE_MATCH`），且宿主侧接收端点（`NativeVoiceProvider`）仅在开启了显式 Gradle 编译开关的 Debug 构建中启用。

---

## 为什么 v1.0.0 Release 不附带桥接成品 APK？

- 默认正式包 `Floryx-v1.0.0.apk`（Release 构建）出于精简、安全与通用性考虑，**不包含** `NativeVoiceProvider` 桥接服务端点。
- 若单独在 Release 中分发一个 Debug 签名的桥接模块 APK，普通用户下载后既无法与 `Floryx-v1.0.0.apk` 配对（包名与签名均不匹配），也缺乏对应版本的系统前置条件。
- 因此在 `v1.0.0` 中，该模块仅随仓库源码提供给有经验的开发者自行配对编译；后续版本会评估在正式版中提供标准开关与配对分发方式。

---

## 源码配对编译与启用（进阶用户）

在具备 JDK 21 与 Android SDK 35 的环境中，使用同一套签名配置同步编译开启了真机语音运行时的 Debug 宿主与桥接模块：

```powershell
# 1. 编译开启原生语音桥接的 Floryx Dev 与桥接模块 APK
.\gradlew.bat :app:assembleDebug :native-voice-bridge:assembleDebug `
  -PnativeVoiceBridge=true -PnativeVoiceRealRuntime=true
```

生成的两个同签名 APK 位于：
- `app/build/outputs/apk/debug/app-debug.apk`（`com.floryx.app.debug`）
- `native-voice-bridge/build/outputs/apk/debug/native-voice-bridge-debug.apk`（`com.floryx.nativevoicebridge`）

安装与启用步骤：
1. 将上述两个同签名 APK 安装到同一台兼容设备，并确认已在 Floryx Dev 中配置好模型服务商与所需权限（如无障碍等）。
2. 在 LSPosed 管理器中启用「**Floryx 语音桥接**」模块，作用域仅勾选 `com.vivo.ai.copilot`（无需勾选其他应用），随后重启系统助手进程或重启手机使其生效。

---

## 免责声明

- 本模块为个人学习与开源技术研究性质的实验实现，**与 vivo / iQOO 官方及其「蓝心小 V」产品无任何隶属、授权或合作关系**。
- 修改或 Hook 系统级应用行为可能受系统更新、ROM 安全策略或厂商机制影响而失效，存在一定兼容性与稳定性风险；请用户充分评估后在个人设备上自行承担使用风险。
