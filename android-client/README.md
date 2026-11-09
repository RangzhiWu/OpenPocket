# OpenPocket Android

面向 Open WebUI 的原生 Java Android 客户端，最低 Android 8.0（API 26）。

安装、功能与限制见 [项目首页](../README.md)。构建与测试见 [开发指南](../docs/development.md)，服务端接口要求见 [兼容说明](../docs/server-compatibility.md)。应用默认没有服务器地址，请连接自己的服务。

```bash
./gradlew :app:testReleaseUnitTest :app:assembleDebug :app:lintRelease
```

Windows 可运行 `./Build-Release.ps1` 生成签名 APK，再运行 `./Install-Device.ps1` 安装到已授权 ADB 的设备。多台设备用 `-Serial` 指定；`-Rebuild` 重新构建。签名保存在用户目录 `.android/openpocket-signing`，请自行妥善备份，覆盖安装必须沿用同一签名。
