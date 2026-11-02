# 编译 APK（工具链默认在 <项目>\toolchain，可用 XXKG_TOOL_DIR 覆盖）
$ErrorActionPreference = 'Stop'
$proj = Split-Path $PSScriptRoot -Parent
$tool = if ($env:XXKG_TOOL_DIR) { $env:XXKG_TOOL_DIR } else { Join-Path $proj 'toolchain' }

$env:JAVA_HOME      = Join-Path $tool 'jdk17'
$env:ANDROID_HOME   = Join-Path $tool 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $tool 'gradle-home'
$env:PATH = "$(Join-Path $tool 'jdk17\bin');$env:PATH"

$gradle = Join-Path $tool 'gradle-8.7\bin\gradle.bat'
$task = if ($args.Count -gt 0) { $args[0] } else { ':app:assembleDebug' }

& $gradle -p $proj --no-daemon $task
if ($LASTEXITCODE -ne 0) { throw "gradle 失败，退出码 $LASTEXITCODE" }

$apk = if ($task -match 'Release') {
    Join-Path $proj 'app\build\outputs\apk\release\app-release.apk'
} else {
    Join-Path $proj 'app\build\outputs\apk\debug\app-debug.apk'
}
if (Test-Path $apk) {
    "=============================================="
    "APK: $apk"
    "大小: $([math]::Round((Get-Item $apk).Length / 1MB, 2)) MB"
}
