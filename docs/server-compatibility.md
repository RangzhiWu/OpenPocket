# 服务器兼容说明

OpenPocket 是独立 Android 客户端。本仓库提供客户端与少量 Linux 终端辅助脚本，不包含完整 Open WebUI、Agent 网关、用量聚合服务或 OpenTerminal 部署。首次公开版尚未建立各上游版本的兼容矩阵；请先使用测试账号确认你部署的接口。

## 地址与认证

服务器地址应为根地址，例如 `https://webui.example.com`，不附页面路径、查询参数或账号密码。公网要求有效 HTTPS 证书，不跳过 TLS 校验。HTTP 只接受 localhost、回环、10/8、172.16/12、192.168/16、100.64/10 范围内的 IP；这不会自动建立 VPN 或授权服务器访问。

应用通过 `POST /api/v1/auths/signin` 使用邮箱和密码取得 `token`；后续 REST 请求带 `Authorization: Bearer <token>`，会话范围请求可带 `X-Session-Id`。反向代理应正确转发认证头与 WebSocket。

## 客户端调用的接口

| 功能 | 接口及约定 |
| --- | --- |
| 模型 | `GET /api/models`，对象中的 `data` 数组含模型 id/name |
| 会话列表 | `GET /api/v1/chats/?include_folders=true&include_pinned=true`，返回数组；更新时间用于缓存判断 |
| 详情 / 修改 | `GET/POST /api/v1/chats/{id}`，含 `id` 和 `chat`；models-only、title-only 更新必须保留其他字段 |
| 新建 | `POST /api/v1/chats/new`，请求含 `chat`，响应含 `id` |
| 生成 | `POST /api/chat/completions`，支持 `chat_id`、`id`、`parent_id`、`user_message`、`session_id`、`background_tasks` 等后台会话语义 |
| 任务 | `POST /api/tasks/chat/{id}/stop`；运行状态同时根据会话消息恢复 |
| 实时 | Socket.IO 路径 `/ws/socket.io`，具体认证与事件见 `LiveSession.java` |
| 附件 | `POST /api/v1/files/?process=...`、`GET /api/v1/files/{id}/process/status` |
| 用量扩展 | `GET /api/v1/users/usage?days=30`；响应结构见 `MainActivity.java` 的用量渲染 |
| 终端扩展 | `GET /api/v1/terminals/`，经 `/api/v1/terminals/{id}/...` 代理 OpenTerminal |

接口及字段以 `Api.java`、`CompletionRequest.java`、`MainActivity.java` 和 `TerminalApi.java` 为准。服务端缺少扩展时，相关页面可能报错；客户端不会自动创建服务端功能。模型选择来自服务器列表，优先选择存在的 `server-codex-agent`，否则使用第一个模型。安装应用不会安装 Codex / Claude CLI，也不会配置它们的 API Key、中转或地区访问。

## 交互终端

终端代理需支持文件列表、cwd、读写、上传，以及 `/execute?wait=0` 创建进程、`/execute/{pid}/status?wait=0&offset=...` 读取带偏移输出、`/execute/{pid}/input` 原始输入和 `DELETE /execute/{pid}` 停止。返回状态、输出数组与转义要求见 `TerminalApi.java`。执行环境需要 Bash、Python 3、可用的控制 PTY 与作业控制；普通按行执行的命令接口无法替代交互 PTY。

`server/terminal/` 提供三个可选辅助脚本：

- `bash-session.sh`：在交互 Bash 中维护隐藏提示标记，保留 sudo/su 前后的目录和状态协议。
- `shell-token.py`：沿父进程读取当前 OpenPocket shell 标记。
- `resize-terminal.py`：根据标记找到原 PTY 并设置行列数。

如需启用，将脚本以管理员所有、普通用户不可写的方式放到 `/usr/local/lib/openpocket/`。Bash 会话辅助脚本需由交互 shell 初始化文件加载，例如在服务端 `/etc/bash.bashrc` 中显式 source；部署者需自行审查与现有提示符配置的交互。普通终端没有标记时会保持原配置。

客户端尺寸同步固定调用 `sudo -n /usr/bin/python3 /usr/local/lib/openpocket/resize-terminal.py <token> <cols> <rows>`。脚本默认 shell 账号为 `webui-agent`，可在管理员控制的部署副本中改为实际服务账号，或由 sudo 保留管理员配置的 `OPENPOCKET_SHELL_USER` 环境变量。不要让调用者任意选择目标账号；部署者应审查并限制 sudo 规则到该辅助入口，不能授予通用 Python 或 shell 的免密管理员权限。本仓库不自动修改 sudoers。

客户端通过已认证的 Open WebUI 代理访问终端，APK 不保存终端管理员密钥。不要直接将无鉴权终端端口暴露到公网。
