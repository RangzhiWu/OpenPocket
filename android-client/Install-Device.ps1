param([string]$Serial = '', [string]$AdbPath = '', [switch]$Rebuild)
$ErrorActionPreference = 'Stop'
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adb = if ($AdbPath) { $AdbPath } else { Join-Path $sdkRoot 'platform-tools/adb.exe' }
if (-not (Test-Path -LiteralPath $adb)) { throw '未找到 ADB，请设置 ANDROID_HOME 或使用 -AdbPath。' }
$version = [regex]::Match((Get-Content (Join-Path $PSScriptRoot 'app/build.gradle') -Raw), "versionName '([^']+)'").Groups[1].Value
if (-not $version) { throw '未找到版本号。' }
$apk = Join-Path $PSScriptRoot "artifacts/OpenPocket-$version.apk"
if ($Rebuild -or -not (Test-Path -LiteralPath $apk)) { & (Join-Path $PSScriptRoot 'Build-Release.ps1') }
$devices = & $adb devices
if ($LASTEXITCODE -ne 0) { throw 'ADB 执行失败。' }
$authorized = @($devices | Where-Object { $_ -match '^(\S+)\s+device\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
if ($Serial) {
    if ($authorized -notcontains $Serial) { throw '指定设备未连接或未授权。' }
} elseif ($authorized.Count -eq 1) { $Serial = $authorized[0] }
else { throw "发现 $($authorized.Count) 台已授权设备，请使用 -Serial 指定。" }
& $adb -s $Serial install --no-incremental -r $apk
if ($LASTEXITCODE -ne 0) { throw '覆盖安装失败。请检查签名和版本；脚本不会卸载应用或清空数据。' }
& $adb -s $Serial shell am start -n cn.openpocket.app/.MainActivity
if ($LASTEXITCODE -ne 0) { throw '已安装，请从设备桌面打开 OpenPocket。' }
Write-Output "已安装 OpenPocket $version。"
