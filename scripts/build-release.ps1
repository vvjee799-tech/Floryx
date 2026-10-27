param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$env:JAVA_HOME = 'D:\ruanjian\Java\jdk-21.0.12.1+1'
$env:GRADLE_USER_HOME = 'D:\ruanjian\GradleCache'
$gradle = 'D:\ruanjian\Gradle\gradle-8.10.2\bin\gradle.bat'
$buildTools = 'D:\ruanjian\AndroidSDK\build-tools\35.0.0'
$debugKeystore = Join-Path $env:USERPROFILE '.android\debug.keystore'
if (!(Test-Path -LiteralPath $debugKeystore)) { throw 'The existing release-channel keystore is required. Do not generate a replacement.' }
$configuration = Get-Content -LiteralPath (Join-Path $projectRoot 'app\build.gradle.kts') -Raw
$version = [regex]::Match($configuration, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$versionCode = [int][regex]::Match($configuration, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (!$version) { throw 'Could not determine the release version.' }
if (!$OutputDirectory) { $OutputDirectory = Join-Path $projectRoot "build\releases\$version" }
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$initScript = Join-Path $PSScriptRoot 'retain-existing-install.init.gradle'
& $gradle -I $initScript :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --console=plain 2>&1 |
    Tee-Object -FilePath (Join-Path $outputRoot 'build.log')
if ($LASTEXITCODE -ne 0) { throw 'Release build or verification failed.' }
$artifact = Join-Path $outputRoot "Floryx-$version-retain-config.apk"
Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk') -Destination $artifact
$certificate = & (Join-Path $buildTools 'apksigner.bat') verify --verbose --print-certs $artifact
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$expectedCertificate = '423042ed2303b81afcb8d6581541e0dab977297a9893b8906071fd2867681772'
if (($certificate -join "`n") -notmatch $expectedCertificate) { throw 'APK certificate does not match the existing release channel.' }
$metadata = & (Join-Path $buildTools 'aapt.exe') dump badging $artifact
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the APK.' }
$metadataText = $metadata -join "`n"
if ($metadataText -notmatch "package: name='com.flowchat.app.debug'" -or
    $metadataText -notmatch "versionCode='$versionCode'" -or $metadataText -notmatch "versionName='$([regex]::Escape($version))'" -or
    $metadataText -notmatch "application-label:'Floryx'" -or $metadataText -match 'application-debuggable') {
    throw 'APK identity, version, label or release flags are incorrect.'
}
$digest = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $outputRoot "SHA256SUMS-$version.txt"), "$digest  $([IO.Path]::GetFileName($artifact))`n", [Text.UTF8Encoding]::new($false))
$summary = [ordered]@{ version = $version; versionCode = $versionCode; applicationId = 'com.flowchat.app.debug'; label = 'Floryx'; debuggable = $false; certificateSha256 = $expectedCertificate; sha256 = $digest; apk = $artifact }
$summary | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outputRoot 'artifact-verification.json') -Encoding utf8
$summary | ConvertTo-Json
