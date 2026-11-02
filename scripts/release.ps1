# Build a signed release APK, copy it to dist/, and print its SHA-256.
# Usage: powershell -ExecutionPolicy Bypass -File .\scripts\release.ps1
param([string]$Version = "")

$ErrorActionPreference = 'Stop'
$proj = Split-Path $PSScriptRoot -Parent

if (-not $Version) {
    $m = Select-String -Path (Join-Path $proj 'app\build.gradle.kts') -Pattern 'versionName = "([^"]+)"' |
        Select-Object -First 1
    if ($m) { $Version = $m.Matches[0].Groups[1].Value }
}
if (-not $Version) { throw "Cannot read versionName from app/build.gradle.kts" }

& powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build.ps1') :app:assembleRelease
if ($LASTEXITCODE -ne 0) { throw "release build failed" }

$apk = Join-Path $proj 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { throw "release APK not found: $apk" }

$dist = Join-Path $proj 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$dest = Join-Path $dist "chaoxing-board-v$Version.apk"
Copy-Item -LiteralPath $apk -Destination $dest -Force

$hash = (Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash.ToLower()
$size = [math]::Round((Get-Item -LiteralPath $dest).Length / 1MB, 2)

"=============================================="
"APK   : $dest"
"Version: $Version"
"Size  : $size MB"
"SHA256: $hash"
"Next  : GitHub -> Releases -> New release, tag v$Version, upload the APK above."
