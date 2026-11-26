param(
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Label = 'current',
    [switch]$BuildOnly,
    [string]$AdbPath = $env:ADB_PATH
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot

$gradle = Join-Path $projectRoot 'gradlew.bat'
$outputDirectory = Join-Path $projectRoot 'build\device-performance'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
& $gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease --no-daemon --max-workers=1 --console=plain 2>&1 |
    Tee-Object -FilePath (Join-Path $outputDirectory "$Label-build.log")
if ($LASTEXITCODE -ne 0) { throw 'Build, lint or unit tests failed.' }
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
$classes = 'com.flowchat.app.device.DevicePerformanceDiagnosticTest,com.flowchat.app.device.DeviceAutomationTest,com.flowchat.app.device.VisualTapDiagnosticTest,com.flowchat.app.device.ScreenVisionDiagnosticTest,com.flowchat.app.device.FloatingOutputDiagnosticTest'
$deviceLog = Join-Path $outputDirectory "$Label-device.log"
& $AdbPath -s $serial shell am instrument -w -r -e class $classes `
    -e runDevicePerformanceDiagnostics true -e performanceLabel $Label `
    -e runVisualTapDiagnostics true -e runScreenVisionDiagnostics true -e runOverlayDiagnostics true `
    com.floryx.app.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | Tee-Object -FilePath $deviceLog
if ($LASTEXITCODE -ne 0 -or !(Select-String -LiteralPath $deviceLog -Pattern '^OK \(' -Quiet)) {
    throw "MuMu regression failed. See $deviceLog"
}
foreach ($report in @("$Label.json", 'pixel-comparison.json')) {
    $reportLines = & $AdbPath -s $serial shell run-as com.floryx.app.debug cat "files/diagnostics/device-performance/$report"
    if ($LASTEXITCODE -ne 0) { throw "Could not read $report from MuMu." }
    $reportLines | Set-Content -LiteralPath (Join-Path $outputDirectory $report) -Encoding utf8
}
