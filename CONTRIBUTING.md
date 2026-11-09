# 贡献指南

欢迎提交问题与 Pull Request。请先说明具体场景、预期结果和实际结果，复现时使用测试账号与虚构数据。

1. Fork 仓库并建立主题分支。
2. 按 [开发指南](docs/development.md) 配置 JDK 与 Android SDK。
3. 运行相关单元测试和 Android Lint；终端渲染修改还应运行浏览器回归。
4. 涉及 SQLite、异步响应或离线操作时，在隔离模拟器运行对应仪器测试。
5. 提交前运行 `python scripts/audit-public.py`，确认没有凭据、私人地址、日志或真机会话数据。

提交中说明变更原因、验证方式和已知限制。请勿上传私钥、签名文件、真实账号、Bearer Token、手机数据库或包含私人消息的截图。

贡献的原创代码应有权以本项目 MIT 协议提供；引入第三方代码时保留原许可并更新 `THIRD_PARTY_NOTICES.md`。
