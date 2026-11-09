# 贡献指南

欢迎提交问题与 Pull Request。请说明具体场景、预期结果与实际结果，复现时使用测试账号与虚构数据。

代码 Agent 开始工作前请阅读 [AGENTS.md](AGENTS.md)。所有贡献者都应先了解 [服务端改造与功能依赖](docs/backend-customizations.md)：本项目基于维护者改造的 Open WebUI 开发，不能仅凭客户端构建成功宣称原版后端支持所有功能。

1. Fork 仓库并建立主题分支。
2. 按 [开发指南](docs/development.md) 配置 JDK 与 Android SDK。
3. 执行 Android 构建与 Lint，并验证与改动相关的界面或行为。
4. 提交前运行 `python scripts/audit-public.py --staged`，确认没有凭据、私人地址、日志或个人数据。
5. 文档更新时同步维护 README 的语言入口；截图须使用可公开的演示内容。

提交中说明变更原因、验证方式和已知限制。请勿上传私钥、签名文件、真实账号、Bearer Token、手机数据库或包含私人消息的截图。本地测试夹具和个人构建安装脚本不属于公开源码。

贡献的原创代码应有权以 MIT 协议提供；引入第三方代码时保留原许可并更新 `THIRD_PARTY_NOTICES.md`。
