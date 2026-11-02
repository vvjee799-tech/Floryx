param([switch]$Start)
$ErrorActionPreference = 'Stop'
$manager = 'D:\ruanjian\MuMuPlayer\nx_main\MuMuManager.exe'
$inventory = & $manager info -v all | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Could not query MuMu instances.' }
$matches = @($inventory.PSObject.Properties.Value | Where-Object { $_.name -ceq 'Floryx' })
if ($matches.Count -ne 1) { throw 'Expected exactly one MuMu instance named Floryx; no other instance will be used.' }
$instance = $matches[0]
if (!$instance.is_android_started -and $Start) {
    & $manager control -v $instance.index launch | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the Floryx instance.' }
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        Start-Sleep -Milliseconds 500
        $instance = & $manager info -v $instance.index | ConvertFrom-Json
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
