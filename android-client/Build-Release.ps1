$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:/Program Files/Android/Android Studio/jbr' }
if (-not (Test-Path "$javaRoot/bin/java.exe")) {
    if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME }
    else { throw '请安装 JDK 17 或设置 JAVA_HOME。' }
}
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$version = [regex]::Match((Get-Content (Join-Path $projectRoot 'app/build.gradle') -Raw), "versionName '([^']+)'").Groups[1].Value
if (-not $version) { throw '未找到版本号。' }
$apkName = "OpenPocket-$version.apk"
if (-not (Test-Path $sdkRoot)) { throw '未找到 Android SDK，请设置 ANDROID_HOME。' }
Set-Content -LiteralPath (Join-Path $projectRoot 'local.properties') -Encoding ascii -Value ('sdk.dir=' + $sdkRoot.Replace('\','/'))
$signingRoot = Join-Path $env:USERPROFILE '.android/openpocket-signing'
New-Item -ItemType Directory -Force $signingRoot | Out-Null
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $signingRoot /inheritance:r /grant:r "${identity}:(OI)(CI)(F)" | Out-Null
if ($LASTEXITCODE -ne 0) { throw '无法保护签名目录权限。' }
$metadataPath = Join-Path $signingRoot 'signing.json'
$keystorePath = Join-Path $signingRoot 'release.p12'
if (-not (Test-Path $metadataPath)) {
    if (Test-Path $keystorePath) { throw '签名文件已存在但密码配置缺失，请恢复原 signing.json。' }
    $random = New-Object byte[] 36
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($random)
    $rng.Dispose()
    $secret = [Convert]::ToBase64String($random)
    @{ password = $secret } | ConvertTo-Json | Set-Content -LiteralPath $metadataPath -Encoding utf8
}
$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
$oldJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $javaRoot
    $env:OPENPOCKET_KEYSTORE = $keystorePath
    $env:OPENPOCKET_STORE_PASS = $metadata.password
    if (-not (Test-Path $keystorePath)) {
        & "$javaRoot/bin/keytool.exe" -genkeypair -noprompt -keystore $keystorePath -storetype PKCS12 -storepass:env OPENPOCKET_STORE_PASS -keypass:env OPENPOCKET_STORE_PASS -alias openpocket -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=OpenPocket Personal, OU=Personal Apps, O=OpenPocket, C=CN'
        if ($LASTEXITCODE -ne 0) { throw '生成发布签名失败。' }
    }
    Push-Location $projectRoot
    try {
        & './gradlew.bat' --no-daemon :app:testReleaseUnitTest :app:assembleRelease :app:lintRelease
        if ($LASTEXITCODE -ne 0) { throw 'APK 构建或检查失败。' }
        New-Item -ItemType Directory -Force './artifacts' | Out-Null
        Copy-Item -LiteralPath './app/build/outputs/apk/release/app-release.apk' -Destination "./artifacts/$apkName" -Force
        $buildTools = Join-Path $sdkRoot 'build-tools/35.0.0'
        if (-not (Test-Path -LiteralPath $buildTools)) { $buildTools = Join-Path $sdkRoot 'build-tools/34.0.0' }
        & "$buildTools/apksigner.bat" verify --verbose "./artifacts/$apkName"
        if ($LASTEXITCODE -ne 0) { throw 'APK 签名验证失败。' }
        $hash = (Get-FileHash -LiteralPath "./artifacts/$apkName" -Algorithm SHA256).Hash.ToLowerInvariant()
        Set-Content -LiteralPath './artifacts/SHA256.txt' -Encoding ascii -Value ($hash + '  ' + $apkName)
        Write-Output ('APK: ' + (Join-Path $projectRoot "artifacts/$apkName"))
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $oldJava
    Remove-Item Env:OPENPOCKET_KEYSTORE -ErrorAction SilentlyContinue
    Remove-Item Env:OPENPOCKET_STORE_PASS -ErrorAction SilentlyContinue
}
