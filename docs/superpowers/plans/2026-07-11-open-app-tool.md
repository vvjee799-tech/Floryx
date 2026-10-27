# Open App Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let Floryx's assistant directly open a uniquely matched launcher app after an explicit user request.

**Architecture:** Reuse the existing OpenAI-compatible tool loop and tool timeline. Add a small `AppLauncher` Android boundary that discovers launcher activities, applies deterministic label/package matching, and starts an explicit activity; expose it through one new `open_app` tool.

**Tech Stack:** Kotlin, Android PackageManager/Intent, Hilt, kotlinx.serialization, JUnit, Jetpack Compose existing tool UI.

---

### Task 1: Add the model tool contract

**Files:**
- Modify: `app/src/test/java/com/flowchat/app/domain/tools/AgentToolDefinitionsTest.kt`
- Modify: `app/src/main/java/com/flowchat/app/domain/tools/AgentToolDefinitions.kt`

- [ ] **Step 1: Write the failing schema test**

Assert that `withLifestyleTools()` includes `open_app` and that its JSON schema has one required `app_name` string. Also assert that its description limits calls to explicit open/launch requests.

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.flowchat.app.domain.tools.AgentToolDefinitionsTest" --no-daemon --console plain
```

Expected: FAIL because `open_app` does not exist.

- [ ] **Step 3: Add the minimal tool definition**

Add:

```kotlin
const val OpenAppToolName = "open_app"

fun openApp(): ChatToolDefinition = ChatToolDefinition(
    name = OpenAppToolName,
    description = "Open an installed Android app only when the latest user message explicitly asks to open or launch it.",
    parameters = JsonObject(
        mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(
                mapOf(
                    "app_name" to JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The app label or Android package name to open.")
                        )
                    )
                )
            ),
            "required" to JsonArray(listOf(JsonPrimitive("app_name"))),
            "additionalProperties" to JsonPrimitive(false)
        )
    )
)
```

Append `openApp()` to `withLifestyleTools()`.

- [ ] **Step 4: Run the test and verify GREEN**

Use the command from Step 2. Expected: PASS.

### Task 2: Match and launch installed apps

**Files:**
- Create: `app/src/main/java/com/flowchat/app/domain/repository/AppLauncher.kt`
- Create: `app/src/main/java/com/flowchat/app/data/applauncher/AndroidAppLauncher.kt`
- Create: `app/src/test/java/com/flowchat/app/data/applauncher/AndroidAppLauncherTest.kt`

- [ ] **Step 1: Write failing matcher tests**

Cover exact localized label matching, exact package matching, one unique partial label, no match, and ambiguous matches. The desired API is:

```kotlin
internal data class LaunchableApp(
    val label: String,
    val packageName: String,
    val activityName: String
)

internal fun selectLaunchableApp(apps: List<LaunchableApp>, query: String): LaunchableApp
```

- [ ] **Step 2: Run the matcher test and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.flowchat.app.data.applauncher.AndroidAppLauncherTest" --no-daemon --console plain
```

Expected: compilation failure because the launcher types are absent.

- [ ] **Step 3: Implement deterministic matching and Android launch**

Create the boundary:

```kotlin
interface AppLauncher {
    suspend fun openApp(appName: String): String
}
```

Normalize with `lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)`. Prefer one exact label/package match, then one partial label/package match; throw a clear exception for zero or multiple matches. Query `ACTION_MAIN` plus `CATEGORY_LAUNCHER`, then launch the selected explicit component with `FLAG_ACTIVITY_NEW_TASK`. Return the actual label.

- [ ] **Step 4: Run the matcher test and verify GREEN**

Use the command from Step 2. Expected: PASS.

### Task 3: Execute the tool through the existing chat timeline

**Files:**
- Modify: `app/src/test/java/com/flowchat/app/presentation/chat/ChatViewModelTest.kt`
- Modify: `app/src/main/java/com/flowchat/app/presentation/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/flowchat/app/di/AppModule.kt`

- [ ] **Step 1: Write the failing ViewModel test**

Inject a fake `AppLauncher`, stream an `open_app` call with `{"app_name":"Settings"}`, then assert the fake received `Settings`, the follow-up request contains the tool result, and the tool timeline message uses `打开应用：Settings`.

- [ ] **Step 2: Run the ViewModel test and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.flowchat.app.presentation.chat.ChatViewModelTest" --no-daemon --console plain
```

Expected: FAIL because `ChatViewModel` does not execute `open_app`.

- [ ] **Step 3: Add the minimal execution branch**

Inject `AppLauncher`, parse `app_name` with the existing `toolJson`, add the `open_app` case to `executeSingleToolCall()`, and format the timeline name as `打开应用：<requested name>`. Bind `AndroidAppLauncher` to `AppLauncher` in Hilt.

- [ ] **Step 4: Run the ViewModel test and verify GREEN**

Use the command from Step 2. Expected: PASS.

### Task 4: Declare visibility and publish the release change

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle.kts`
- Modify: `README.md`

- [ ] **Step 1: Add launcher visibility without broad permission**

Add this directly under `<manifest>`:

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

Do not add `QUERY_ALL_PACKAGES`.

- [ ] **Step 2: Update release metadata and README**

Set `versionCode = 3`, `versionName = "1.1.0"`, and add direct app opening to the README's lifestyle-tool highlights and limitations.

- [ ] **Step 3: Run complete verification**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --no-daemon --console plain
```

Expected: BUILD SUCCESSFUL with signed debug and release APKs.

- [ ] **Step 4: Install and test on LDPlayer**

Install `app/build/outputs/apk/release/app-release.apk` on `emulator-5554`. Explicitly ask the model to open Settings, verify Android Settings becomes the resumed app, return to Floryx, and verify the completed tool timeline plus final assistant follow-up remain visible.

- [ ] **Step 5: Commit and push**

Commit only the feature, regression fix, tests, docs, version, and README; leave `design-qa.md` untouched. Push `main` to `origin`.
