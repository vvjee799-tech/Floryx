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
3. **同签名宿主要求**：出于安全校验，桥接模块（`com.floryx.nativevoicebridge`）与 Floryx 宿主之间强制校验签名一致（`PackageManager.SIGNATURE_MATCH`）。模块会优先自动绑定正式版 `com.floryx.app`，若未安装同签名正式版则会回退尝试调试版 `com.floryx.app.debug`。

---

## 正式版直接安装与启用（推荐，v1.0.1+）

自 **`v1.0.1`** 起，GitHub Release 会同时发布使用**同一张正式签名证书**构建的配对安装包：

- `Floryx-v<version>.apk`（主应用：`com.floryx.app`）
- `Floryx-VoiceBridge-v<version>.apk`（桥接模块：`com.floryx.nativevoicebridge`）

> 🛡️ **安全说明**：普通用户仅安装主应用 `Floryx-v<version>.apk` 时，`NativeVoiceProvider` 仅放行与 Floryx 签名完全一致的 `com.floryx.nativevoicebridge` 或官方原版 `com.vivo.ai.copilot`（`6.11.1.1`），其他第三方应用无法调用，不会带来额外暴露面。

安装与启用步骤：

1. 在兼容设备上安装同一次发布的 `Floryx-v<version>.apk` 与 `Floryx-VoiceBridge-v<version>.apk`（若此前装过旧的 `com.flowchat.nativevoicebridge` 测试桥接模块，请先卸载旧模块）。
2. 打开 **Floryx**，至少配置并启用一个大模型服务商与模型（若尚未开启「手机操作」无障碍或悬浮窗，可按需开启）。
3. 打开 **LSPosed 管理器 → 模块 → Floryx 语音桥接**：
   - 打开模块总开关；
   - 作用域**仅勾选「蓝心小V」**（`com.vivo.ai.copilot`），无需勾选其他应用；
4. **强行停止一次「蓝心小V」**（或重启手机），随后直接语音/按键唤醒小 V 即可。

---

## 从源码配对编译（开发者 / Debug 调试）

如果你使用的是自签 Debug 包（`com.floryx.app.debug`）或需要调试语音桥接代码，可在具备 JDK 21 与 Android SDK 35 的环境中同步编译开启了真机语音运行时的 Debug 宿主与桥接模块（桥接模块会自动识别同签名的 `com.floryx.app.debug`）：

```powershell
# 编译开启原生语音桥接的 Floryx Dev 与桥接模块 Debug APK
.\gradlew.bat :app:assembleDebug :native-voice-bridge:assembleDebug `
  -PnativeVoiceBridge=true -PnativeVoiceRealRuntime=true
```

生成的两个同签名 Debug APK 位于：
- `app/build/outputs/apk/debug/app-debug.apk`（`com.floryx.app.debug`）
- `native-voice-bridge/build/outputs/apk/debug/native-voice-bridge-debug.apk`（`com.floryx.nativevoicebridge`）

---

## 免责声明

- 本模块为个人学习与开源技术研究性质的实验实现，**与 vivo / iQOO 官方及其「蓝心小 V」产品无任何隶属、授权或合作关系**。
- 修改或 Hook 系统级应用行为可能受系统更新、ROM 安全策略或厂商机制影响而失效，存在一定兼容性与稳定性风险；请用户充分评估后在个人设备上自行承担使用风险。
