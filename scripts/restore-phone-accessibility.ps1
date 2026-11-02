param(
    [ValidateSet('10AD5J08C5000UJ')][string]$Serial = '10AD5J08C5000UJ',
    [ValidateSet('com.flowchat.app.debug', 'com.flowchat.app')][string]$Package = 'com.flowchat.app.debug'
)
$ErrorActionPreference = 'Stop'
$adbPath = 'D:\ruanjian\AndroidSDK\platform-tools\adb.exe'
$serviceClass = 'com.flowchat.app.data.device.FlowChatAccessibilityService'
$component = "$Package/$serviceClass"
$shortComponent = "$Package/.data.device.FlowChatAccessibilityService"

function Invoke-PhoneAdb([string[]]$AdbArguments) {
    $output = & $adbPath -s $Serial @AdbArguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($AdbArguments -join ' '): $output" }
    return ($output -join "`n").Trim()
}
function Test-ServiceInSection([string]$Dump, [string]$Section) {
    if ($Section -eq 'Bound services') {
        # This phone's accessibility dump prints labels only, without component IDs.
        # Correlate it with the specific service's live system binding instead.
        $service = Invoke-PhoneAdb @('shell', 'dumpsys', 'activity', 'services', $component)
        return $Dump -match '(?m)^\s*Bound services:\{Service\[' -and $service.Contains($component) -and
            $service.Contains('requested=true received=true hasBound=true')
    }
    $line = ($Dump -split "`n" | Where-Object { $_ -match "^\s*${Section}:" }) -join ' '
    return $line.Contains($Package) -and $line.Contains('FlowChatAccessibilityService')
}

if ((Invoke-PhoneAdb @('shell', 'getprop', 'ro.product.model')) -ne 'V2302A' -or
    (Invoke-PhoneAdb @('shell', 'getprop', 'ro.product.manufacturer')) -ne 'vivo') {
    throw 'The specified serial is not the verified iQOO V2302A phone.'
}
$original = Invoke-PhoneAdb @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
$enabled = @($original -split ':' | Where-Object { $_ -and $_ -ne 'null' })
if ($component -notin $enabled -and $shortComponent -notin $enabled) {
    throw 'Floryx accessibility was not enabled. No permission or setting was changed.'
}
$originalMaster = Invoke-PhoneAdb @('shell', 'settings', 'get', 'secure', 'accessibility_enabled')
$state = Invoke-PhoneAdb @('shell', 'dumpsys', 'accessibility')
if (!(Test-ServiceInSection $state 'Bound services') -or (Test-ServiceInSection $state 'Crashed services')) {
    # The instrumentation runner force-stops its target after the test process exits.
    # Rebind only this already-authorized service, preserving all other enabled services.
    Invoke-PhoneAdb @('shell', 'am', 'start', '-W', '-n', "$Package/com.flowchat.app.MainActivity") | Out-Null
    $remaining = @($enabled | Where-Object { $_ -ne $component -and $_ -ne $shortComponent })
    try {
        if ($remaining.Count) {
            Invoke-PhoneAdb @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ($remaining -join ':')) | Out-Null
        } else {
            Invoke-PhoneAdb @('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services') | Out-Null
        }
        $deadline = [DateTime]::UtcNow.AddSeconds(5)
        do {
            Start-Sleep -Milliseconds 200
            $state = Invoke-PhoneAdb @('shell', 'dumpsys', 'accessibility')
        } while (((Test-ServiceInSection $state 'Bound services') -or (Test-ServiceInSection $state 'Crashed services')) -and [DateTime]::UtcNow -lt $deadline)
    } finally {
        Invoke-PhoneAdb @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', $original) | Out-Null
        Invoke-PhoneAdb @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', $originalMaster) | Out-Null
    }
}
$deadline = [DateTime]::UtcNow.AddSeconds(12)
do {
    $state = Invoke-PhoneAdb @('shell', 'dumpsys', 'accessibility')
    if ((Test-ServiceInSection $state 'Bound services') -and !(Test-ServiceInSection $state 'Crashed services')) { break }
    Start-Sleep -Milliseconds 250
} while ([DateTime]::UtcNow -lt $deadline)
if (!(Test-ServiceInSection $state 'Bound services') -or (Test-ServiceInSection $state 'Crashed services')) {
    throw 'Accessibility did not bind after reconnecting; check device logs.'
}
Start-Sleep -Milliseconds 1200
$state = Invoke-PhoneAdb @('shell', 'dumpsys', 'accessibility')
if (!(Test-ServiceInSection $state 'Bound services') -or (Test-ServiceInSection $state 'Crashed services')) {
    throw 'Accessibility disconnected again after binding.'
}
if ((Invoke-PhoneAdb @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')) -ne $original) {
    throw 'The enabled accessibility-service list changed during recovery.'
}
Write-Output "Accessibility healthy on ${Serial}: $component is bound; no crash entry; enabled-service list preserved."
