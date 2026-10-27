param([switch]$BuildOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$env:JAVA_HOME = 'D:\ruanjian\Java\jdk-21.0.12.1+1'
$env:GRADLE_USER_HOME = 'D:\ruanjian\GradleCache'
$gradle = 'D:\ruanjian\Gradle\gradle-8.10.2\bin\gradle.bat'
$adb = 'D:\ruanjian\AndroidSDK\platform-tools\adb.exe'
$manager = 'D:\ruanjian\MuMuPlayer\nx_main\MuMuManager.exe'
$outputDirectory = Join-Path $projectRoot 'build\chat-optimization'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
& $gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease :app:assembleRelease --console=plain 2>&1 |
    Tee-Object -FilePath (Join-Path $outputDirectory 'build.log')
if ($LASTEXITCODE -ne 0) { throw 'Android build or unit tests failed.' }
if ($BuildOnly) { return }

$device = (& $manager info -v 0 | ConvertFrom-Json)
if (!$device.is_android_started) {
    & $manager control -v 0 launch
    if ($LASTEXITCODE -ne 0) { throw 'Could not start MuMu instance 0.' }
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        Start-Sleep -Seconds 2
        $device = (& $manager info -v 0 | ConvertFrom-Json)
        if ($device.is_android_started) { break }
    }
}
if (!$device.is_android_started -or !$device.adb_host_ip -or !$device.adb_port) { throw 'MuMu ADB endpoint is unavailable.' }
$serial = '{0}:{1}' -f $device.adb_host_ip, $device.adb_port
& $adb connect $serial
& $adb -s $serial wait-for-device
$booted = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    if ((& $adb -s $serial shell getprop sys.boot_completed).Trim() -eq '1') { $booted = $true; break }
    Start-Sleep -Seconds 2
}
if (!$booted) { throw 'MuMu did not finish booting.' }
& $adb -s $serial install -r (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk')
if ($LASTEXITCODE -ne 0) { throw 'Could not install the debug app on MuMu.' }
& $adb -s $serial install -r (Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Could not install the device tests on MuMu.' }
# The cold-recovery fixture must run before any Activity initializes the process-scoped recovery cache.
$classes = 'com.flowchat.app.device.ProductReliabilityDiagnosticTest,com.flowchat.app.device.ChatOptimizationDiagnosticTest,com.flowchat.app.device.ContextWindowDiagnosticTest,com.flowchat.app.device.ComposerFooterDiagnosticTest,com.flowchat.app.device.FloatingOutputDiagnosticTest'
& $adb -s $serial shell am instrument -w -r -e class $classes `
    -e runChatOptimizationDiagnostics true -e runProductReliabilityDiagnostics true -e runContextWindowDiagnostics true `
    -e runUsageDiagnostics true -e runOverlayDiagnostics true com.flowchat.app.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1 |
    Tee-Object -FilePath (Join-Path $outputDirectory 'device.log')
if ($LASTEXITCODE -ne 0 -or !(Select-String -LiteralPath (Join-Path $outputDirectory 'device.log') -Pattern 'OK \(' -Quiet)) {
    throw 'MuMu chat optimization tests failed. See build/chat-optimization/device.log.'
}
