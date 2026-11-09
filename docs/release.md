# 发布流程

1. 更新 `android-client/app/build.gradle` 的版本号和递增 versionCode、脚本包版本与 CHANGELOG。
2. 执行开发指南中的 JVM、Lint、浏览器及相关隔离模拟器回归；生成并校验签名 APK。
3. `git add` 后运行 `python scripts/audit-public.py --staged`，审查文件列表、差异和第三方许可。只提交公开源码；不要提交日志、真实消息、设备截图、私钥、签名密码或部署资料。
4. 提交审核后的源码，打 `v<version>` 标签。使用 `git archive --format=zip --prefix=OpenPocket-<version>/ -o <output.zip> <tag>` 生成源代码包，不能直接压缩工作目录。
5. 对 APK 与源码 ZIP 计算 SHA256，写入 `SHA256SUMS`。GitHub Release 附上这三个文件与版本说明。
6. 验证公共仓库、标签、下载地址和资产校验值。GitHub 自动源码归档与附带源码 ZIP 均对应发布标签。

CI 构建调试包并执行检查，不存储正式签名私钥。分发签名在维护者本地完成；SHA256 用于下载完整性核对，不能替代 APK 签名校验。
