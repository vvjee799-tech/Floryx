param(
    [switch]$BuildOnly,
    [string]$AdbPath = $env:ADB_PATH
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot

$gradle = Join-Path $projectRoot 'gradlew.bat'
$outputDirectory = Join-Path $projectRoot 'build\chat-optimization'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
& $gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease :app:assembleRelease --console=plain 2>&1 |
    Tee-Object -FilePath (Join-Path $outputDirectory 'build.log')
if ($LASTEXITCODE -ne 0) { throw 'Android build or unit tests failed.' }
if ($BuildOnly) { return }

if (!$AdbPath) {
    $cmd = Get-Command 'adb.exe' -ErrorAction SilentlyContinue
    if ($cmd) { $AdbPath = $cmd.Source }
    elseif ($env:ANDROID_HOME) { $AdbPath = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' }
}
if (!$AdbPath -or !(Test-Path -LiteralPath $AdbPath)) { throw 'adb not found. Set ANDROID_HOME or pass -AdbPath.' }

$device = & (Join-Path $PSScriptRoot 'resolve-floryx-mumu.ps1') -Start
$serial = $device.Serial
& $AdbPath connect $serial
& $AdbPath -s $serial wait-for-device
$booted = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    if ((& $AdbPath -s $serial shell getprop sys.boot_completed).Trim() -eq '1') { $booted = $true; break }
    Start-Sleep -Seconds 2
}
if (!$booted) { throw 'MuMu did not finish booting.' }
& $AdbPath -s $serial install -r (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk')
if ($LASTEXITCODE -ne 0) { throw 'Could not install the debug app on MuMu.' }
& $AdbPath -s $serial install -r (Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Could not install the device tests on MuMu.' }
$classes = 'com.flowchat.app.device.ProductReliabilityDiagnosticTest,com.flowchat.app.device.ChatOptimizationDiagnosticTest,com.flowchat.app.device.ContextWindowDiagnosticTest,com.flowchat.app.device.ComposerFooterDiagnosticTest,com.flowchat.app.device.FloatingOutputDiagnosticTest'
& $AdbPath -s $serial shell am instrument -w -r -e class $classes `
    -e runChatOptimizationDiagnostics true -e runProductReliabilityDiagnostics true -e runContextWindowDiagnostics true `
    -e runUsageDiagnostics true -e runOverlayDiagnostics true com.floryx.app.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1 |
    Tee-Object -FilePath (Join-Path $outputDirectory 'device.log')
if ($LASTEXITCODE -ne 0 -or !(Select-String -LiteralPath (Join-Path $outputDirectory 'device.log') -Pattern 'OK \(' -Quiet)) {
    throw 'MuMu chat optimization tests failed. See build/chat-optimization/device.log.'
}
