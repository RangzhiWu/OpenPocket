# 发布流程

1. 更新 `android-client/app/build.gradle` 的版本号和递增 versionCode、脚本包版本与 CHANGELOG。
2. 按开发指南执行构建、Android Lint 与相关场景验证；生成并校验签名 APK。
3. `git add` 后运行 `python scripts/audit-public.py --staged`，审查文件列表、差异、截图和许可。测试夹具、个人构建安装脚本、日志、真实消息和签名资料不入库。
4. 提交审核后的源码，打 `v<version>` 标签。用 `git archive --format=zip --prefix=OpenPocket-<version>/ -o <output.zip> <tag>` 生成源码包，不能直接压缩工作目录。
5. 对 APK 与源码 ZIP 计算 SHA256，写入 `SHA256SUMS`。GitHub Release 附上这三个文件与版本说明。
6. 验证公开仓库、标签、下载地址与资产校验值。自动源码归档和附带源码 ZIP 均对应发布标签。

CI 执行公开文件检查、Android Lint 与调试构建，不存储正式签名私钥。正式签名在维护者本地完成；SHA256 用于下载完整性核对，不能替代 APK 签名校验。
