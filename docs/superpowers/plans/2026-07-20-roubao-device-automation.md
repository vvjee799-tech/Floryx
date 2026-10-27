# Roubao-Style Device Automation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a text-model-compatible Android automation layer to Floryx so the assistant can observe accessible UI elements and perform bounded device actions through the existing tool-call conversation flow.

**Architecture:** Keep Floryx's existing OpenAI-compatible tool loop and Shizuku gateway. Add an Android AccessibilityService for structured UI-tree observation and semantic element actions, while retaining Shizuku for device state and shell-level controls. Expose only narrowly typed tools, require explicit user intent for actions, confirm destructive actions, cap autonomous action rounds, and leave screenshot/VLM observation behind a future-compatible interface.

**Tech Stack:** Kotlin, Jetpack Compose, Android AccessibilityService, Shizuku, Hilt, kotlinx.serialization, JUnit.

---

### Task 1: Define structured UI automation contracts

**Files:**
- Modify: `app/src/main/java/com/flowchat/app/domain/device/DeviceAssistantGateway.kt`
- Modify: `app/src/main/java/com/flowchat/app/domain/device/DeviceActionPolicy.kt`
- Test: `app/src/test/java/com/flowchat/app/domain/device/DeviceActionPolicyTest.kt`
- Test: `app/src/test/java/com/flowchat/app/domain/device/DeviceUiModelsTest.kt`

- [ ] Add failing tests for UI observation formatting, sensitive-screen redaction, bounded coordinates, and action confirmation levels.
- [ ] Run the focused tests and verify they fail because the contracts do not exist.
- [ ] Add `DeviceUiElement`, `DeviceScreenSnapshot`, accessibility state/capabilities, and gateway methods for observe, tap, swipe, input, Back, and Home.
- [ ] Run the focused tests and verify they pass.

### Task 2: Implement the accessibility execution layer

**Files:**
- Create: `app/src/main/java/com/flowchat/app/data/device/FlowChatAccessibilityService.kt`
- Create: `app/src/main/java/com/flowchat/app/data/device/AccessibilityScreenReader.kt`
- Create: `app/src/main/res/xml/accessibility_service_config.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/flowchat/app/data/device/ShizukuDeviceAssistantGateway.kt`
- Test: `app/src/test/java/com/flowchat/app/data/device/AccessibilityIntegrationContractTest.kt`

- [ ] Add a failing contract test for the declared service, privacy flags, indexed UI observation, and semantic actions.
- [ ] Run it and verify the missing service/configuration failure.
- [ ] Implement capped UI-tree extraction, cached element indexes, click/long-click, text replacement, gestures, Back/Home, and accessibility settings launch.
- [ ] Add sensitive-window and FlowChat-self safeguards; avoid logging UI text.
- [ ] Run the focused tests and verify they pass.

### Task 3: Expose bounded agent tools

**Files:**
- Modify: `app/src/main/java/com/flowchat/app/domain/tools/AgentToolDefinitions.kt`
- Modify: `app/src/main/java/com/flowchat/app/presentation/chat/ChatViewModel.kt`
- Test: `app/src/test/java/com/flowchat/app/domain/tools/AgentToolDefinitionsTest.kt`
- Test: `app/src/test/java/com/flowchat/app/presentation/chat/ChatViewModelTest.kt`

- [ ] Add failing tests for `observe_screen`, `tap_ui_element`, `swipe_screen`, `input_text`, `press_back`, and `press_home` schemas and execution.
- [ ] Verify the tests fail because the tools are absent.
- [ ] Register tools only when device assistant and required permissions are enabled.
- [ ] Execute actions through the gateway, publish existing timeline messages, refresh the screen after actions, and increase the loop cap to a bounded multi-step task budget.
- [ ] Require explicit user intent for device-changing actions and confirmation for destructive or sensitive actions.
- [ ] Run focused ViewModel and tool-definition tests.

### Task 4: Add permission/status UI without making chat formal

**Files:**
- Modify: `app/src/main/java/com/flowchat/app/presentation/chat/ChatUiState.kt`
- Modify: `app/src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Test: `app/src/test/java/com/flowchat/app/presentation/chat/DeviceAssistantUiContractTest.kt`

- [ ] Add a failing UI contract test for accessibility status and settings action.
- [ ] Verify it fails.
- [ ] Extend the existing device-assistant dialog with a compact accessibility status row and enable action.
- [ ] Keep tool progress in the existing conversational timeline; do not add a separate automation dashboard.
- [ ] Run UI contract tests.

### Task 5: Verify and install

**Files:**
- Modify: `README.md`
- Create: `NOTICE`

- [ ] Credit the MIT-licensed Roubao project and document the text-model UI-tree fallback plus future VLM path.
- [ ] Run `:app:testDebugUnitTest`.
- [ ] Run `:app:assembleDebug`.
- [ ] Install to the LDPlayer emulator and verify permission setup, screen observation, a safe multi-step Settings navigation, stopping output, and ordinary chat regression.
- [ ] Capture the final package path and test result.
