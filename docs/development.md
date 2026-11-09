# 开发指南

公开仓库包含应用源码、构建配置和必要资源。测试夹具、真机调试资料与维护者的 PowerShell 构建安装脚本不随公开源码分发，构建应用无需这些文件。

## 环境与构建

需要 JDK 17、Android SDK Platform 34、Build Tools 35.0.0。设置 `JAVA_HOME`、`ANDROID_HOME`，或在 `android-client/local.properties` 中设置 `sdk.dir`；该文件不提交。

```bash
cd android-client
./gradlew :app:assembleDebug :app:lintRelease
```

Windows 使用 `gradlew.bat`。调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。应用包名为 `cn.openpocket.app`，默认没有服务器地址，需要连接自己的服务。

使用独立模拟器或测试设备验证 UI、离线会话切换、终端按键、复制、滚动和网络恢复；不要用不同签名的调试包替换保存个人数据的正式安装。

## 发布签名

创建并备份自己的 PKCS12 签名密钥，别名使用 `openpocket`。在当前构建进程中设置 `OPENPOCKET_KEYSTORE`（密钥绝对路径）和 `OPENPOCKET_STORE_PASS`，然后执行：

```bash
./gradlew :app:assembleRelease :app:lintRelease
```

签名包位于 `app/build/outputs/apk/release/app-release.apk`。若没有设置签名环境变量，Gradle 会生成未签名发布包，不能作为签名安装包分发。使用 Android SDK 的 `apksigner verify --verbose` 校验成品。

不要把私钥或密码写入源码、命令示例、CI 日志或仓库。覆盖安装必须沿用相同签名；本项目不保存维护者的签名文件。发布流程见 [release.md](release.md)。

## 离线拼音词库

APK 已包含词库，普通构建不需要 Node.js，也不需要重新生成。修改词库时可使用公开的生成器：

```bash
cd scripts
npm ci --ignore-scripts --omit=optional
npm run build:pinyin
```

需要 Node.js 20 或以上版本。生成器从锁定的 pinyin 2.11.2 公开字典生成固定候选，不包含用户输入。请保留 assets/keyboard 中的来源与许可。

## 提交前

执行 Android 构建与 Lint，并说明实际验证过的场景、设备或模拟器。运行 `python scripts/audit-public.py --staged` 检查待提交文件；截图还需要人工检查内容。GitHub CI 执行公开文件检查、Lint 与调试构建。
