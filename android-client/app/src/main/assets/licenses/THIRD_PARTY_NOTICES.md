# 第三方声明

OpenPocket 原创代码使用根目录 MIT 许可。以下组件保留自己的许可；项目 MIT 不替代第三方条款。

## APK 中的组件

| 组件 | 版本 | 许可与来源 |
| --- | --- | --- |
| xterm.js | 6.0.0 | MIT；[xtermjs/xterm.js](https://github.com/xtermjs/xterm.js)，原许可保存在 assets/terminal/LICENSE.xterm |
| addon-fit | 0.11.0 | MIT；同上，LICENSE.fit |
| addon-serialize | 0.14.0 | MIT；同上，LICENSE.serialize |
| pinyin 反向字词数据 | 2.11.2 | MIT；[hotoo/pinyin](https://github.com/hotoo/pinyin)，assets/keyboard/LICENSE.pinyin 与 SOURCE.txt |
| Socket.IO Java client | 2.1.2 | MIT；[socketio/socket.io-client-java](https://github.com/socketio/socket.io-client-java)，[许可](docs/licenses/socket.io-client-java.txt) |
| Engine.IO Java client | 2.1.0 | MIT；[socketio/engine.io-client-java](https://github.com/socketio/engine.io-client-java)，[许可](docs/licenses/engine.io-client-java.txt) |
| OkHttp | 3.12.12 | Apache-2.0；[square/okhttp](https://github.com/square/okhttp)，[许可](docs/licenses/okhttp.txt) |
| Okio | 1.15.0 | Apache-2.0；[square/okio](https://github.com/square/okio)，[许可](docs/licenses/okio.txt) |

离线拼音由 scripts/build-pinyin.cjs 从公开字典反向生成，没有包含用户输入。Android 平台提供的 org.json 不作为单独第三方库打包。

OkHttp: Copyright (C) 2012 Square, Inc.；Okio: Copyright (C) 2014 Square, Inc.。这些组件按上表 Apache-2.0 条款提供，未修改其源码。

本 APK 的许可文本位于 assets/licenses/（Socket.IO、Engine.IO、OkHttp、Okio 和 OpenPocket）、assets/terminal/LICENSE.*（xterm.js 及插件）和 assets/keyboard/LICENSE.pinyin。上表中的 docs 链接对应源代码仓库的文件。

## 开发与测试

- Gradle Wrapper 8.11.1：[Gradle 许可](docs/licenses/gradle.txt)，包括 Apache-2.0 与该上游发行说明所列第三方条款。完整 Gradle 发行包由 Wrapper 下载，不随 APK 打包。
- Android Gradle Plugin / Android SDK / JDK：由开发者单独安装并遵守各工具许可，不纳入项目 MIT 授权。

第三方 JavaScript 保留原始许可证文件与源码头。后续升级依赖时应同步检查许可与版本。
