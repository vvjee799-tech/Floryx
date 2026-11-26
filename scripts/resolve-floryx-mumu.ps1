param(
    [switch]$Start,
    [string]$MuMuManagerPath = $env:MUMU_MANAGER
)
$ErrorActionPreference = 'Stop'

if (!$MuMuManagerPath) {
    $cmd = Get-Command 'MuMuManager.exe' -ErrorAction SilentlyContinue
    if ($cmd) { $MuMuManagerPath = $cmd.Source }
}
if (!$MuMuManagerPath -or !(Test-Path -LiteralPath $MuMuManagerPath)) {
    throw 'MuMuManager.exe not found. Pass -MuMuManagerPath or set MUMU_MANAGER.'
}

$inventory = & $MuMuManagerPath info -v all | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Could not query MuMu instances.' }
$matches = @($inventory.PSObject.Properties.Value | Where-Object { $_.name -ceq 'Floryx' })
if ($matches.Count -ne 1) { throw 'Expected exactly one MuMu instance named Floryx; no other instance will be used.' }
$instance = $matches[0]
if (!$instance.is_android_started -and $Start) {
    & $MuMuManagerPath control -v $instance.index launch | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the Floryx instance.' }
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        Start-Sleep -Milliseconds 500
        $instance = & $MuMuManagerPath info -v $instance.index | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0 -or $instance.name -cne 'Floryx') { throw 'MuMu instance identity changed; stopping.' }
    } while (!$instance.is_android_started -and [DateTime]::UtcNow -lt $deadline)
}
if (!$instance.is_android_started -or !$instance.adb_host_ip -or !$instance.adb_port) {
    throw 'The Floryx MuMu instance is not ready. No other device will be used.'
}
[PSCustomObject]@{
    Name = $instance.name
    Index = $instance.index
    Serial = '{0}:{1}' -f $instance.adb_host_ip, $instance.adb_port
}
