# 开发指南

需要 JDK 17、Android SDK Platform 34、Build Tools 35.0.0。设置 `JAVA_HOME`、`ANDROID_HOME`，或在 `android-client/local.properties` 中配置 `sdk.dir`；该文件不提交。

## Android

```bash
cd android-client
./gradlew :app:testReleaseUnitTest :app:assembleDebug :app:lintRelease
```

Windows 使用 `gradlew.bat`。正式版没有默认服务器；输入自己的 HTTPS 地址，或可信私人网络中的 HTTP IP。应用包名为 `cn.openpocket.app`。

用调试 APK 在独立模拟器检查，避免覆盖有个人数据的正式安装：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s <emulator-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <emulator-serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <emulator-serial> shell am instrument -w cn.openpocket.app.test/cn.openpocket.app.SmokeInstrumentation
adb -s <emulator-serial> shell am instrument -w -e chatModels true cn.openpocket.app.test/cn.openpocket.app.SmokeInstrumentation
adb -s <emulator-serial> shell am instrument -w -e offlineUsage true cn.openpocket.app.test/cn.openpocket.app.SmokeInstrumentation
adb -s <emulator-serial> shell am instrument -w -e keyboard true cn.openpocket.app.test/cn.openpocket.app.SmokeInstrumentation
```

这些命令使用虚构数据验证 SQLite、模型隔离、离线响应、用量缓存及键盘。仪器入口另有手动 `seed` 联网模式，会读取外部凭据并可能修改服务器数据；日常回归和 CI 不使用此模式。

## 终端浏览器回归

```bash
cd scripts
npm ci --ignore-scripts --omit=optional
npx playwright install chromium
npm test
```

Linux 首次安装浏览器时可用 `npx playwright install --with-deps chromium`。也可设置 `BROWSER_EXECUTABLE` 指向本地 Chromium / Edge。测试只加载仓库的本地页面并注入虚构输出，不连接服务器。覆盖 Unicode 历史、触摸拖动、惯性、复制、备用屏幕退出、光标与尺寸恢复。

运行 `npm run build:pinyin` 可从锁定的 pinyin 2.11.2 字典重建离线词库。词库是固定候选，不包含用户输入。保留 assets/keyboard 中的来源与许可。

## 发布签名

Windows 运行 `android-client/Build-Release.ps1`，脚本先执行单元测试与 Lint，然后输出 `android-client/artifacts/OpenPocket-<version>.apk` 与 SHA256。首次运行在用户目录 `.android/openpocket-signing` 创建密钥，以后复用；该目录和构建产物均不入库。

其他平台可自行创建 PKCS12 密钥，别名 `openpocket`，在当前进程设置 `OPENPOCKET_KEYSTORE`（绝对路径）和 `OPENPOCKET_STORE_PASS`，再执行 `./gradlew :app:assembleRelease`。缺少签名环境变量时 Gradle 产生未签名发布包，不能作为已签名安装包分发。请用 SDK 的 `apksigner verify --verbose` 校验最终 APK。

更换签名无法直接覆盖原安装。不要为安装测试版卸载个人正式版；使用隔离模拟器。发布流程见 [release.md](release.md)。
