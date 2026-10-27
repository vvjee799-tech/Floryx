param(
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Label = 'current',
    [switch]$BuildOnly
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$env:JAVA_HOME = 'D:\ruanjian\Java\jdk-21.0.12.1+1'
$env:GRADLE_USER_HOME = 'D:\ruanjian\GradleCache'
$gradle = 'D:\ruanjian\Gradle\gradle-8.10.2\bin\gradle.bat'
$adb = 'D:\ruanjian\AndroidSDK\platform-tools\adb.exe'
$manager = 'D:\ruanjian\MuMuPlayer\nx_main\MuMuManager.exe'
$outputDirectory = Join-Path $projectRoot 'build\device-performance'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
& $gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease --no-daemon --max-workers=1 --console=plain 2>&1 |
    Tee-Object -FilePath (Join-Path $outputDirectory "$Label-build.log")
if ($LASTEXITCODE -ne 0) { throw 'Build, lint or unit tests failed.' }
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
$classes = 'com.flowchat.app.device.DevicePerformanceDiagnosticTest,com.flowchat.app.device.DeviceAutomationTest,com.flowchat.app.device.VisualTapDiagnosticTest,com.flowchat.app.device.ScreenVisionDiagnosticTest,com.flowchat.app.device.FloatingOutputDiagnosticTest'
$deviceLog = Join-Path $outputDirectory "$Label-device.log"
& $adb -s $serial shell am instrument -w -r -e class $classes `
    -e runDevicePerformanceDiagnostics true -e performanceLabel $Label `
    -e runVisualTapDiagnostics true -e runScreenVisionDiagnostics true -e runOverlayDiagnostics true `
    com.flowchat.app.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | Tee-Object -FilePath $deviceLog
if ($LASTEXITCODE -ne 0 -or !(Select-String -LiteralPath $deviceLog -Pattern '^OK \(' -Quiet)) {
    throw "MuMu regression failed. See $deviceLog"
}
foreach ($report in @("$Label.json", 'pixel-comparison.json')) {
    $reportLines = & $adb -s $serial shell run-as com.flowchat.app.debug cat "files/diagnostics/device-performance/$report"
    if ($LASTEXITCODE -ne 0) { throw "Could not read $report from MuMu." }
    $reportLines | Set-Content -LiteralPath (Join-Path $outputDirectory $report) -Encoding utf8
}
