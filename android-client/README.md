# OpenPocket Android

面向维护者改造后的 Open WebUI 部署开发的原生 Java Android 客户端，最低 Android 8.0（API 26）。完整功能需要配套后端；安装 APK 不会改造原版 Open WebUI。当前仓库未包含完整服务端改造源码或一键部署包，详见 [服务端改造与功能依赖](../docs/backend-customizations.md)。

功能、截图、安装和多语言入口见 [项目首页](../README.md)。构建步骤见 [开发指南](../docs/development.md)，服务端要求见 [兼容说明](../docs/server-compatibility.md)。

```bash
./gradlew :app:assembleDebug :app:lintRelease
```

Windows 使用 `gradlew.bat`。公开构建只需要 JDK 与 Android SDK，不需要维护者的本地测试目录或 PowerShell 脚本。发布签名通过环境变量配置，具体步骤见开发指南。
