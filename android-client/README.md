# OpenPocket Android

面向 Open WebUI 的原生 Java Android 客户端，最低 Android 8.0（API 26）。

功能、截图、安装和多语言入口见 [项目首页](../README.md)。构建步骤见 [开发指南](../docs/development.md)，服务端要求见 [兼容说明](../docs/server-compatibility.md)。

```bash
./gradlew :app:assembleDebug :app:lintRelease
```

Windows 使用 `gradlew.bat`。公开构建只需要 JDK 与 Android SDK，不需要维护者的本地测试目录或 PowerShell 脚本。发布签名通过环境变量配置，具体步骤见开发指南。
