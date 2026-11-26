param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot

function Resolve-SdkDir {
    if ($env:ANDROID_HOME -and (Test-Path -LiteralPath $env:ANDROID_HOME)) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT -and (Test-Path -LiteralPath $env:ANDROID_SDK_ROOT)) { return $env:ANDROID_SDK_ROOT }
    $localProps = Join-Path $projectRoot 'local.properties'
    if (Test-Path -LiteralPath $localProps) {
        $line = Get-Content -LiteralPath $localProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) {
            $raw = ($line -replace '^sdk\.dir=', '').Trim() -replace '\\\\', '\' -replace '\\:', ':'
            if (Test-Path -LiteralPath $raw) { return $raw }
        }
    }
    throw 'Android SDK not found. Set ANDROID_HOME or configure sdk.dir in local.properties.'
}

$keystoreProps = Join-Path $projectRoot 'keystore.properties'
if (!(Test-Path -LiteralPath $keystoreProps)) {
    throw 'Missing keystore.properties. Configure release signing before running build-release.ps1.'
}

$gradle = Join-Path $projectRoot 'gradlew.bat'
if (!(Test-Path -LiteralPath $gradle)) {
    throw 'Missing gradlew.bat in repository root.'
}

$sdkDir = Resolve-SdkDir
$buildToolsRoot = Join-Path $sdkDir 'build-tools'
$buildTools = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
    Sort-Object { [version]($_.Name -replace '-.*$', '') } -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if (!$buildTools) { throw "No Android build-tools found under $buildToolsRoot" }

$configuration = Get-Content -LiteralPath (Join-Path $projectRoot 'app\build.gradle.kts') -Raw
$version = [regex]::Match($configuration, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$versionCode = [int][regex]::Match($configuration, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (!$version -or $versionCode -le 0) { throw 'Could not determine release versionName/versionCode from app/build.gradle.kts.' }

if (!$OutputDirectory) { $OutputDirectory = Join-Path $projectRoot "build\releases\$version" }
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null

$buildLog = Join-Path $outputRoot 'build.log'
& $gradle :app:testDebugUnitTest :app:lintRelease --max-workers=1 --no-parallel --console=plain 2>&1 |
    Tee-Object -FilePath $buildLog
if ($LASTEXITCODE -ne 0) { throw 'Release verification (unit tests or lintRelease) failed.' }

# Finish the test/lint workers before R8 starts to keep peak memory lower.
& $gradle :app:assembleRelease --max-workers=1 --no-parallel --console=plain 2>&1 |
    Tee-Object -FilePath $buildLog -Append
if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }

$releaseApk = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk'
if (!(Test-Path -LiteralPath $releaseApk)) { throw "Expected APK missing: $releaseApk" }

$artifactName = "Floryx-v$version.apk"
$artifact = Join-Path $outputRoot $artifactName
Copy-Item -LiteralPath $releaseApk -Destination $artifact -Force

$certOutput = & (Join-Path $buildTools 'apksigner.bat') verify --verbose --print-certs $artifact 2>&1
if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed:`n$($certOutput -join "`n")" }
$certMatch = [regex]::Match(($certOutput -join "`n"), 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})')
$certificateSha256 = if ($certMatch.Success) { $certMatch.Groups[1].Value.ToLowerInvariant() } else { '' }
if (!$certificateSha256) { throw 'Could not extract signing certificate SHA-256 digest.' }

$metadata = & (Join-Path $buildTools 'aapt.exe') dump badging $artifact 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the APK with aapt.' }
$metadataText = $metadata -join "`n"
if ($metadataText -notmatch "package: name='com\.floryx\.app'" -or
    $metadataText -notmatch "versionCode='$versionCode'" -or
    $metadataText -notmatch "versionName='$([regex]::Escape($version))'" -or
    $metadataText -notmatch "application-label:'Floryx'" -or
    $metadataText -match 'application-debuggable') {
    throw 'APK identity, version, label, or non-debuggable release flags are incorrect.'
}

$digest = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $outputRoot 'SHA256SUMS.txt'), "$digest  $artifactName`n", [Text.UTF8Encoding]::new($false))

$summary = [ordered]@{
    version = $version
    versionCode = $versionCode
    applicationId = 'com.floryx.app'
    label = 'Floryx'
    debuggable = $false
    certificateSha256 = $certificateSha256
    sha256 = $digest
    apk = $artifact
}
$summary | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outputRoot 'artifact-verification.json') -Encoding utf8
$summary | ConvertTo-Json
