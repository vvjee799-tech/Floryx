# Floryx Development Guidelines

## Product Direction & Architecture Principles

- **Autonomy & Reliability**: Floryx aims for seamless, intelligent, human-like Android automation and workspace execution. Once a user states a clear goal, Floryx handles navigation, observation, input, verification, and error recovery internally with minimal interruptions.
- **General Screen Understanding**: Prefer generic UI-tree observation, visual targeting, and adaptive goal-driven scrolling (fine adjustment near target, overshoot recovery) rather than brittle per-app hardcoding.
- **Bounded & Verifiable Actions**:
  - Separate command exit status, resource cleanup verification, and business-goal completion.
  - Workspace operations use bounded byte-range reading/searching and SHA-256 verification on write-back, keeping conflict copies rather than silently overwriting modified user files.
  - Password, payment, and `FLAG_SECURE` screens are strictly blocked from capture and automated control.
- **Single Task Spine**: Chat, system assistant, floating overlay, and sharing entries share one task-run state machine and persistent checkpoint/budget tracking (`FlowChatDatabase` v17).

## Build & Verification Conventions

- **Requirements**: JDK 21, Android SDK 35 (`local.properties` or `ANDROID_HOME`).
- **Unit Tests**: `./gradlew :app:testDebugUnitTest` (or `.\gradlew.bat :app:testDebugUnitTest` on Windows).
- **Lint & Delta Guard**: `./gradlew :app:lintDebug :app:lintRelease` and `python tools/check_lint_delta.py`.
- **Release Build**: Requires local `keystore.properties` (never committed). Run `.\scripts\build-release.ps1` to verify tests, release lint, R8 minification, signature, and APK metadata (`com.floryx.app`).
- **Device Testing**: Always specify explicit ADB serials (`adb -s <serial>`), verify device identity before testing, and restore original accessibility service bindings and clean up temporary test packages/artifacts after instrumentation runs.
