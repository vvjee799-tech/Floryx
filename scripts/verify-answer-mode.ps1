param([ValidateSet('10AD5J08C5000UJ')][string]$Serial = '10AD5J08C5000UJ')
$ErrorActionPreference = 'Stop'
$adbPath = 'D:\ruanjian\AndroidSDK\platform-tools\adb.exe'
$recovery = Join-Path $PSScriptRoot 'restore-phone-accessibility.ps1'
& $recovery -Serial $Serial
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDirectory = Join-Path $projectRoot 'build\answer-mode'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$log = Join-Path $outputDirectory 'device.log'
try {
    & $adbPath -s $Serial shell am instrument -w -e class com.flowchat.app.device.ScreenQuestionDiagnosticTest `
        -e runScreenQuestionDiagnostics true com.flowchat.app.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1 |
        Tee-Object -FilePath $log
    if ($LASTEXITCODE -ne 0 -or !(Select-String -LiteralPath $log -Pattern '^OK \(' -Quiet)) {
        throw "Answer-mode diagnostics failed. See $log"
    }
} finally {
    # This must run on the host AFTER am instrument has exited, not inside test teardown.
    & $recovery -Serial $Serial
}
