# 一次性下载 Android 编译工具链（默认放到 <项目>\toolchain，可用 XXKG_TOOL_DIR 覆盖）
# JDK=清华源, SDK=腾讯源, Gradle=腾讯源
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root = if ($env:XXKG_TOOL_DIR) {
    $env:XXKG_TOOL_DIR
} else {
    Join-Path (Split-Path $PSScriptRoot -Parent) 'toolchain'
}
$dl   = Join-Path $root '_downloads'
New-Item -ItemType Directory -Force -Path $root, $dl | Out-Null

function Get-File($url, $out) {
    if ((Test-Path $out) -and ((Get-Item $out).Length -gt 1MB)) {
        Write-Host "[skip] $out ($([math]::Round((Get-Item $out).Length/1MB,1)) MB)"
        return
    }
    Write-Host "[get ] $url"
    & curl.exe -sS -L --fail --retry 3 --retry-delay 2 --connect-timeout 20 -o $out $url
    if ($LASTEXITCODE -ne 0) { throw "download failed: $url" }
    Write-Host "       -> $([math]::Round((Get-Item $out).Length/1MB,1)) MB"
}

# 1) JDK 17 (Temurin, 清华镜像)
$jdkZip = Join-Path $dl 'jdk17.zip'
Get-File 'https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip' $jdkZip
if (-not (Test-Path (Join-Path $root 'jdk17\bin\java.exe'))) {
    Write-Host '[unzip] jdk17'
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'jdk17'), (Join-Path $root '_tmpjdk') | Out-Null
    & tar.exe -xf $jdkZip -C (Join-Path $root '_tmpjdk')
    $inner = Get-ChildItem (Join-Path $root '_tmpjdk') -Directory | Select-Object -First 1
    Copy-Item "$($inner.FullName)\*" (Join-Path $root 'jdk17') -Recurse -Force
    Remove-Item (Join-Path $root '_tmpjdk') -Recurse -Force
}

# 2) Android cmdline-tools (腾讯镜像)
$cmdZip = Join-Path $dl 'cmdline-tools.zip'
Get-File 'https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-win-11076708_latest.zip' $cmdZip
$sdkRoot = Join-Path $root 'android-sdk'
if (-not (Test-Path (Join-Path $sdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'))) {
    Write-Host '[unzip] cmdline-tools'
    New-Item -ItemType Directory -Force -Path (Join-Path $sdkRoot 'cmdline-tools') | Out-Null
    & tar.exe -xf $cmdZip -C (Join-Path $sdkRoot 'cmdline-tools')
    Move-Item (Join-Path $sdkRoot 'cmdline-tools\cmdline-tools') (Join-Path $sdkRoot 'cmdline-tools\latest') -Force
}

# 3) Gradle 8.7 (腾讯镜像)
$gz = Join-Path $dl 'gradle-8.7-bin.zip'
Get-File 'https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip' $gz
if (-not (Test-Path (Join-Path $root 'gradle-8.7\bin\gradle.bat'))) {
    Write-Host '[unzip] gradle'
    & tar.exe -xf $gz -C $root
}

# 4) SDK 组件（走腾讯镜像的 repository）
$env:JAVA_HOME = Join-Path $root 'jdk17'
$env:SDK_TEST_BASE_URL = 'https://mirrors.cloud.tencent.com/AndroidSDK/'
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_HOME = $sdkRoot
$sm = Join-Path $sdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'

$need = @('platform-tools', 'platforms;android-34', 'build-tools;34.0.0')
$missing = @()
foreach ($p in $need) {
    $probe = switch -Wildcard ($p) {
        'platform-tools'         { Join-Path $sdkRoot 'platform-tools\adb.exe' }
        'platforms;android-34'   { Join-Path $sdkRoot 'platforms\android-34\android.jar' }
        'build-tools;34.0.0'     { Join-Path $sdkRoot 'build-tools\34.0.0\aapt2.exe' }
    }
    if (-not (Test-Path $probe)) { $missing += $p }
}
if ($missing.Count -gt 0) {
    Write-Host "[sdk ] installing: $($missing -join ', ')"
    # 接受许可
    $yes = ("y`n" * 30)
    $yes | & $sm --sdk_root=$sdkRoot --licenses 2>&1 | Out-Null
    foreach ($p in $missing) {
        Write-Host "[sdk ] $p"
        $yes | & $sm --sdk_root=$sdkRoot $p 2>&1 | Select-Object -Last 3
        if ($LASTEXITCODE -ne 0) { Write-Host "[warn] $p install exit $LASTEXITCODE" }
    }
} else {
    Write-Host '[sdk ] all components present'
}

Write-Host '[ok] toolchain ready'
"JDK   : $(Join-Path $root 'jdk17')"
"SDK   : $sdkRoot"
"Gradle: $(Join-Path $root 'gradle-8.7')"
