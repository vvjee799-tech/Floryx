# Device testing

- Use the user's MuMu Android emulator as the default device for installation, debugging, and device testing in this project.
- Use a connected physical phone only when the user explicitly requests it. Do not automatically select a phone if multiple ADB devices are available.
- Before running device commands, identify the MuMu emulator's ADB serial and target that serial explicitly.
- If the MuMu emulator is unavailable, report the issue and troubleshoot it instead of switching to a phone.
- Before native computer-use interactions, obtain the user's explicit confirmation for the planned scope, as required by the user's standing computer-use instructions. This does not require confirmation for ordinary shell commands or file edits.

# Local Android build tools

- JDK: `D:\ruanjian\Java\jdk-21.0.12.1+1` (set `JAVA_HOME` for build commands).
- Android SDK: `D:\ruanjian\AndroidSDK`; the ignored `local.properties` points here.
- Gradle: `D:\ruanjian\Gradle\gradle-8.10.2\bin\gradle.bat`; use `GRADLE_USER_HOME=D:\ruanjian\GradleCache` to reuse the downloaded dependencies.
- Debug builds install as `FlowChat Dev` (`com.flowchat.app.debug`) alongside the release app. KernelSU permissions must be granted separately to each package.
