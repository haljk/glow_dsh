<#
.SYNOPSIS
    Build the Glow Android app and run its unit tests.

.DESCRIPTION
    The Gradle test worker cannot read source paths that contain non-ASCII
    characters: every unit test fails with ClassNotFoundException even though the
    classes compile. rather than move the whole repository, this script mirrors
    android/ to an ASCII path, builds there, and copies the APK back.

.PARAMETER Test
    Also run the unit tests (conformance vectors).

.PARAMETER Install
    Install the built APK onto the connected device or emulator with adb.

.EXAMPLE
    ./tools/build-android.ps1 -Test
#>
param(
    [switch]$Test,
    [switch]$Install
)

$ErrorActionPreference = 'Stop'

$repoAndroid = Join-Path $PSScriptRoot '..\android' | Resolve-Path
$buildRoot = 'C:\glow-build\android'
$gradle = 'C:\gradle\gradle-9.1.0\bin\gradle.bat'
$sdk = 'C:\Android\Sdk'

$env:JAVA_HOME = 'C:\Program Files\Amazon Corretto\jdk21.0.10_7'
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

Write-Host "==> Mirroring source to $buildRoot (ASCII path is required for unit tests)" -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
& robocopy.exe $repoAndroid $buildRoot /MIR /XD build .gradle .kotlin /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy failed with $LASTEXITCODE" }

# A delivered APK must be distinguishable from the last one. Derive both numbers from
# git so this cannot be forgotten: the commit count only ever goes up, and the short
# SHA makes a build traceable back to its source.
$repoRoot = Join-Path $PSScriptRoot '..'
$versionCode = (git -C $repoRoot rev-list --count HEAD 2>$null)
$shortSha = (git -C $repoRoot rev-parse --short HEAD 2>$null)
if (-not $versionCode) { $versionCode = '1' }
if (-not $shortSha) { $shortSha = 'nogit' }
$versionName = "1.1.$versionCode-$shortSha"
Write-Host "==> version $versionName (versionCode $versionCode)" -ForegroundColor Cyan

$tasks = @(':app:assembleDebug')
if ($Test) { $tasks += ':app:testDebugUnitTest' }

Write-Host "==> gradle $($tasks -join ' ')" -ForegroundColor Cyan
Push-Location $buildRoot
try {
    & $gradle @tasks --no-daemon --console=plain "-PglowVersionCode=$versionCode" "-PglowVersionName=$versionName"
    if ($LASTEXITCODE -ne 0) { throw "gradle failed with $LASTEXITCODE" }
} finally {
    Pop-Location
}

$apk = Join-Path $buildRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw "APK not produced at $apk" }

$dist = Join-Path $PSScriptRoot '..\dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$target = Join-Path $dist 'glow-debug.apk'
Copy-Item $apk $target -Force
Write-Host "==> APK: $target ($([math]::Round((Get-Item $target).Length / 1MB, 2)) MB)" -ForegroundColor Green

if ($Install) {
    Write-Host '==> Installing on the connected device' -ForegroundColor Cyan
    & "$sdk\platform-tools\adb.exe" install -r $target
}
