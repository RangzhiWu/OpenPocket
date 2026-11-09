# OpenPocket

面向 **Open WebUI** 的 Android 客户端。把对话、离线历史、服务器任务和交互终端放进口袋。

[![Android CI](https://github.com/RangzhiWu/OpenPocket/actions/workflows/android.yml/badge.svg)](https://github.com/RangzhiWu/OpenPocket/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

## 功能

- **会话隔离**：每个对话独立保存模型选择，切换会话、离线或重开应用均可恢复。
- **离线历史**：完整消息、思考过程与工具输出保存至手机 SQLite，按账号和服务器隔离。本地读取不等待网络请求，显示保存进度。
- **服务器任务**：前台实时更新、任务状态与停止操作。关闭客户端不取消服务器上的任务。
- **交互终端**：本地打包 xterm.js，支持 ANSI、备用屏幕、原始控制键、复制与惯性滚动。
- **终端键盘**：底部展开时抬高输出区，集成 Ctrl/Alt、Esc、Tab、方向键和离线中文拼音；不使用系统输入法，不学习输入历史。
- **附件与用量**：文件、图片上传；真实用量统计在手机持久化，先显示缓存，再按间隔后台更新。

## 安装

从 [Releases](https://github.com/RangzhiWu/OpenPocket/releases) 下载签名 APK。需要 **Android 8.0 / API 26 或更高版本**。

打开应用，填写你自己的 Open WebUI 地址、邮箱和密码。公网服务器使用 HTTPS；可信局域网、回环或私人 VPN 的 IP 地址支持 HTTP。应用没有预置服务器、账号或 API Key。已有用户覆盖安装保留登录与本地数据；自行签名的构建必须保持同一签名才能覆盖安装。

**服务器兼容性：** 本项目最初针对带有后台任务、用量接口和 OpenTerminal 代理的 Open WebUI 部署开发。基本会话接口与扩展功能的需求不同；安装 APK 并不会自动安装 Agent 或配置终端。接入前请阅读 [服务器兼容说明](docs/server-compatibility.md)。原版或不同版本的 Open WebUI 可能缺少部分接口。

## 构建与测试

需要 JDK 17、Android SDK 34、Build Tools 35.0.0。仓库自带 Gradle Wrapper。

```bash
cd android-client
# 设置 JAVA_HOME、ANDROID_HOME，或在 local.properties 中设置 sdk.dir
./gradlew :app:assembleDebug :app:testReleaseUnitTest :app:lintRelease
```

Windows 使用 `gradlew.bat`。在已配置 Android SDK 的 Windows 环境中，`android-client/Build-Release.ps1` 可生成本地签名发布包，签名资料保存在用户目录，源码中不包含密钥。其他环境的发布签名步骤见 [开发指南](docs/development.md)。

终端浏览器回归与拼音词库重建需要 Node.js 20 或更高版本，详见开发指南。Android 仪器测试只使用模拟数据，勿将调试测试包装到存有个人数据的正式安装上。

## 项目结构

```text
android-client/   Android 应用、Gradle 配置和测试
server/terminal/  可选的 Linux Bash / PTY 配套辅助脚本
scripts/          终端浏览器回归、词库生成和公开文件检查
docs/             接入、开发、发布与隐私说明
.github/          CI、问题模板与发布流程
```

## 当前限制

- 首次离线使用前，需要联网完成对应会话的保存；附件原文件暂不下载为离线副本。
- 服务器没有提供消息级增量接口时，变化的会话会重新获取完整快照；未变化的会话复用手机缓存。
- 用量使用真实服务器统计；客户端一分钟内复用缓存，服务端聚合并非增量计数器。缺失的历史用量不估算。
- 无后台推送、语音输入；拼音采用固定词库，生僻词覆盖与候选排序有限。
- 终端全屏 CLI 的行为取决于服务端 PTY 和程序；不能将浏览器回归视为所有设备、CLI 菜单均已验证。

参与开发请阅读 [贡献指南](CONTRIBUTING.md)。漏洞报告见 [SECURITY.md](SECURITY.md)，版本记录见 [CHANGELOG.md](CHANGELOG.md)。

## 许可

本项目原创代码使用 [MIT License](LICENSE)。第三方代码、数据与构建工具保留各自许可，见 [第三方声明](THIRD_PARTY_NOTICES.md)。OpenPocket 是独立客户端项目，与 Open WebUI、OpenAI 或 Anthropic 无隶属关系。
