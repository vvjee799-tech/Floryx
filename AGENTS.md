# Device testing

- Use the MuMu instance named `Floryx` as the default device for installation, debugging, and device testing, per the user's instruction on 2026-11-02. This supersedes the previous physical-phone default.
- Before device commands, query `D:\ruanjian\MuMuPlayer\nx_main\MuMuManager.exe info -v all`, verify the instance name, and obtain its current ADB host/port from instance info. The instance was verified as index `1` on 2026-11-02; recheck the name rather than assuming the index or port never changes. Target its ADB serial explicitly for every command.
- Starting the existing `Floryx` instance when needed is authorized. Do not install into, run tests on, or automatically fall back to another MuMu instance (including index `0`, named `火影脚本`). If `Floryx` is missing or unavailable, report the issue and continue independent work.
- App behavior fixes require running device tests on `Floryx` before completion; passing unit tests alone does not replace device verification. Use the physical phone only when the user explicitly requests it; verify `10AD5J08C5000UJ` (iQOO Neo8 Pro / V2302A) before targeting it.
- Instrumentation completion can force-stop Floryx Dev and leave its enabled accessibility service in `Crashed services`. After `am instrument` exits, restore any previously enabled Floryx accessibility service on the selected test device and verify it is bound and absent from the crash list before reporting completion. Preserve the original enabled-service list; an enabled switch or passing in-process test alone is insufficient. `scripts/restore-phone-accessibility.ps1` is phone-specific and must only be used for the verified phone, not MuMu.
- Before native computer-use interactions, obtain the user's explicit confirmation for the planned scope, as required by the user's standing computer-use instructions. This does not require confirmation for ordinary shell commands or file edits.

# Local Android build tools

- JDK: `D:\ruanjian\Java\jdk-21.0.12.1+1` (set `JAVA_HOME` for build commands).
- Android SDK: `D:\ruanjian\AndroidSDK`; the ignored `local.properties` points here.
- Gradle: `D:\ruanjian\Gradle\gradle-8.10.2\bin\gradle.bat`; use `GRADLE_USER_HOME=D:\ruanjian\GradleCache` to reuse the downloaded dependencies.
- Debug builds install as `Floryx Dev` (`com.flowchat.app.debug`) alongside the release app. KernelSU permissions must be granted separately to each package.
