---
name: pitfall-dbx-docker-plugin-unknown-host-key
description: DBX Docker 插件 SSH 连远端 docker.sock 时强制校验主机密钥，~/.ssh/known_hosts
  缺该主机即连接失败；host/port 在 unix-over-nc 模式下不生效
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_eef345a4-d942-409b-9ce0-5739422a598e
---

DBX 的 `类型=plugin / driver=Docker` 连接若 `external_config.protocol = unix-over-nc`，实际链路是「SSH 登录远端 + 远端 `nc -U /var/run/docker.sock`」，**完全不碰 TCP 2375**。此时连接表里显示的 `host=127.0.0.1 / port=2375` 是残留字段：插件 manifest 里 `host`/`port` 带 `visible_when: protocol in [http, https]`，SSH 模式下不参与连接。所以不要为了「连不上」去给服务器开 2375 —— 那是把无鉴权的 root 级 Docker API 暴露公网。

真正会静默卡住的点是主机密钥：`dbx-docker-plugin.exe` 是 Go 程序，用 `x/crypto/ssh` + `knownhosts` 严格校验，二进制内含报错模板 `SSH host key for %s is not in %s. Add it with "ssh-keyscan %s >> %s", or set "SSH known_hosts path" ...`，且**没有** `InsecureIgnoreHostKey` 兜底（实测 0 命中）→ `ssh_known_hosts_path` 留空时按 `~/.ssh/known_hosts` 解析，缺这台主机就直接失败。

修法与核对：

1. `ssh-keyscan -t rsa,ecdsa,ed25519 <host>` 取三把 key（Go 协商的算法不定，三种都要有；`ssh-rsa` 条目同时覆盖 `rsa-sha2-*` 呈现）。
2. 与服务器本人自报的 `/etc/ssh/ssh_host_*_key.pub` 逐条比对，确认无中间人、密钥未变更；`ssh-keygen -lf` 看指纹。
3. 三点都要写：`~/.ssh/known_hosts`，以及 `%APPDATA%\com.dbx.app\known_hosts`（DBX 应用级那份，插件默认路径解析可能落到这里 —— 实证里它恰好只有北京 Docker 主机的 key）。
4. 验证不用连 Docker：`ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -o HostKeyAlgorithms=<alg> root@<host> true`，停在 `Permission denied (password)` 即 host key 已过。

同一套判据适用于任何「远端 docker.sock over SSH」的工具；排查顺序固定为：远端 `/var/run/docker.sock` 的 `/_ping` → 远端 `nc -U` 可用性 → 客户端 known_hosts。相关：[[reference-dbx-ssh-test-server]]、[[pitfall-dbx-generic-password-rotated]]。