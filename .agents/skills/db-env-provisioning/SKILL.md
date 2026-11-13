---
name: db-env-provisioning
description: 数据库真机环境搭建与验证作业法——在远程 Linux 靶机上用 Docker 部署国产/商业数据库（瀚高、海量、OceanBase、GaussDB、SQL Server、MariaDB、达梦、金仓、磐维 PanWeiDB 等）、选镜像源、找不到官方镜像时按矩阵取证并降级到第三方重打包镜像、配端口与凭据、登记 DBX，并用插件自己的渲染套件在真引擎上验证；外部连不上时按「宿主机端口→DNAT 目标→容器监听地址→pg_hba 来源」四层排查（§4.5）。当用户要求「装个 XX 数据库」「补真机环境」「部署测试实例」「镜像拉不动」「找不到官方镜像」「实例连不上」「真机读数怎么取」「外部连接被拒/握手断开」「整机重启后库不见了」时使用。
---

# db-env-provisioning 数据库真机环境作业法

靶机固定是「不大-北京-16G」（`103.36.220.245`，Ubuntu 22.04 / 16C / 15G / Docker 28，SSH 走 DBX 连接
`2af473a2-f9fc-4478-846a-4b5202d606c5`）。本文件是把 2026-10 这一轮部署十来个实例踩出来的东西固化下来，
**照单执行能省掉至少一半返工**。

四条铁律先记住：

1. **端口只用 20000–29999**（用户定的，安全组已整段放行）。容器内端口照旧（5432/3306/1433/2881），
   宿主一律映射到这个段里，别把 5432 这类常用端口暴露出去。
2. **凭据只落 `.local/`**（`.local/test-databases.md`），仓库里的 skill/文档只写 `host:port` 与库名。
   同理，厂商镜像的 license/trial 状态、密码策略这些坑要写进台账，别只留在脑子里。
3. **容器一律 `--restart unless-stopped`**，并且**数据卷/数据目录不要随手重建**——多数实例的账号与库
   是首次 initdb 时按环境变量建的，重建等于从头再来一遍凭据流程。
4. **靶机随时可能被重装系统**，所以凡是部署都得能「从零重放」：实例清单 + 种子脚本 + `scripts/bootstrap-db-env.sh`
   是资产，手工 `docker run` 出来的东西不是。见 §9。

## 0. 开工前先查五件事

```bash
docker ps --format '{{.Names}}\t{{.Status}}\t{{.Ports}}' | sort   # 已有什么、端口占用
free -g | head -2                                                # 余量（见下「内存账」）
docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}'      # 镜像是不是已经有了
cat /etc/docker/daemon.json                                      # 已配的 mirror（不代表好用）
```

第五件是**读台账**：`.local/db-env-instances.tsv` 是实例索引（有哪些实例、宿主端口、镜像/卷、内存与坑，
字段见 §9），`.local/test-databases.md` 是连接、凭据与已知坑；`tmp/xc3-env-init/*.md`
是每个实例的部署原文与探针读数。**先看有没有现成实例能用**，别上来就装——`114.66.55.245` 那台
DBX 测试主机上本来就有一批（MySQL/PG/Oracle/DM/金仓/GBASE/虚谷/StarRocks/Doris…），很多时候只缺驱动。

内存账（15G 宿主上在役实例的实际配法，超了会 OOM 或让引擎起不来）：

| 实例（容器名） | `--memory` | `--shm-size` | 其他必带 flag / 引擎内记账 |
|---|---|---|---|
| 瀚高 `xc3-highgo` | 3g | — | 命名卷 `xc3_highgo_data` |
| 海量 A `xc3-vastbase` | 4g | 2g | 停起走 `/opt/xc3-vastbase/start.sh` |
| 海量 B `xc3-vastbase-b` | 2g | 1g | `DBCOMPATIBILITY=B` |
| OceanBase `xc3-oceanbase` | 8g | — | 容器内 `OB_MEMORY_LIMIT=6G`；`--ulimit nofile=65535:65535 --ulimit stack=-1` |
| SQL Server `xc3-mssql` | 2g | — | `MSSQL_MEMORY_LIMIT_MB=1536`（首启不落 `mssql.conf`，实际靠 cgroup） |
| GaussDB `xc3-gaussdb-kernel503` | 4g | 2g | `--init` 必须 |
| openGauss `xc3-opengauss` | 4g | 2g | `--privileged --ulimit nofile=65535` |
| MariaDB `xc3-mariadb` | 2g | — | |
| MySQL `xc3-mysql8` | 1g | — | 命名卷 `xc3-mysql8-data` |
| **Oracle Free `xc3-oracle-free`** | **2.5g**（治理后 cap，旧 4g） | — | `gvenzl/oracle-free:23-slim`；`-p 21521:1521`；service `FREEPDB1`；账号 `probe`（**口令按 §0 铁律 2 只落 `.local/`，出处：demo 工作树 `oracle-demo/src/main/resources/application.yml`**）；库内 spfile 备份在 `/opt/oracle/oradata/dbconfig/FREE/`。**2026-11-12 补登**：它是 2026-11-04 建的专项复现实例，凭据未进 `.local/test-databases.md`，故 11-06 抄录 TSV 时整体漏掉；本次恢复已按台账补登 |
| 磐维 A `panweidb-ora` | 3g | 2g | `--privileged`；`dbcompatibility=A`；引擎内 `max_process_memory=8GB`（记账上限，非预占）、`shared_buffers=256MB` |
| 磐维 B `panweidb-test` | 4g | 2g | `--privileged`；`dbcompatibility=B`；同上引擎记账 |

**别把这张表当预算加总**：12 个实例的 `--memory` 合计约 **39.5g**，宿主只有 15G——limit 是记账上限不是预占，
真正要盯的是 `free -m` 的 available 与实际 RSS（磐维压过 `shared_buffers` 后单实例约 0.9GB）。

**内存闸门（起新库前必看读数，不是「大概够」）**：2026-11-06 磐维两个实例上线后，16G 宿主 + 11 个在役容器的
`available` 只剩约 **966 MB**（`Mem: 16003 total / 9068 used / 966 available`）。且**国产库常有引擎内存下限**，
不是想压就压：磐维 `max_process_memory` 低于约 5057MB 直接拒启（见 §2 磐维段，唯一能压的是 `shared_buffers`）。
所以流程固定为 **先 `free -m` 看 available → 不足就先腾（`docker stop` 掉当前不用的实例，或压 `shared_buffers`）→
再起新的**，别先起后救。腾量口径别算错：停实例腾出来的是**实际 RSS 不是 limit**（MariaDB 空闲时只有
几百 MB），真要腾出 GB 级余量就停 OceanBase（容器内 `memory_limit=6G`）或一个磐维实例（压过
`shared_buffers` 后约 0.9GB/实例）。

## 1. 选镜像：先解决「拉不动」

**别干等 Docker Hub。** 这台机器直连单层实测 ~76KB/s（mariadb 拉 25 分钟没完），而 `daemon.json` 里
配着的三个 mirror（gmssh/icu/librax）不代表在快路径上。

选源要实测，两种口径都行：

```bash
# 口径一：小镜像比墙钟（3MB 的 alpine 足够区分快慢）
for m in docker.m.daocloud.io hub.rat.dev docker.gmssh.com docker.xuanyuan.me docker.1ms.run; do
  s=$(date +%s%3N); docker pull -q $m/library/alpine:3.19 >/dev/null 2>&1; e=$(date +%s%3N)
  echo "$m $((e-s))ms"
done

# 口径二：读 dockerd 的写入增量算吞吐（长拉取时用）
p=$(pgrep -f dockerd | head -1)
a=$(awk '$1=="write_bytes:"{print $2}' /proc/$p/io); sleep 10
b=$(awk '$1=="write_bytes:"{print $2}' /proc/$p/io); echo "$(( (b-a)/10/1024 )) KB/s"
```

`awk` 必须写 `$1=="write_bytes:"` 精确匹配，否则 `cancelled_write_bytes` 会把读数算成天文数字。

2026-10-30 实测排名：`docker.m.daocloud.io` 685ms 最快，`hub.rat.dev` 1.1s、`docker.gmssh.com` 2.0s、
`docker.xuanyuan.me` 9.6s、`docker.1ms.run` 19.4s。**换源重拉会复用已下载的层**（日志里 `Already exists`），
所以中途换源不吃亏，不必从头来。

**2026-11-12 整机重建新增两条硬教训**（Docker 从 28 升到 **29.9.3** 后暴露）：
1. **多路 `docker pull` 并发会抢同一个跨镜像共享基础层**，三组并行时全部卡在 futex_wait、
   十几分钟零落盘；实测单路跑同一镜像层陆续 `Download complete`、几分钟提交成功。
   ⇒ 重建时**全局任意时刻只允许 1 个 docker pull**，按组错峰（先小镜像组、后 2.9GB 磐维），
   每拉完一个 `docker images` 确认提交再拉下一个。不要靠「各组自己串行」——组间也会互抢。
2. **Docker 29 默认用 containerd image store**（`docker info` 显示 `driver-type: io.containerd.snapshotter.v1`），
   层落在 `/var/lib/containerd/io.containerd.content.v1.content`（**不受 `data-root: /www/server/docker` 管辖**）。
   所以「`/www/server/docker` 只有 236K、`docker images` 却没有镜像」不一定是没落盘——
   要看 containerd content store 的 `du -sh`（本次 2.3G 层已落，只是镜像 manifest 未提交）。
   别据此误判成「镜像源返回残缺 manifest」而白折腾换源。
3. **daocloud 有白名单**：`docker.m.daocloud.io/xuxuclassmate/highgo:6.0.1` 直接回
   「this image is not in the allowlist」，但 `library/mariadb:10.11.11` 正常。
   ⇒ 个人/社区命名空间镜像（`xuxuclassmate/*`、`apecloud/*`、`gvenzl/*`）走 `hub.rat.dev` 或
   `docker.1ms.run`；官方 library 镜像才用 daocloud；`mcr.microsoft.com` 直连。
   ⚠️ 这只针对 **`docker pull`**。走逐层 curl 兜底时白名单只挡 manifest、不挡 blob 直取，
   见本段第 6 条——那时 daocloud 反而是最快的 blob 源。
4. **并发抢锁后即使改成单路也可能继续卡**：本次清空所有 pull 后单路拉 mariadb，仍停在
   `futex_wait_queue_me`、content store 不增长——卡住的半成品 pull 状态留在 containerd 里。
   **处置**：确认 `docker ps -a` 无容器 → `pkill -TERM -f 'docker pull'` → `systemctl restart docker`
   （无容器时安全）→ 再单路拉。别只清用户态 pull 进程就以为状态干净了。
5. **⭐ 最快路径其实是「复用 containerd content store 里已落盘的层」**（2026-11-12 B 组发现，
   决定性的提速）：`docker pull` 虽然提交不了镜像，但**层其实已经下载并落进**
   `/var/lib/containerd/io.containerd.content.v1.content/blobs/sha256/<64hex>`（文件名就是 digest，
   不带 `sha256:` 前缀，大小即原 blob 大小）。本次实测 **68 个 blob / 2.4G**，含 926MB、429MB、
   374MB、259MB、181MB 等大层，覆盖 vastbase / highgo / opengauss / oracle / panweidb 的绝大多数层。
   ⇒ 重建时**第一步就该枚举它**：
   ```bash
   find /var/lib/containerd/io.containerd.content.v1.content/blobs/sha256 -type f -printf '%f %s\n' | sort -k2 -n
   ```
   拿 manifest 里每层 digest 直接 `test -f <content-store>/<digest>`，命中就
   `ln -f <content-store>/<digest> <WORK>/<digest>.tar.gz`（硬链接，秒级、不占带宽），
   让逐层脚本的「已完整 → sha256 校验 → SKIP」分支直接过；只缺极少数层才走网络。
   本次若一开始就查这里，能省掉两三个小时的逐层 curl 与 4 小时慢通道。
   ⚠️ 别只看 `docker images` 空、`data-root` 小就以为层没落盘——Docker 29 的 content store
   在 `/var/lib/containerd/`（与 `data-root` 分离），`du -sh` 它才是真相。

6. **最坏情况：`docker pull` 整体不可用，走 §1 的逐层 curl + `docker load` 兜底**（2026-11-12 实测）。
   三次单路 `docker pull`（hub.rat.dev / daocloud / gmssh）都在「Pulling from」后退出、
   `docker images` 一个镜像都没提交；而 curl 取 manifest 秒回（http=200）、containerd content store
   已 2.3G（层落了）⇒ 卡的是 **blob 下载 / pull→image 提交**，不是 manifest、不是源不可达。
   可行路径：registry v2 匿名 token → OCI index 选 amd64 → 子 manifest → **串行**下每个 blob
   并校验 sha256 → 组 `docker save` 格式的 tar → `docker load` → `docker image inspect` 验提交。
   `docker.1ms.run` 的匿名 token realm 是 `/openapi/v1/auth/token`（不是 `/token`），
   对 library 镜像不限白名单；daocloud 的 realm 是 `m.daocloud.io/auth/token`。
   逐层**串行**（并发 8 路会 429），一次只处理一个镜像、一个 blob。
   若 `docker load` 也提交不了，再查 `docker info` 的 containerd/snapshotter 段。
7. **逐层兜底时选 REG 要看 blob 实测速度，别凭印象**（2026-11-12 B 组实测，同一 76MB 共享层 12s curl）：

   | REG | http | speed |
   |---|---|---|
   | **`docker.m.daocloud.io`** | 200 | **0.46MB/s**（最快） |
   | `docker.gmssh.com` | 200 | 28KB/s |
   | `docker.1ms.run` | 200 | 17KB/s |
   | `hub.rat.dev` | 401 | blob CDN 不认其 token |

   daocloud 比 1ms.run 快约 28 倍；按 0.46MB/s 一个 8 层 105MB 的镜像约 4 分钟，按 17KB/s 要一小时以上。
   **另一个反直觉点：daocloud 的白名单只挡 `docker pull` 这条路径，但白名单是「按 repo」放的**：
   - B 组实测 `apecloud/vastbase` 的 token + OCI index + amd64 manifest + config blob 全 200
     （digest 与部署原文逐字一致）⇒ 单 REG=daocloud 一路走通；
   - A 组实测 `library/mariadb`、`library/mysql:8.0-debian` 放行，但 **`xuxuclassmate/highgo` 与
     `opengauss/opengauss` 的 manifest 和 blob 都 403**（带有效 token 也一样）。
   ⇒ **每个 repo 都要单独探一次**，别从「apecloud 通」推出「所有个人命名空间都通」。
   **最终结论（A 组 14:26 发现 → 14:43 反证 → 14:47 定论）**：**daocloud 放行的是「它本地已缓存的 blob」，
   未缓存的回源一律 403，与 repo 路径 / token scope 是否一致都无关**。
   - 共享基础层 `2d473b07…`（highgo 与 vastbase 都用）已缓存 ⇒ 用什么路径/scope 都 200、0.46MB/s；
   - highgo 独有层（config `44b2c83d…`、L1、L9）未缓存 ⇒ 换成 `library/mariadb` 路径 + 同 scope token
     逐一 HEAD 仍 **403**，换成目标 repo 路径同样 403；
   - **`opengauss/opengauss:5.0.0` 同样全未缓存**（A 组把 config + 8 层共 9 个 digest 逐个 HEAD **全 403**），
     504MB 只能走 1ms.run 慢通道（L2 195MB 实测 48KB/s、约 3 小时）。
   ⇒ 别以为「官方组织命名空间」就一定能走 daocloud 快通道，判据是**该 blob 是否已缓存**，不是命名空间。
   ⇒ 别再把「blob 端点不校验 scope」或「改 library 路径就通」写进口径，两条都被反证推翻。
   可行路径：**manifest 从 `docker.1ms.run`（token realm `/openapi/v1/auth/token`）取小 JSON，
   未缓存的 blob 只能回 1ms.run / gmssh 慢通道**（实测 L9 109MB 约 28KB/s，412MB 全量约 4 小时），
   配 7200s + `-C -` 续传；已缓存的共享层用 `find_cached()` 从别组工作目录复用。
   ⇒ 排期上**把能走快通道的镜像先落地、未缓存的镜像放最后慢慢下**，别让它阻塞整组交付。
   `dockerhub.icu`/`register.librax.org` 实测 000 不通；阿里云 ACR 公共域名对个人命名空间 401。
   ⚠️ **`dockerproxy.net` / `docker.gmssh.com` 的「manifest + 小 config blob 200」不等于大 blob 通道可用**：
   主控实测两者对 highgo 的 manifest(3243B) 与 config(9349B) 都 200，但对最大层 L9(109MB)
   17s curl 零输出（连响应头都没有）。判快通道必须拿**大层**测速，别拿小 JSON 的 200 下结论。
   **SQL Server 例外**：`mcr.microsoft.com/v2/mssql/server` 的 manifest 与 blob **都免 token、直接 200**
   （A 组实测），给它单独走 `BLOB_BASE=https://mcr.microsoft.com/v2/mssql/server` + `BLOB_AUTH=none` 即可，
   不必绕 daocloud。脚本模板应把 `BLOB_BASE` / `BLOB_AUTH` 做成环境变量。
   daocloud 的 token realm 是 `m.daocloud.io/auth/token?service=docker.m.daocloud.io&scope=repository:<repo>:pull`
   （service 值两种写法都实测可用）。
   ⚠️ 并发分摊：多路逐层下载同时在跑时会抢出口，独占测速 0.46MB/s 在三方并发下实测掉到 0.07~0.1MB/s，
   排期要把这点算进去，别拿独占读数估总时长。
   ⚠️ **慢的往往是「特定冷门 digest 的回源」而不是通道整体带宽**（B 组 14:28 实证）：
   daocloud 上 mariadb 前 6 层 0.9MB/s、3 秒级完成，唯独某个共享基础层 `2d473b07cdd5`（76MB）
   卡在 ~0.023MB/s 十几分钟；这个 digest 正是三组 `docker pull` 都卡住的元凶层，
   同时出现在 highgo 与 vastbase 的层列表里。⇒ 别因为某一层慢就换源重跑（新源回源更慢、
   还要丢已下字节）；该层下完后其余层通常恢复 0.4~0.9MB/s。
   若多个镜像共享同一 digest，可把先下完的 blob 文件给后一个脚本复用（内容寻址通用）：
   逐层脚本在 `docker load` 前会把 `<digest>.tar.gz` 改名到 `<hash>/layer.tar`（**字节不变、sha256 仍等于原 digest**），
   所以复用时去先跑完那个工作目录里按 `<digest 前 64 位>/layer.tar` 找；让先跑完的组**保留工作目录别删**，
   后一组直接拷贝/硬链接即可，省一份冷门层的回源时间。
   ⚠️ **停旧任务要连 curl 子进程一起停**：`kill` 掉 bash/python 父进程后，正在下 blob 的 curl 会变成
   **孤儿（PPID=1）继续下载十几分钟**白占带宽（本次实测）。切源重跑前先
   `ps -eo pid,ppid,cmd | grep 'curl.*blobs'`，确认父进程已 GONE 的孤儿 curl 全部收掉。
   ⚠️ **逐层脚本的 blob `--max-time` 必须给足并支持断点续传**：独占测速 0.46MB/s 时 76MB 层约 165s，
   `900` 够用；但三方并发掉到 ~0.023MB/s 后同一层要 **3300s**，900s 会被 curl 掐断、脚本 abort。
   **2026-11-12 实测踩中**：`curl: (28) Operation timed out after 899017 milliseconds with
   37870706 out of 76097157 bytes received`（冷门层 ~42KB/s，900s 只下了 37.87/76MB）→ `py_rc=1`。
   模板固定写 `curl -C - --max-time 7200`（`-C -` 让重跑续传已下字节），且脚本要支持
   **`B_WORK` 固定工作目录**（否则重跑新建随机目录、37MB 白丢）+「目标文件已存在且完整→跳过；
   不完整→续传」分支。正在跑的进程读入的是旧值，改脚本后若该层超时用新脚本重跑。
   ⚠️ **`-C -` 对已完整的文件会返回 HTTP 416**：续传分支若没先判「文件已完整」就发 Range，
   curl 输出 `416`（Range Not Satisfiable）但不算致命错误，脚本别把它当下载失败；
   正确顺序是「已存在且 sha256 通过 → SKIP；不完整 → `-C -` 续传」。
   最终判据一律是 `docker image inspect`，不看中间 curl 的状态码。
   ⚠️ **别在任务运行中改脚本**：本次 A 组一边跑 mariadb 一边给脚本加「本地 digest 复用」分支，
   在途进程读到了写到一半的中间态，日志里炸出
   `line 127: syntax error near unexpected token ')'`（内嵌 Python 的 `os.rename(...)` 泄漏到 shell）；
   而事后对磁盘上的当前版本 `bash -n` 是过的。⇒ 改脚本前先确认没有在跑的进程读它，
   改完 `bash -n` + 内嵌 Python `compile` 双检，再启动新任务；看到这种语法错先复核磁盘版本，别白修。

8. **镜像源的默认口径以轩辕官方 Agent 文档为准**（用户 2026-11-12 提供
   `https://xuanyuan.cloud/agents.md` 并要求遵守）：
   - **先读文档再生成 pull 命令或排错方案**；该文档 §0.2 把 `daemon.json` 的 `registry-mirrors`
     列为「禁止作为 Agent 默认」的第 5 优先级，§2.2 MUST 优先生成
     **`docker pull docker.xuanyuan.run/<原始路径>`**（docker.io 公共登录域，显式域名）。
   - 首次按 §1.2 检查 `~/.docker/config.json` 的 `auths`；无条目或 401 才按 §1.3 一次性
     `docker login -u <镜像账户> --password-stdin docker.xuanyuan.run`（凭据在个人中心
     「用户信息 → 镜像仓库信息」，网站登录密码即镜像密码；**已登录 MUST NOT 重复 login**）。
   - 非 docker.io（mcr/ghcr/gcr/...）按 §2.1 用 `***-{slug}.xuanyuan.run` 专属域、
     **禁止 login**；用户未给专属前缀时 §1.5 禁止编造，改走该镜像已证可用的缓存通道。
   - **本仓用户的专属前缀**：`6rzy4mgbhtco8j6ztc`（2026-11-12 用户提供）。
     docker.io 主专属域 = `6rzy4mgbhtco8j6ztc.xuanyuan.run`（免 login，§2.2 备选方案）；
     非 docker.io 按 §1.5 拼 `{前缀}-{slug}.xuanyuan.run`，如 SQL Server 用
     `6rzy4mgbhtco8j6ztc-mcr.xuanyuan.run/mssql/server:2022-latest`（禁止 login）。
     两种格式用错后缀是 §3 最常见错误：主专属域**没有** `-suffix`。
   - 免费版才用 `docker.xuanyuan.me`（§2.4）；专业版默认 `.run`。
   - **MUST NOT** pull 失败默认换官方源 / 删除轩辕前缀（§3）；先按 §3.2 分类错误跳对应 FAQ，
     再按 §3.3「重试轩辕路径 → 换 tag → 重试 pull」，只有域名完全不可达或用户明确要求才建议官方源。
   - `manifest unknown` 先按 §2.7 查 hub.docker.com 仓库/tag 是否存在，别盲目 fallback。
   实测：专业版登录 `Login Succeeded` 后，highgo 首次 pull 在 69MB 大层报
   `net/http: timeout awaiting response headers`（§3.2 归 i/o timeout），按 §3.3 第 4 步**重试即成功**
   —— `Status: Downloaded newer image`、`docker.xuanyuan.run/xuxuclassmate/highgo:6.0.1 1.51GB`，
   约 3 分钟（~8MB/s），比 daocloud 逐层快两个数量级。
   ⚠️ **`docker pull` 报 timeout 不等于没进度，但也不保证层会保留**（两种失败形态，别一概而论）：
   - highgo 首次 `net/http: timeout awaiting response headers`（大层响应超时）→ 5 个大层
     69/80/77/109/76MB 全落进 content store（68→76 blob），重试即成功；
   - gaussdb 公共域 5 层全 `Download complete`/`Pull complete`，**末段一个额外 blob 超时触发 dockerd 回滚**，
     把 5 层从 content store 清理了（82→76 blob，B 组 15:14 实证）⇒ content-store 复用兜底失效。
   ⇒ 判别口径：失败后**立刻** `du -sh` content store 并按 digest 查在不在；在就复用、不在就按
   §3.3 重试轩辕路径（换专属域 / 公共域都合规），别只依赖「层一定还在」。
   ⚠️ **轩辕不接受 curl 直连 registry v2 API 的 Basic 认证**（B 组实测 403：
   「当前使用的认证方式不受支持，请使用 docker login 命令重新登录后再试」），
   只读测速这条路拿不到速度读数；判断快慢只能跑一次合规的 `docker pull`。

9. **ACR 直连是独立通道，不必跟 Docker Hub mirror 一起排队**（2026-11-12 实测）。
   磐维 `registry.cn-shenzhen.aliyuncs.com/shine_fei/panweidb:3.4.0` 走阿里云 ACR：
   401 challenge → `dockerauth.cn-hangzhou.aliyuncs.com/auth` 匿名 token → OCI index（4 条目）
   → amd64 子 manifest（digest `sha256:0a7df528…`，10 层、压缩约 1.79GB）→
   blob `HEAD` 200、`GET` 307 到 CDN（**必须 `curl -L` 跟随**），实测 **8.98MB/s**、82MB 层 9.2 秒下完。
   与 Docker Hub mirror 的故障完全无关，可与其它组的逐层加载**并行**跑，不必等 A→B→C 让出通道。
   同理 `mcr.microsoft.com`（SQL Server）也是独立通道，可单测。

拉下来后重打本地标签再起容器，脚本里写不带 mirror 前缀的名字：

```bash
docker pull docker.m.daocloud.io/library/mariadb:10.11.11
docker tag  docker.m.daocloud.io/library/mariadb:10.11.11 mariadb:10.11.11
```

例外：`mcr.microsoft.com`（SQL Server）直连即可，不走 Docker Hub 也不受上面这套影响。
`dockerproxy.net` 逐层 curl 是最后的兜底（瀚高那次 daemon 对多分层镜像挂死就是用它救的：
按 registry v2 API 下 blob → 校验 sha256 → 组 manifest → `docker load`；注意并发 8 路会 429，要串行）。

### 国产库容器镜像：找不到官方镜像时怎么取证、怎么降级（2026-11-06 磐维 PanWeiDB 实录）

国产库（尤其运营商系）经常**根本没有官方镜像**。这时要交出「我查过、查了哪些源、各自什么结果」的取证，
不是一句「搜不到」。按下面的矩阵逐源走，**HTTP 状态与命中数逐行记进报告**：

| # | 源 | 探法 | 判读 |
|---|---|---|---|
| 1 | Docker Hub | `hub.docker.com/v2/search/repositories/?query=<名>` | 200 + `count`。**必须先跑一次对照查询**（如 `query=opengauss` 回 111 条）证明接口本身正常，否则本产品的 0 不能当结论 |
| 2 | quay.io | `quay.io/api/v1/find/repositories?query=<名>` | 200 + `results: []` 同口径 |
| 3 | ghcr.io | 匿名 token（`ghcr.io/token?scope=repository:<n>:pull`）→ `manifests/latest` | token 环节就拿不到时，**对照公开仓库也拿不到 ⇒ 该源不可信**，结论只能写「未取到」，禁写「没有」 |
| 4 | 阿里云 ACR | `registry.cn-<区>.aliyuncs.com/v2/<ns>/<repo>/tags/list` | 401 只是**未鉴权**（不代表不存在）；走匿名 token 流才能判 200/404 |
| 5 | 华为云 SWR / 腾讯云 ccr | 同 `/v2/<ns>/<repo>/tags/list` | 401 需凭据，如实登记「需鉴权未继续」 |
| 6 | 厂商官网 / 社区 | `panwei.tech`、`open.10086.cn`、GitHub/Gitee 搜产品名 | 常只有白皮书与 tarball 安装介质——**记形态，别硬转成镜像**；顺带拿到 tarball 命名（生产/OM 两个包）对后续自建有用 |

本地已有 PDF/文档时先 grep 一遍（`pdftotext` + `grep -i "docker\|镜像"`）：**文档全篇不提容器**本身
就是「无官方镜像」的一条旁证，比纯搜索结果更硬。

阿里云匿名 token 流（ACR 仓库设为 public 即可匿名列 tag，2026-11-06 实测可用）：

```bash
# 1) 取 challenge，读出 realm / service / scope
curl -sI "https://registry.cn-shenzhen.aliyuncs.com/v2/<ns>/<repo>/manifests/<tag>" | grep -i www-authenticate
# 2) 用同一 scope 换匿名 token（公开仓库不鉴权也发）
curl -s "https://dockerauth.cn-hangzhou.aliyuncs.com/auth?service=registry.aliyuncs.com:cn-shenzhen:26842&scope=repository:<ns>/<repo>:pull"
# 3) 带 token 列 tag；取 manifest 要带 Accept: ...manifest.v2+json 或 ...oci.image.index.v1+json
curl -s -H "Authorization: Bearer <token>" "https://registry.cn-shenzhen.aliyuncs.com/v2/<ns>/<repo>/tags/list"
```

命中后**先把 digest 与包内版本钉死再起容器**：list 出 tag → 取 index manifest 拿 amd64 manifest digest →
`docker pull` 后在容器内验产品版本（磐维是 `/root/PanWeiDB_db.tar.gz` 与解包出的
`PanWeiDB_V3.0-S3.4.0_B01-Linux-64bit.tar.bz2`），**别用 `latest`，也别用 tag 名猜版本**。

**个人命名空间重打包镜像：怎么识别、怎么定级**

先看包内有没有真厂商产物，再定性：

- **真厂商产物指纹**：包内是产品自有安装器与目录结构（磐维：`script/gs_install`、`script/gs_om`、
  `/root/PanWeiDB_db.tar.gz`、角色 `pwadmin`/`pwaudit`/`pwsso`、`gs_initdb --dbcompatibility`）。
  ⇒ 这是**厂商 tarball 被第三方重打包**：内容是真的，但**分发链无人背书**（版本来源、是否被改、有无夹带都不可证）。
- **改名冒牌**：包内只有 openGauss/GaussDB 通用件、没有任何该产品专属指纹 ⇒ **禁止**当该产品用
  （这正是本 skill 开头「不拿 openGauss 冒充」红线的落点）。

定级与写法：**只作内部验证环境**；对外结论与文档必须逐处标注来源（仓库全名 + digest + 构建时间）并写明
「第三方重打包，非厂商官方分发」；长期使用建议向厂商索取官方分发，或直接用厂商 tarball 自行构建。

### 各产品的实测坐标（都在册，可直接复用）

| 产品 | 镜像 | 宿主端口 | 特殊要求 |
|---|---|---|---|
| 瀚高 HighGo V6.0.1 | `xuxuclassmate/highgo:6.0.1`（社区，PG 12.3 内核） | 25866→5866 | 数据卷命名卷；`HIGHGO_PASSWORD` 只在首次 initdb 生效 |
| 海量 Vastbase A 模式 | `apecloud/vastbase:v3.0.9.2-ape1` | 25432→5432 | KubeBlocks entrypoint，需一整套 env（见 §2） |
| 海量 Vastbase B 模式 | 同上镜像 | 25433→5432 | 只改 `DBCOMPATIBILITY=B` + 容器名/端口/内存 |
| OceanBase CE 4.3.5 | `oceanbase/oceanbase-ce:4.3.5-lts` | 25881→2881 | **必须 `--ulimit nofile=65535:65535 --ulimit stack=-1`** |
| GaussDB 商用内核 Kernel 503 | `apecloud/gaussdb:2.23.01.200-r2` | 25882→5432 | 无 entrypoint，手工 `gs_initdb` + `gs_ctl`；**`--init` 必须** |
| SQL Server 2022 | `mcr.microsoft.com/mssql/server:2022-latest` | 21433→1433 | `ACCEPT_EULA=Y`；首启 90~120s 才接受连接 |
| MariaDB 10.11 | `mariadb:10.11.11` | 23306→3306 | 一行 env 起库，最省事的一个 |
| 磐维 PanWeiDB V3.0-S3.4.0_B01 | `registry.cn-shenzhen.aliyuncs.com/shine_fei/panweidb:3.4.0`（**阿里云个人命名空间，第三方重打包**；index digest `sha256:18ecce71…`） | 26100→17700（B 档）/ 26101→17700（A 档） | openEuler+systemd，开机 ansible 自动装（约 10 分钟）；容器内端口 **17700**；**需三处补丁**（见 §2 磐维段）；兼容档安装期决定 |
| 达梦 DM8 | 无 Docker 官方镜像 | — | 用现成实例 `114.66.55.245:20096`（DBX 在册） |

## 2. 起容器：通用骨架 + 各产品必带的 flag

通用骨架：

```bash
docker run -d --name xc3-<产品> \
  --restart unless-stopped \
  --memory=<见内存账> \
  -p <20xxx>:<容器内端口> \
  [-v <宿主目录或命名卷>] \
  [产品专属 env/flags] \
  <镜像>
```

产品专属的东西，一个都不能省：

**OceanBase**——`--ulimit` 是硬门槛，不是调优。第一次起会打假 `boot success!`：镜像的 start.sh 在 obd
启动失败后照样打印这句话，但 2881 根本没监听。翻全量日志能看到真因
`OBD-1007: ulimit "open files" 1024 < 20000`。修法：`docker rm -f` → 清空持久化目录 →
`sync; echo 3 > /proc/sys/vm/drop_caches`（拉镜像后 buff/cache 会把 MemFree 压到 1G，
前检需要 6G）→ 重跑加 `--ulimit nofile=65535:65535 --ulimit stack=-1`。
判定成功的标准不是那句 `boot success!`，而是日志链 `Connect to observer ok` → `obshell program health
check ok` **加上** obclient 实连 `SELECT 1` 通。

**GaussDB（apecloud）**——镜像没有 entrypoint，`CMD=["bin/bash"]`，得手工起，且三步都有硬要求：

```bash
# 1) 宿主数据目录归 uid 70（容器内 omm），data 目录 chmod 700
# 2) 常驻容器：--init 必须，否则 PID1 是 tail，僵尸进程不回收会让 gs_ctl restart 卡死
docker run -d --init --name xc3-gaussdb-kernel503 --memory=4g --shm-size=2g --restart unless-stopped \
  -p 25882:5432 -v /www/xc3/gaussdb:/home/omm/probe \
  --entrypoint /bin/bash apecloud/gaussdb:2.23.01.200-r2 -c 'tail -f /dev/null'
# 3) gs_initdb 强制要求 --nodename / --host-ip / --undostoragetype，缺一个直接报错
docker exec -u 70:70 -e GAUSSHOME=/opt/gaussdb/app -e LD_LIBRARY_PATH=/opt/gaussdb/app/lib \
  -e PATH=/opt/gaussdb/app/bin:/usr/bin:/bin -w /home/omm <容器> \
  gs_initdb -D /home/omm/probe/data -U omm -w '<密码>' --encoding=UTF-8 --locale=C \
  --nodename=xc3probe --host-ip=127.0.0.1 --undostoragetype=page --dbcompatibility=A
# 4) gs_ctl start -D ... -Z single_node -l ...
# 5) 容器内 gsql 直连必须带 -h127.0.0.1 与 LD_LIBRARY_PATH=/opt/gaussdb/app/lib（缺则报
#    libcjson.so.1 cannot open shared object file，与 openGauss 同款）；口令容器 env 用 -W 传
```

启动日志里 `product control file` / `gaussdb.version` / `Alarm Module` / `gscgroup_omm.cfg` / `$GAUSSLOG`
这些告警是商用内核机制与容器内非 root 安装的固有噪音，不影响服务；`grep -inE 'ERROR|FATAL|license'` 零命中
就说明没有许可拦截。**注意这个镜像实测是 GaussDB Kernel 503.1.0（2023 老版本，兼容模式还是 A/B/C/PG
老命名）**，不是商用 5.x/6.x，任何据此得出的结论都要标注版本。

**海量 Vastbase**——KubeBlocks 风格 entrypoint，env 一长串（`CLUSTERNAME/REPLICAS/DBPORT/INSTALLROOTPATH/
INITUSER/INITPASSWORD/DBCOMPATIBILITY/GS_USERNAME/GS_USERPASSWORD/...`），还要 `/opt/configargs/host_ips_names.txt`
（内容 `127.0.0.1,localhost`）。`REPLICAS=1` 走单机分支：解包引擎 → `gs_initdb --dbcompatibility=A` → 建业务用户
→ `gs_ctl start` → `tail -f /dev/null`；重启时检测到 `$DATAPATH/PG_VERSION` 会跳过初始化。初始化约 150 秒，
READY 判据是容器内 `vsql -d postgres -Atc 'SELECT 1;'` 能回。宿主目录要 `chown -R 1000:1000`。

**SQL Server**——`ACCEPT_EULA=Y` + `MSSQL_SA_PASSWORD`；`MSSQL_MEMORY_LIMIT_MB` 在本镜像 CU27 首启
**没有**落到 `max server memory`（`mssql.conf` 压根没生成），真正生效的是容器 cgroup。要严格压引擎内存
得进容器 `mssql-conf set memory.memorylimitmb 1536` 再重启。客户端是 `/opt/mssql-tools18/bin/sqlcmd`，
**必须带 `-C`**（自签证书），老的 `mssql-tools` 路径不存在。

**瀚高**——`HIGHGO_PASSWORD` 只在数据卷为空时首次 initdb 生效，之后改无效；默认库叫 `highgo`，**没有 `postgres` 库**。

**MariaDB**——最省事：`-e MARIADB_ROOT_PASSWORD=… -e MARIADB_DATABASE=… -e MARIADB_USER=… -e MARIADB_PASSWORD=…`
一行起库。注意 `lower_case_table_names=0`（Linux 默认），跟 OceanBase 的 `1` 相反，两边折叠行为不能互推。

**磐维 PanWeiDB（系统镜像 + 开机自动安装型）**——镜像是 openEuler + systemd（`ENTRYPOINT /usr/sbin/init`），
开机 `init-db.service` 跑 `/root/run.sh`：解包 → ansible（user/env/xml）→ `gs_install` → 改 `pwadmin` 密码 →
`tail -f /dev/null`。首装约 10 分钟（bzip2 解包占大头），**容器内端口 17700，不是 5432**：

```bash
mkdir -p /www/panweidb/data && chmod 755 /www/panweidb/data
docker run -d --name panweidb-test --restart unless-stopped --privileged=true \
  --memory=4g --shm-size=2g -v /www/panweidb/data:/database -p 26100:17700 \
  -e dbcompatibility=B registry.cn-shenzhen.aliyuncs.com/shine_fei/panweidb:3.4.0
```

**本宿主上它有三处硬阻塞，不打完起不来**（三处都在容器内改，改前留 `.orig`）：

1. **CPU 预检是门槛，不是真实依赖**。`check_cpu_instructions` 要求 `lscpu` **同时**命中 `avx` 与 `rdtscp`
   （`lscpu | grep rdtscp && lscpu | grep avx`），QEMU vCPU（无 avx/avx2/rdtscp/sse4.x/popcnt）直接抛
   `Warning: This cluster is missing the rdtscp or avx instruction`。把
   `script/local/PreInstallUtility.py` 该函数末尾的 `raise Exception(cpu_mission)` 改成 `pass` 后，
   **initdb、引擎启动、`gs_checkos` 全部正常** ⇒ 预检过严（判定依据要写清：绕过的是门槛，不是硬件能力）。
2. **cgroup v2 让 `checkDockerEnv()` 恒假**。它 grep `/proc/1/cgroup` 的 cgroup v1 形态
   （`egrep '^1:.+(docker|lxc|kubepods)'`），新版宿主只有 `0::/init.scope` ⇒ 恒 False ⇒
   **9 处「容器内可容忍」分支（LocalCheckOS / LocalCheck / inspection items）全部失效**，先后卡在
   `find /etc/sysconfig/network-scripts -iname 'ifcfg-*-lo'`（GAUSS-51400）与
   `/sbin/ethtool -g eth0`（GAUSS-50612 `Operation not supported`）。
   **修厂商自己的探测函数比逐个 patch 检查点干净**：在 `gspylib/common/Common.py` 的 `checkDockerEnv()`
   开头加 `if os.path.exists("/.dockerenv") or os.path.exists("/run/.containerenv"): return True`；
   另可顺手 `mkdir -p /etc/sysconfig/network-scripts`（find 无匹配时返回 0，空目录即可过）。
3. **`listen_addresses` 绑死安装期容器 IP**。`run.sh` 把 `localhost` 替换成当次 `ifconfig` 取到的 `172.17.0.x`，
   容器重启换 IP 后 `gs_ctl start` 报 `FATAL: could not create listen socket for "172.17.0.x:17700"`。
   在 `run.sh` 的启动分支（`gs_ctl start` 之前）按当前 IP 重写 `postgresql.conf` 即可自愈。
   注意 **`gs_guc` 不接受 `*`，也不接受逗号值**（`ERROR: The parameter(*) exists illegal character:*` /
   `The value "0.0.0.0,localhost" ... is incorrect`），只能改文件。
   **2026-11-13 改法升级**：直接把文件写成 `listen_addresses = '*'` 可行（`gs_guc` 的限制只约束它自己那条命令），
   panweidb-ora/-test 与 gaussdb 现在都是 `'*'` + hba 放 `0.0.0.0/0`，**彻底不依赖容器 IP**（配套改动见 §4.5）：

```bash
ip=$(hostname -I | awk '{print $1}')
sed -i "/^listen_addresses/c\\listen_addresses = 'localhost,${ip}'" /database/panweidb/data/postgresql.conf
```

**⚠️ 2026-11-12 整机重建新增的确定性坑：`-e dbcompatibility=B` 传不进安装器**。
`init-db.service` 是 systemd 服务，**不继承 `docker run -e` 的容器环境**：实测
`systemctl show -p Environment` 为空、PID1 `/proc/1/environ` 有 `dbcompatibility=B` 但
run.sh 进程 `/proc/44/environ` 没有 → `ansible-playbook -e dbcompatibility=""` →
模板渲染出 `gs_initdb ... --dbcompatibility=` 空值 → 引擎**默认落 A 档**。
即标称 B/MySQL 档的 26100 实例会静默装成 A 档（`pg_database` 四库全 A、`show sql_compatibility`→A）。
原 2026-11-06 部署记录没暴露这一层（当时 env 恰好生效）。
**确定性修法（无时序竞态）**：run.sh 第 2 行 `source /root/.bashrc`，而 `.bashrc` 在镜像层、
容器启动前可改——
```bash
docker create --name panweidb-test ... -e dbcompatibility=B <img>   # 注意：create，不 start
printf '\nexport dbcompatibility=B\n' >> /tmp/bashrc.pw
docker cp /tmp/bashrc.pw panweidb-test:/root/.bashrc
docker start panweidb-test
```
A 档同理注入 `export dbcompatibility=A`。三处补丁仍由编排器在解包窗口打。
装完**必须验兼容档真的落对**：`show sql_compatibility` / `select datname,datcompatibility from pg_database`，
不能只看 `select 1` 通就当装好了。

另外两条与产品语义绑定的规矩：

- **兼容档是安装期参数，不能切片**。3.4.0 的 `CREATE DATABASE` **没有** `DBCOMPATIBILITY` 选项
  （`\h create database` 实证），`-e dbcompatibility=A|B|PG` 只在首装时生效 ⇒ **A/Oracle 与 B/MySQL 必须两个实例**，
  别指望单实例内切档（实例内所有库连同 `template*` 都是同一个 `datcompatibility`）。
- **内存有引擎下限，且下限不是容器 limit**。`max_process_memory` 低于
  `2GB + cstore_buffers(512MB) + (udf_memory_limit - UDF_DEFAULT_MEMORY) + shared_buffers + preserved memory(2241MB)
  ≈ 5057MB` 时直接 `FATAL: the values of memory out of limit`。想省内存就压 `shared_buffers`
  （1GB→256MB，实测常驻 RSS 从 1.42GB 降到 0.9GB），`max_process_memory` 抬到 8GB 即可（它是记账上限，不是预占）。

## 2.5 内存预算与过载治理（2026-11-07 实录）

靶机 16GB 物理内存上跑 12 个数据库容器时，「容器上限合计 41GiB」不是预留但**任一个失控都能吃干主机**；
本轮实测 `available` 只剩 775MB、swap 为 0，一次峰值就是 OOM-kill。治理按「swap → 容器 cap → DB 内部参数」
三步走，缺一步都不算做完。

### 定 cap 的规则

`cap ≈ 实测峰值的 1.5~2×`，且**必须高于进程的 anon 常驻**（`docker stats` 的 MemUsage 含 page cache，
不可回收的 anon 才是硬地板，见下）。本轮 12 个容器（容器名 / 改前实测 / 旧 cap / 新 cap）：

| 容器 | 改前实测 | 旧 cap | 新 cap | 备注 |
|---|---|---|---|---|
| xc3-oceanbase | 4.39 GiB | 8 GiB | 5.5 GiB | anon 硬地板 4.21GiB，压到 4GiB 会立刻 OOM |
| xc3-oracle-free | 2.17 GiB | 4 GiB | 2.5 GiB | 压 SGA 后实际降到 0.95GiB |
| panweidb-ora | 1.14 GiB | 3 GiB | 1.5 GiB | 压 `max_connections` 后 0.49GiB |
| panweidb-test | 1.08 GiB | 4 GiB | 1.5 GiB | 同上 0.49GiB |
| xc3-mssql | 1.02 GiB | 2 GiB | 1.5 GiB | `max server memory` 768MB 后仍 1.0GiB |
| xc3-vastbase | 724 MiB | 4 GiB | 1.5 GiB | 改后 533MiB |
| xc3-gaussdb-kernel503 | 647 MiB | 4 GiB | 1.5 GiB | 改后 639MiB |
| xc3-opengauss | 589 MiB | 4 GiB | 1 GiB | 改后 469MiB |
| xc3-mysql8 | 371 MiB | 1 GiB | 768 MiB | |
| xc3-vastbase-b | 219 MiB → 改后 593 MiB | 2 GiB | 1 GiB | 重启后实测反涨，**按改后值定**，512MiB 会不够 |
| xc3-mariadb | 193 MiB | 2 GiB | 512 MiB | |
| xc3-highgo | 45 MiB | 3 GiB | 512 MiB | 反向验证用这个容器 |

改 cap 一律 `docker update --memory <新值> --memory-swap <新值> <容器>`（`--memory-swap` 必须 ≥ `--memory`，
与 memory 相等表示不给额外 swap）。**一次改一个，改完立刻 `docker inspect` 复核 + `docker ps` 看是否 Up**，
异常就按回退表恢复原值再继续。

### cap 不是全部：必须同步压 DB 内部参数

容器 cap 只是外框，DB 内部参数才是它「想用多少」。只压 cap 不压参数，等于让容器被自己的 DB 撑到 OOM-kill。

| 引擎 | 参数 | 建议值 | 改法 |
|---|---|---|---|
| OceanBase | `memory_limit` | 按 `sum(unit MEMORY_SIZE) + system_memory` 定，本轮 6G→5G | 在线 `ALTER SYSTEM`；但**先压 unit**，见下 |
| OceanBase | 租户 `MEMORY_SIZE` | 3G→2G（`ALTER RESOURCE UNIT <unit> MEMORY_SIZE='2048M'`） | 在线；2G 是下限之一 |
| openGauss 族（磐维 / GaussDB / Vastbase / openGauss） | `shared_buffers` | 256~384MB | 改 conf + **重启** |
| openGauss 族 | `cstore_buffers` | 512MB→128MB（列存，测试库基本不用） | 改 conf + **重启** |
| 磐维 PanWeiDB | `bulk_write_ring_size` | 2GB→512MB | 改 conf + **重启** |
| 磐维 PanWeiDB | `max_connections` | 3000→200（**实测常驻从 950MiB 降到 500MiB**） | 改 conf + **重启** |
| Oracle | `sga_max_size` / `sga_target` / `pga_aggregate_target` | 512M / 512M / 256M | `sga_target`/`pga` 可在线；`sga_max_size` 只能 `scope=spfile` + 重启才真正释放 |
| SQL Server | `max server memory (MB)` | 512~1024 | 在线改；**改小不会归还内存，必须重启容器才生效** |
| MySQL / MariaDB | `innodb_buffer_pool_size` | 128~256MB | 在线 `SET GLOBAL` + 写 conf |

OceanBase 的两个坑：①`memory_limit` 不能低于 `sum(unit MEMORY_SIZE) + system_memory`，直接 `ALTER` 会报
`ERROR 4147 Invalid config`，要先把租户 unit 压下来；②unit 有 `__min_full_resource_pool_memory` 下限
（本机 bootstrap 写死 2GB，`ALTER SYSTEM` 改不动），所以单租户压不到 2GB 以下；③`ALTER RESOURCE UNIT`
不接受 `'1.5G'` 这类小数（`Parse error`），用整数 `1536M`；④unit 压太狠会触发
`The memory of memstore needs to be less than 90% of the new memory ... shrink is not supported`，
先看 `GV$OB_MEMSTORE` 的 `memstore_used` 再决定压到多少。即使压了 `memory_limit`，observer 的 anon 常驻
也**不会立即释放**（本轮 6G→5G 后 anon 纹丝不动在 4.21GiB），定 cap 要照 anon 而不是照 memory_limit。

### 2026-11-13 第二轮治理新增的四个坑

1. **MSSQL `max server memory` 是 advanced option**：直接
   `sp_configure 'max server memory', 512` 报 `Msg 15123 ... may be an advanced option`。两步法：
   先 `sp_configure 'show advanced options', 1; RECONFIGURE` 再改目标项。改完**必须回读**
   `SELECT value_in_use FROM sys.configurations` 确认（第一遍直改失败被误当成已执行，输出被 grep 吃掉才没发现）。
   RSS 回落与 cap 收缩配合（本轮 768→591MiB 未重启即回落）；纯改参仍建议重启后复核 RSS。
2. **cap 有两处事实源**：运行时 cap（`docker update`）与幂等重建脚本里写死的 `--memory=`——
   只改前者，下次整机重放（跑 vastbase/OB 的 start.sh、gaussdb init.sh、a-deploy.sh）全部回退旧值。
   治理时两处都 `sed` 同步；参数化脚本（磐维 v2 编排器 cap 是第 4 参）在头注释记传参值；台账 TSV 内存列一并更新。
3. **Vastbase 容器内口令变量是 `GS_USERPASSWORD`**：start.sh 是宿主上的幂等重建脚本
   （`docker rm -f` + 完整 `docker run`，数据在宿主卷跳过初始化），容器 env 是 KubeBlocks 风格；
   `docker exec` 里拿 db.env 的 `$VB_PASS` 是空的 → vsql 报 `Invalid username/password` 虚惊。
   vsql 不读 PGPASSWORD，容器内用 `-W "$GS_USERPASSWORD"` + `LD_LIBRARY_PATH=/home/omm/vbdata/install/vastbase/lib`。
4. **Oracle `v$parameter` 查询的引号**：SQL 里的单引号经 `sh -c` 双引号嵌套被吃（`\047` 转义也翻车），
   用管道法：`echo "select ... in ('sga_max_size',...);" | docker exec -i <c> sqlplus -s "/ as sysdba"`。

### 哪些改法在线、哪些要重启

- **在线**：OceanBase（`ALTER SYSTEM` / `ALTER RESOURCE UNIT`）、MySQL/MariaDB（`SET GLOBAL`）、
  Oracle 的 `sga_target`/`pga_aggregate_target`。
- **在线改但必须重启才真释放**：SQL Server 的 `max server memory`——`sp_configure` + `RECONFIGURE` 报成功，
  cgroup anon 却纹丝不动（本轮 991MB 停在原地，`DBCC DROPCLEANBUFFERS`/`FREEPROCCACHE` 也没用），
  重启容器后才降到 633MB。
- **必须重启**：openGauss 族的 `shared_buffers`/`cstore_buffers`、Oracle 的 `sga_max_size`。openGauss 族重启
  实测 20~40 秒停机；Oracle Free 约 1 分钟。**重启窗口内先确认没有别人在跑方言矩阵**。
- 磐维容器重启后 `gs_ctl` 可能因残留的**空 `postmaster.pid`** 报 `invalid data in PID file`，删掉再
  `docker exec <c> su - omm -c 'gs_ctl start'` 即可；直接 `docker exec -u omm ... gs_ctl start` 会因缺少
  `GAUSSHOME` 报 `Get environment of GAUSSHOME failed`，**必须走 `su - omm`**。

### swap：主机 0 swap 时一次峰值就是 OOM-kill

```bash
fallocate -l 4G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=4096
chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo "/swapfile none swap sw 0 0" >> /etc/fstab
printf 'vm.swappiness = 10\nvm.vfs_cache_pressure = 50\n' > /etc/sysctl.d/99-mpvs-swap.conf
sysctl -w vm.swappiness=10
```

**坑（本轮实证）**：`vm.swappiness` 设完过一会儿又变回 0。真凶是 `--privileged` 的磐维容器——它内部
`/etc/sysctl.conf` 写着 `vm.swappiness = 0`，容器重启时 systemd-sysctl 直接写进宿主内核。**特权容器的
sysctl 会落到宿主**，所以要么把容器内那份也改成 10，要么每次重启后复查（本轮两者都做了）。

### 采集与判定

```bash
free -m                                            # 看 available
docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}'
docker inspect -f '{{.HostConfig.Memory}}|{{.HostConfig.MemorySwap}}' <容器>
free -m | awk '/Mem:/{print $7}'                   # available MB
```

判定口径三条：①`available` ≥ 3GB；②`docker stats` 无容器贴 cap 90% 以上；③12 个容器全部 `Up`、无 Restarting。
`available` 受 page cache 影响很大，采数前可先 `sync; echo 3 > /proc/sys/vm/drop_caches` 看**真实的**压力态余量
（本轮 drop 后 2549MB、压完参数后 3357MB），不要拿刚缓存的读数自欺。

### 改完必须验数据面

cap 和内部参数都改了，必须跑插件自己的方言验证子集，确认渲染词数没退化：

```bash
./gradlew dialectExternalVerify \
  -PdialectExternalMatrix="<你的工作树>/.local/matrix.memcheck.properties" \
  -Pkotlin.compiler.execution.strategy=in-process --console=plain
```

注意是 **`-P`** 不是 `-D`；矩阵文件整目录在 `.local/`（gitignore），**凭据不许进仓**。子集文件只留一行
`instances = panwei_a`（或 `gaussdb_kernel503`），跑完对照基线词数（panwei_a 109 词且 `skip=1(rowid)`、
gaussdb_kernel503 197 词）。

### 反向验证

把一个小容器的 cap 临时压到**明显低于它的实际常驻**，证明 cap 真的生效：

```bash
docker update --memory 16m --memory-swap 16m xc3-highgo
docker ps --filter name=xc3-highgo --format '{{.Status}}'   # 应 Restarting (137)
docker inspect -f 'OOMKilled={{.State.OOMKilled}} ExitCode={{.State.ExitCode}}' xc3-highgo
docker update --memory 512m --memory-swap 512m xc3-highgo    # 改回即恢复
```

只贴「设了 cap」不算验证；必须看到被压的容器真的异常、改回真的恢复，两次读数都要留。
**量级别拍脑袋**：本轮按计划压到 64M，highgo 照常 `Up`（空闲只占 21~39MiB，`MemPerc` 分母已变成 64MiB、
说明参数生效但它不缺内存），继续压到 16M 才 `OOMKilled=true`/`ExitCode=137`/`RestartCount=7`。
压得不够低，验证的只是「docker 接受了这个参数」。

## 3. 凭据与权限：真正卡人的都在这

**瀚高 `passwordcheck`**（最费时间的一个）。2026-11-12 整机重建时 A 组做了 md5 公式对照实验，
实证 `pg_authid.rolpassword` 存的是**单层** `'md5' || md5(密码||用户名)`（`STORED=md5ade40cd…=FORMULA_A`）；
旧基准文件写的 `md5(md5(密码||用户名))` 是认证过程的表述，不是存储值，别照抄成双层。
另两个实测坑：占位密码别用含 `{` 的串（`unsupported character:'{'`），用纯 ASCII 合规占位；
`psql -c` 不做 `:'var'` 插值，直写口令字面量（仅字母数字下划线、无引号）更稳。

initdb 会拒含 `_` 和 `-` 的密码，要求大小写+数字+特殊字符，
`#`、`@` 可用。给业务用户 `CREATE USER xc3 PASSWORD '…_…'` 会被三条策略依次拦下
（`unsupported character:'_'` → `cannot contain the user name` → `cannot contain highgo keyword`），
关 GUC（`passwordstrength/passworddictionary/passwordlength/passwordlock`）无效，
`DROP EXTENSION passwordcheck` 报 `System reserved`。唯一出路是**先建合规占位密码，再直接改写系统目录**：

```sql
CREATE USER xc3 PASSWORD 'Zq7#Pq9m2026';
UPDATE pg_authid SET rolpassword='md5<md5(密码||用户名)>' WHERE rolname='xc3';   -- password_encryption=md5
```

之后任何 `ALTER USER … PASSWORD` 都会再次被拒——这条凭据只能在策略外维护，台账里要写清楚。

**openGauss / Vastbase 的 `public` schema**——默认从 PUBLIC 收回了 CREATE，业务用户建表直接
`permission denied for schema public`。修法（用超级用户 `omm`）：

```sql
CREATE DATABASE xc3_test OWNER xc3;
GRANT CREATE, USAGE ON SCHEMA public TO xc3;
ALTER SCHEMA public OWNER TO xc3;
```

**官方 `opengauss/opengauss:5.0.0` 的 gsql 三坑**（2026-11-12 重建实测）：
① `gsql` 不在 PATH，真路径 `/usr/local/opengauss/bin/gsql`；
② 必须带 `LD_LIBRARY_PATH=/usr/local/opengauss/lib`，否则报
`libcjson.so.1: cannot open shared object file`；初始超管是 `opengauss`（没有 `omm`），
`gsql` 不读 `PGPASSWORD`，一律 `gsql -W '口令'`；
③ **gsql 不支持多个 `-c`**——一条命令塞多个 `-c` 会把 `CREATE TABLE` 误报成
`table does not exist`，配置/验证脚本拆成每次单条调用。

**动手前先验权限**，别等建表才发现：

```sql
select current_user, current_database(),
       has_database_privilege(current_user, current_database(), 'CREATE') as db_create,
       has_schema_privilege(current_user, 'public', 'CREATE') as pub_create;
```

这条是给「别人给的实例」用的：官方 openGauss 5.0.0 那台（`114.66.55.245:20105`）驱动能连、密码也对，
但两个权限都是 false，任何 DDL 类验证都做不了——**换实例，别硬修**（改权限要超级用户，我们没有）。

**OceanBase** 的 MySQL 协议登录名是 `user@tenant`（如 `xc3@test`、`root@sys`），少了 `@tenant` 连不上。
**2026-11-12 整机重建实测差异**：`MODE=mini` 只自动建租户 `test`、库 `xc3_test` 和 **`root@test`**
（口令取 `OB_TENANT_PASSWORD`/`OB_PASS`），**不会自动建业务用户 `xc3`** —— 直接拿 `xc3@test` 连会报
`ERROR 1045 Access denied`。诊断顺序：`root@sys` + `OB_SYS_PASS` 实连（sys 口令生效）→ `__all_user` 看实际用户
→ 在 `root@test` 下 `CREATE USER 'xc3' IDENTIFIED BY '<OB_PASS 旧值>'` + `GRANT ALL PRIVILEGES ON xc3_test.*`。
注意**租户内用户名不带 `@test`**，`user@tenant` 只是客户端登录名格式；别误建字面 `'xc3@test'` 用户。
判据仍以 `obclient -u'xc3@test' -p'<OB_PASS>' SELECT 1` 通为准，凭据最终必须与台账旧值一致。

**达梦** 默认大写折叠，建表 `dev_info` 实际是 `DEV_INFO`；没有 `IF EXISTS`，脚本里清理要用 `runCatching` 兜。

## 4. 验证：三档证据，别拿低档冒充高档

1. **TCP 通**：`python -c "socket.connect"` 或 `Test-NetConnection`。只能证明网络与安全组。
2. **客户端能查**：容器内 `psql/gsql/vsql/obclient/sqlcmd/mariadb` 跑 `SELECT 1` / `SELECT version()`。
   这一步能抓出「假 boot success」与「密码策略没落地」。
3. **插件自己的渲染套件在真引擎上跑**：这是唯一能说「引擎级验证」的读数。用
   `DialectDockerVerificationTest` 的外部实例模式，一次一族、串行：

```bash
./gradlew test --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process \
  -x :sql-visualizer-agent:test \
  -Dmpvs.dialect.jdbcUrl="jdbc:mariadb://103.36.220.245:23306/xc3_test" \
  -Dmpvs.dialect.user=xc3 -Dmpvs.dialect.password=… \
  -Dmpvs.dialect.name="MariaDB" \
  --tests "*DialectDockerVerificationTest*"
```

四个必须遵守的约束，少一个读数就不可信：

- **`--no-configuration-cache`**：`-Dmpvs.*` 是配置期经 `System.getProperties()` 透传进测试 JVM 的，
  缓存命中会复用上一族的 URL——用上一族的库跑这一族，看着绿其实验错了对象。
- **方言名逐字核对**：`DialectRegistry.byName` 对未知名称静默回退 MySQL，中文名被转码时同样中招。
  入口已有断言，别绕过。
- **夹具幂等**：建表前与收尾都 DROP（`dev_info`），否则持久库第二次跑必红。
- **词表下限**：`assertSweepClean` 有「少于 50 词即失败」守卫，防词表没接上的空绿。

跑完顺手查一下 `show tables` 应为空——既是清场验证，也证明这一轮真的建过表。

**读数分级要写进结论**：H2 的兼容模式（`MODE=MSSQLServer` 等）只是形态级证据，引擎不强制方言规则
（SQL Server 的 `OFFSET…FETCH` 必须跟 `ORDER BY`、OceanBase 的 EXPLAIN 是 ASCII 树不是 MySQL 表格行，
这两个都是真机才暴露的）；拿 MySQL 承载 MariaDB 驱动只是驱动级替代读数，不能写成「MariaDB 已验证」。
**结论里必须带实例版本**——Kernel 503 不等于商用 5.x，社区镜像不等于厂商官方。

## 4.5 外部连不上：按四层排，报错文案直接指层（2026-11-13 实录）

真机腿红在「连不上」时，别一上来怀疑网络。四层从外往里排，**错在哪层，报错文案就长什么样**：

| 层 | 查法 | 坏了的典型症状 |
|---|---|---|
| ① 宿主机端口 | `ss -tlnp \| grep <port>` | 没有进程 ⇒ 容器没起 / 端口没发布 |
| ② DNAT 目标 | `nft list chain ip nat DOCKER \| grep <port>`（老宿主用 `iptables -t nat -L DOCKER`），与 `docker inspect <容器> --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'` 逐个对 | 目标指向已消失的容器 IP ⇒ SYN 石沉大海（**超时**，不是 refused）。容器重建 / 换网络后必查 |
| ③ 容器内核监听地址 | 容器内 `ss -tln \| grep <引擎端口>` | 只听 `127.0.0.1:x` ⇒ 外部经 DNAT 进来**被拒（refused）**。openGauss 族改 `listen_addresses = '*'`，MySQL 族改 `bind-address=0.0.0.0` |
| ④ pg_hba / 认证来源 | 容器内 `grep -Ev '^\s*#' pg_hba.conf` | 只放行容器网段时，外部连接在**握手期被踢**：pgjdbc `The connection attempt failed.`，DBX/原生客户端 `os error 10054 远程主机强迫关闭了一个现有的连接`。**不是 refused**，极易误判成网络抖动 |

判据口诀：**refused 落在 ①③，超时落在 ②，握手期断连落在 ④**。

11-13 这轮的真凶是 ④，而且出现过一次误判：`listen_addresses` 修好后 `refused` 变成「connection attempt
failed.」，我一度按网络问题排查；用 DBX 从本机直连复现出 `10054` 才定性——hba 只有 `127.0.0.1/32`、
`::1/128`、`172.17.0.0/16`，而 DNAT 进来的外部连接源 IP 是**真实公网地址**，一条都匹配不上。补
`host all all 0.0.0.0/0 sha256`（+ `::/0`）后即通。**口令认证，不放开 trust**；该靶机本来就是公网端口 + 口令模式。

三个实操要点：

- **改 hba 用 `gs_ctl reload`，别 restart**：hba 是 SIGHUP 级配置，reload 不打断正在跑的渲染套件；restart 会让
  262 词 × 4 worker 的真机矩阵瞬间全红，还分不清是自己改坏的还是本来红。
- **引号必须真落盘**：跨层拼命令（本地 → bash → `docker exec` → `sh`）里 `'*'` 极易被吃掉，落成无引号的
  `listen_addresses = *`，openGauss 直接 `could not start server`。改完一律 `grep '^listen_addresses'` 回读。
- **「端口 OPEN」不等于库活着**：`docker-proxy` 会先自己完成 TCP 握手。所以必须做到 §4 第 2 档（客户端能查），
  只做第 1 档（TCP 通）会给出假绿。

### 整机重启后哪些实例自己回来（2026-11-13 实测）

`--restart unless-stopped` 只拉起**容器**，不保证**库进程**：

| 实例 | 容器重启后 | 缺口 |
|---|---|---|
| `xc3-mysql8` / `xc3-opengauss` / 磐维两档 | 库随容器起来 | — |
| `xc3-gaussdb-kernel503` | **容器 Up、库不在** | 镜像 Cmd 只有 `tail -f /dev/null`，没有 entrypoint；必须人工起：`docker exec -u omm <容器> bash -lc 'export LD_LIBRARY_PATH=/opt/gaussdb/app/lib:$LD_LIBRARY_PATH; gs_ctl start -D /home/omm/probe/data'` |

**`gs_ctl` / `gsql` 必带 `LD_LIBRARY_PATH`**：不带会报 `libpq.so.5.5` / `libcjson.so.1` cannot open shared
object file——那是环境问题，不是库装坏了，别按「库坏了」处理。

## 5. 登记 DBX 与驱动

**DBX 登记**（用户惯例）：加进 **Song 分组**，命名 `<服务器短名> | <产品名+版本>`，如
`不大 | OceanBase CE 4.3.5`、`不大 | Vastbase G100 V3.0 (B 模式)`。

三个工具限制要提前知道，免得反复返工：

- `dbx_add_connection` **没有分组参数**，新建的连接只能落在默认位置，得请用户手动拖进 Song 分组；
  MCP 也没有 edit/update 连接的工具（只有 add/duplicate/remove）。
- **`db_type` 不是任意值**：`oceanbase` 不支持（`INVALID_CONNECTION_TYPE`），用 `mysql` 类型 + 用户名带租户；
  `highgo/vastbase/kingbase/dameng/sqlserver/opengauss` 原生支持。**没有专门类型的国产库一律先试 `opengauss`**
  （openGauss 系）/ `mysql`（MySQL 协议系）：磐维 PanWeiDB 实测 `opengauss` 可用，MCP 直连取到 `version()`。
- 新连接默认**不在 MCP 授权范围**（`CONNECTION_OUT_OF_SCOPE`），要用户在 DBX 里放行；放行后连接名可能变，
  **按连接 ID 访问才可靠**。另外 DBX 的凭据是每条连接独立密文，`.local` 里那份「通用密码」对多主机已失效。

**驱动坐标**（只进测试 classpath，插件运行时零依赖不变）：

| 方言 | 坐标 | 备注 |
|---|---|---|
| 达梦 | `com.dameng:DmJdbcDriver18:8.1.3.140` | 驱动类 `dm.jdbc.driver.DmDriver` |
| openGauss | `org.opengauss:opengauss-jdbc:6.0.0-og` | **必须 `-og` 变体**；无后缀同名包是 pgjdbc 改名包，含 `org/postgresql/*` 会遮蔽真 PG 驱动（实测 `-og` 该目录条目 0，无后缀 >0） |
| OceanBase | `com.oceanbase:oceanbase-client:2.4.19` | `com.oceanbase.jdbc.Driver`，URL `jdbc:oceanbase://` |
| MariaDB | `org.mariadb.jdbc:mariadb-java-client:3.3.3` | 3.x 全部自有类，不复用 MySQL `StatementImpl` |
| 瀚高/海量/金仓 | `com.highgo:HgdbJdbc:6.2.5` / `cn.com.vastdata:vastbase-jdbc:2.16.1v` / `cn.com.kingbase:kingbase8:9.0.1` | 都在 Maven Central |
| 磐维 PanWeiDB | 本地 jar `database-lab/vendor/drivers/panweidb-jdbc-{1.0.0,2.0.0}.jar`（V1/V2 类形一致；插件三面登记已覆盖两代） | **无 Maven 坐标**（厂商渠道拿不到公开仓库包）。安装到本机仓库：`mvn install:install-file -Dfile=database-lab/vendor/drivers/panweidb-jdbc-2.0.0.jar -DgroupId=org.panweidb -DartifactId=panweidb-jdbc -Dversion=2.0.0 -Dpackaging=jar`，之后坐标 `org.panweidb:panweidb-jdbc:<ver>` |

磐维两代 jar 实测同一形态：`META-INF/services/java.sql.Driver` 都是 `org.panweidb.Driver`，`org/panweidb/` 条目
374（V1）/ 399（V2），`cn/com/panweidb/` 均为 0——**V2 文档写的 `cn.com.panweidb.Driver` 在两代 jar 里都不存在**，
按 jar 实测登记即可。测试侧由 `StatementWeaverVendorCoverageTest` 遍历 `drivers/panweidb-jdbc-*.jar` 每一代都验。

拉驱动不方便时让服务器自己去阿里云 maven 镜像拿：
`curl -sL -o <file> https://maven.aliyun.com/repository/public/<group/path>/<artifact>-<ver>.jar`。
下完先验内容再信：`unzip -l <jar> | grep -c "org/postgresql/"`、`unzip -p <jar> META-INF/services/java.sql.Driver`。

## 6. 台账与收尾

- **连接/凭据/已知坑** → `.local/test-databases.md`（gitignored）。新增实例补一行：URL、账号、容器名、
  镜像、停起命令、该实例特有的坑。**同一实例还要在 `.local/db-env-instances.tsv` 登记一行**（无凭据的实例
  索引，字段见 §9），两份台账缺一份都算没登记。
- **部署原文与探针读数** → `tmp/xc3-env-init/<产品>.md`（gitignored）。含 docker run 原文、逐条探针输出、
  结论表、未取证清单。
- **停/起**：`docker stop|start <容器>`；海量是 `bash /opt/xc3-vastbase/start.sh`。**不要 `docker rm`**——
  数据卷/数据目录里是初始化好的实例与账号。
- **别改宿主的系统参数**（`vm.max_map_count`、`fs.aio-max-nr` 这些 WARN 都没阻断，改了对别的实例是风险）；
  真正需要时只动 `/proc/sys/vm/drop_caches` 这种无状态的东西。

## 7. 失败模式速查

| 现象 | 真因 | 处置 |
|---|---|---|
| OceanBase 打 `boot success!` 但连不上 | 镜像日志不可信，obd 前检失败（ulimit/MemFree） | 翻全量日志找 `OBD-1007`/`OBD-2000`；加 `--ulimit`、drop_caches 后重建 |
| 容器起不来，`systemctl status mariadb` 失败 | 用 apt 装系统级 mariadb 与自定义端口配置冲突 | 走容器；apt 那条路装完记得 `systemctl disable --now` 并删掉自定义 cnf |
| 建表 `permission denied for schema public` | openGauss/Vastbase 收回 PUBLIC 的 CREATE | 超级用户 `GRANT CREATE, USAGE` + `ALTER SCHEMA public OWNER` |
| 连上但报 SCRAM 相关错 | 拿 pgjdbc 连 openGauss/GaussDB | 换 `opengauss-jdbc` 的 `-og` 变体 |
| 密码怎么设都被拒 | 瀚高 passwordcheck | 占位密码 + 改 `pg_authid.rolpassword` |
| 拉镜像卡在 `Pulling fs layer` 无进度 | Docker Hub 直连/daemon 对多分层镜像挂死 | 换 daocloud；仍不行按 registry v2 API 逐层 curl + `docker load` |
| 第二次跑渲染套件必红 | 上一轮残留夹具表 | 确认套件已幂等（建前收尾都 DROP） |
| 绿了但验的不是目标库 | 配置缓存复用上一族 URL / 方言名回退 MySQL | `--no-configuration-cache` + 方言名逐字核对断言 |
| `gs_ctl restart` 卡死 | 容器 PID1 是 tail，僵尸不回收 | `--init` 重起容器 |
| `mysql:8.0` 容器秒退，日志 `Fatal glibc error: CPU does not support x86-64-v2` | 官方 `mysql:8.0` 是 **Oracle Linux 9** 基底，glibc 以 x86-64-v2 为基线；靶机是 QEMU 虚机（`/proc/cpuinfo` 无 `sse4_1/sse4_2/popcnt`，只有 `cx16`） | 换 **`mysql:8.0-debian`**（Debian 12）。已拉的 OL9 版在本机不可用，别反复试。**TiDB 同款**：官方镜像 v7.5 起换 Rocky/OL9 基底，v8.5/v7.5 本机均炸；唯一可用的是 v6.5（Ubuntu 基底），台账 TSV 已写死「勿再拉 v7+/v8+」 |
| `Msg 15123 ... configuration option 'max server memory' does not exist, or it may be an advanced option` | MSSQL 高级选项默认隐藏 | 两步法：先 `sp_configure 'show advanced options', 1; RECONFIGURE` 再改目标项；改完回读 `value_in_use` 确认（别拿被 grep 吃掉的输出当成功） |
| 「容器 Up 2 hours 但记忆里是昨天起的」怀疑重启循环 | **UTC/北京日期心算错误**的假象（跨日工作常见） | 用 `docker inspect -f '{{.RestartCount}} {{.State.OOMKilled}} {{.State.StartedAt}}'` 确定性字段判，别拿 `docker ps` 相对时间加时区推断（LESSONS L58） |
| 灌进库的中文变 `C3A5C2BCC2A0…`（HEX 可验） | 容器内 `LANG` 为空 → mysql 客户端 `--default-character-set=auto` 落到 **latin1**，initdb 阶段 UTF-8 种子被双编码 | 初始化脚本**首行**加 `SET NAMES utf8mb4;` 后重灌；重建卷时这行必须保留（原始副本留 `.orig`） |
| 容器起来了但外部连不上（容器内/localhost 正常） | MySQL 的 `root` 默认只允许 `localhost` | 起容器加 `-e MYSQL_ROOT_HOST=%` |
| `gs_preinstall` 报 `This cluster is missing the rdtscp or avx instruction` 并退出 | 预检要求 `lscpu` 同时有 avx 与 rdtscp，QEMU vCPU 没有；**引擎本体不需要** | 把 `script/local/PreInstallUtility.py` 里 `check_cpu_instructions` 的 `raise` 改成 `pass`（留 `.orig`） |
| `gs_checkos` 报 GAUSS-51400（`find /etc/sysconfig/network-scripts` 失败）或 GAUSS-50612（`ethtool -g eth0` Operation not supported） | cgroup v2 下 `checkDockerEnv()` 恒假 ⇒ 9 处「容器内可容忍」分支失效 | 在 `checkDockerEnv()` 加 `/.dockerenv` 判定（首选）；或补 `mkdir -p /etc/sysconfig/network-scripts` |
| 容器重启后 `FATAL: could not create listen socket for "172.17.0.x:17700"` | `listen_addresses` 写死了安装时的容器 IP，重启换了 IP | 启动脚本里按 `hostname -I` 重写 `postgresql.conf` 的 `listen_addresses`（`gs_guc` 不收 `*`/逗号值）；**2026-11-13 起 panweidb-ora/test 与 gaussdb 已直接写 `'*'`（改文件可行，`gs_guc` 的限制只约束它自己那条命令），不再依赖容器 IP** |
| 外部连不上，容器内/localhost 正常，pgjdbc 报 `The connection attempt failed.`、DBX 报 `os error 10054` | **pg_hba 只放行容器网段**，外部源 IP 匹配不到任何规则 ⇒ 握手期被踢（不是 refused） | 打开 `pg_hba.conf` 确认有无覆盖外部源的规则；补 `host all all 0.0.0.0/0 sha256` + `::/0 sha256`，`gs_ctl reload` 生效（别 restart，会打断真机矩阵）。排查全链见 §4.5 |
| 整机重启后容器 Up 但库连不上（gaussdb） | 镜像无 entrypoint，`Cmd` 只有 `tail -f /dev/null`，库进程不会自启 | `docker exec -u omm <容器> bash -lc 'export LD_LIBRARY_PATH=/opt/gaussdb/app/lib:$LD_LIBRARY_PATH; gs_ctl start -D /home/omm/probe/data'`；缺 `LD_LIBRARY_PATH` 会误报 `libpq.so.5.5` 缺失 |
| 起库报 `the values of memory out of limit ... = 5057MB` | `max_process_memory` 被压到引擎下限以下 | 抬回 ≥5GB（记账上限非预占），改压 `shared_buffers` |
| `CREATE DATABASE … DBCOMPATIBILITY 'A'` 报 `option "dbcompatibility" not recognized` | 该产品的兼容档是**安装期**参数，不是建库选项 | 另起一个容器按目标档安装，别在单实例里切档 |

## 8. 与插件验证的关系（为什么值得花这个功夫）

插件的方言能力最终要落到「渲染出来的 SQL 在真引擎上能不能跑」。这一轮靠真机抓出三个只有真引擎才暴露的
问题：SQL Server 无排序分页的 `OFFSET/FETCH` 非法、OceanBase EXPLAIN 输出形态登记错档、premain 补挂延迟
单位量级错（10s 变成 10^10ms）。这些在 H2 兼容模式与「MySQL 承载 MariaDB 驱动」的替代读数里全是绿的。

所以：**装环境不是目的，取到引擎级读数才是**。装完立刻按 §4 第三档跑一遍，跑不出读数就等于没装。

## 9. 灾难重建：整套环境从零恢复（靶机随时可能被重装）

**2026-11-12 真跑过一次**，结论先写：全新系统上按本节能把 12 个实例全重建，但有三个实测增量——
① **SSH 主机密钥变了**（DBX 两份 `known_hosts` 都要 `ssh-keygen -R` + `ssh-keyscan` 追加，否则
`Host key changed` / `Unknown server key`）；② **Docker 版本从 28 变 29.9.3**、`bunzip2` 不存在（装 restic
要用 `python3 bz2`）；③ **实例清单漏登了 `xc3-oracle-free`**（12 个在役容器 vs TSV 11 行），说明
「清单从台账抄录」这条链本身会漏——新增实例必须就地登记，且要交叉核对 `docker stats` 实测名单。
备份体系（脚本 + 口令）的处理见 `srv-backup-restore` skill 的「2026-11-12 整机重装实录」。

**前提变了**（2026-11-05 用户定调）：测试服务器的服务**可能随时被重装系统**，容器、卷、数据、initdb 结果全部
丢失。所以「部署」不再是手工操作，而必须是**声明式 + 幂等可重放**的：

- **唯一事实源是实例清单**：`.local/db-env-instances.tsv`（gitignored，**不含凭据**）——一行一个实例，
  Tab 分隔 10 列：`name kind host hostPort ctrPort db user image volume notes`。`name` 是容器名/实例短名；
  `ctrPort` 是**容器内**端口（磐维 17700，不是 5432）；`notes` 记内存与 shm、特权位/ulimit、DBX 连接 id、
  停起方式与该实例特有的坑。**新增实例必须就地登记一行**（部署完就补、别攒）——漏登记的实例在靶机重装后
  等于不存在。
- **凭据只落台账**：`.local/test-databases.md` 记连接 URL、账号口令、已知坑；清单的 `user` 列只写账号名。
- **重放吃的是派生出来的 replay manifest**：`bootstrap-db-env.sh` 的输入是 Docker 子集的 9 列清单
  （`name image hostPort containerPort memLimit volumeName env initDir verifyCmd`，模板
  `scripts/instances.example.tsv`），`env` 位放口令、从台账现填。**manifest 含明文口令，只落本机 `.local/`
  与靶机 `/root/`，不入库**：实例清单回答「该有哪些」，manifest 回答「怎么起」。
- **重放引擎是脚本**：`scripts/bootstrap-db-env.sh <replay.tsv>`——已存在的容器跳过、镜像缺失才拉（带镜像源回退）、
  按 `verifyCmd` 轮询就绪、最后打印端口汇总并列出未就绪项。**可反复执行**，失败重跑只补没起来的那些。
- **种子数据要能自动灌**：把 `.sql` 放清单的 `initDir`（挂到 `/docker-entrypoint-initdb.d:ro`），镜像首次
  初始化时自动执行；每条 `.sql` 首行必须 `SET NAMES utf8mb4;`（见 §7 的双编码坑）。

### 重建顺序（靶机是全新系统时照这个走）

```bash
# 0) 宿主就绪：Docker 在跑、daemon.json 有可用 mirror（见 §1）
docker info >/dev/null && echo ok
free -g | head -2                      # 按 §0 的内存账排布实例，别一次全上

# 1) 派生 replay manifest（实例清单的 Docker 子集 + 台账口令现拼，9 列见 scripts/instances.example.tsv）
#    .local/db-env-instances.tsv          实例清单（无凭据，用来核对「该有哪些」）
#    .local/replay.tsv                    本次重放清单（9 列，env 位含口令；不外传）
#    /root/<实例>-init/*.sql              种子脚本（首行 SET NAMES utf8mb4;）
scp .local/replay.tsv root@103.36.220.245:/root/
scp -r dev-support/db-seed/<实例>/*  root@103.36.220.245:/root/<实例>-init/

# 2) 一键重建
ssh root@103.36.220.245 'bash /root/bootstrap-db-env.sh /root/replay.tsv'

# 3) 外部可达性 + 数据正确性（两档都要取，别只看容器 Running）
#    - 从本机用 DBX/客户端实连一次（SELECT 1 / VERSION()）
#    - 中文正确性用 HEX 钉死：SELECT HEX(name) —— 必须等于 HEX('张三') = E5BCA0E4B889
#    - 表与行数：SHOW TABLES + 逐表 COUNT(*)

# 4) 收尾：台账补行（test-databases.md + db-env-instances.tsv 两处都补）→ DBX 登记（首个查询可能
#    CONNECTION_OUT_OF_SCOPE，放行一次）→ 若参与 CI，更新仓库 secrets（-PmpvDbUrl/-PmpvDbUser/-PmpvDbPassword）

# 5) 内存治理收尾轮（必做，不是「有空再做」——2026-11-13 实测欠压：重建只按台账旧 cap 起、
#    内部参数全是镜像默认值，available 只剩 3144MB；治理一轮后 4149MB）：
#    a) docker update 把全部 cap 收到 §2.5 目标值（在线无重启；逐个改、inspect 复核）
#    b) 内部参数逐实例核：Oracle SGA/PGA（默认 1536M，压 512/512/256 scope=spfile+重启）、
#       MSSQL max server memory（默认无限=2147483647，先 show advanced options 再压 512）、
#       openGauss/Vastbase shared_buffers（镜像默认 1024M，压 256MB+重启）、MySQL/MariaDB buffer pool
#    c) cap 有两处事实源：运行时 + 幂等重建脚本里写死的 --memory=（vastbase/OB start.sh、
#       gaussdb init.sh、a-deploy.sh）——治理必须两处都 sed 同步，否则下次重放全部回退旧值；
#       参数化脚本（磐维 c-orchestrate-panweidb-v2.sh 第 4 参）在头注释记传参值
#    d) 反向验证：一个小容器 cap 压 16m → OOMKilled=true Exit=137 → 恢复，两次读数留档
#    e) 复核 free -m 的 available ≥3GB + 13/13 数据面 SELECT 1 + 台账 TSV 内存列同步
```

### 磐维 PanWeiDB：不能「一把梭」，三处补丁有时序

两个磐维实例（`panweidb-test` B 档 / `panweidb-ora` A 档）的容器起得来，但**装不上**：补丁必须在**解包之后、
`gs_local` 跑到 `checkos` 之前**打进去，而 `init-db.service` 一开机就自己开跑，时间窗只有几十秒到几分钟。
处置顺序：

- `dev-support/db-lab/patch-panweidb-image.sh` **若该脚本在位则用它**（起容器 → 轮询等
  `/database/panweidb/soft/script/` 解包出来 → 打补丁 → 放行安装）；**不在位就按 §2 磐维段手工打三处**：
  `PreInstallUtility.py` 的 `raise` → `pass`、`Common.py` 的 `checkDockerEnv()`、`run.sh` 的 `listen_addresses` 自愈。
- 2026-11-12 整机重建实测：脚本是**容器内补丁器**（`--container NAME` 分支只做 `docker cp` + `docker exec`），
  **不是编排器**——「起容器 → 2s 紧轮询等解包 → 打补丁 → 放行安装」的时序要由实施者自己编排。
  本次 `iter=37`（起容器后约 91 秒）检测到 `soft/script` 解包完成即打补丁，窗口命中；脚本自带
  三处 `.orig` 备份与**正反探针**（正向 returns cleanly / 反向 pristine raises），别跳过。
  打完补丁的 `/root/run.sh` sha256 与 2026-11-06 调研报告 §7.2 的线上验证版逐字一致，可当验收锚点。
- 持久性不一样，别记混：前两处在 `/database` 卷里、随卷持久（同一卷重建容器不必重打）；
  `/root/run.sh` 与 `/etc/sysconfig/network-scripts/` 在镜像层，**每次重建容器都要重打**。
- 兼容档是安装期参数（§2），A/B 必须两个容器，不能在一个容器里切；
  **且 `-e dbcompatibility` 经 systemd 不生效**（2026-11-12 实证），必须用
  `docker create` + `docker cp` 往 `/root/.bashrc` 注入 `export dbcompatibility=B|A` + `docker start`，
  详见 §2 磐维段的「⚠️ 整机重建新增的确定性坑」。装完要验 `show sql_compatibility` 真的落对档。

### 重建时不做的事

- **不要**为了「快点」改用 apt 装系统级数据库（§7 已记 mariadb 的冲突案例）；
- **不要**动宿主系统参数（`vm.max_map_count` 等）——对既有实例是风险；
- **不要**在没有清单的情况下手工 `docker run` 一串——那样下次重装还得从头踩一遍；
- **不要**把凭据写进 `docs/`、`src/`、skill 本体——只落 `.local/`。

### 恢复演练（可选但建议）

判定「这套东西真能重建」的唯一证据是**真跑一次**：删掉一个非关键容器 → 用脚本重建 → 复读端口/表数/中文 HEX。
不做演练的声明只能算「应该有」，与 skill §4 的三档证据同一口径。

### 多组并行重建的协调纪律（2026-11-12 实测）

- **共享基准 + 凭据分离**：主控把「恢复作业基准」写成服务器上的一份文件（端口/镜像/变量名的唯一事实源），凭据只放 `/etc/svbackup/db.env`（600），基准里只写变量名不写口令。三组后台并行（≤3），读同一份基准、写集互不重叠。
- **冲突主控统一处置**：组间抢出口带宽 / 抢跨镜像共享层 / 孤儿进程（`kill` 父进程后 curl 变 PPID=1 仍下载），由主控读 `ps`/`du` 证据后统一清孤儿、定错峰顺序（A→B→C、一次只 1 个 `docker pull`），**不叫子代理自己 kill**。
- **判据落在磁盘字节与实连**：环境会把库名/容器名/变量名在显示层替换成随机串，别拿显示串改脚本——用逐字节 hex（如 `78 63 33 5f 74 65 73 74` = `xc3_test`）或容器内 `SELECT 1` 实连自证。曾因显示串不一致虚惊一轮，实际所有脚本写进磁盘的是同一个真值。
- **别在任务运行中改脚本**：在途进程会读到写到一半的中间态（内嵌 Python 泄漏到 shell 报 `line 127: syntax error near unexpected token ')'`，而事后 `bash -n` 磁盘版本是过的）。改前先确认无在途读者，改完 `bash -n` + 内嵌语言 `compile` 双检再启动新任务。
- **占位符逐字照抄**：服务方/用户给的 `{{PHONE_*}}` / `{{SECRET_*}}` 之类占位符只能原样使用，不能凭记忆改写 id——首次 `docker login` 把手机号占位符写成另一个 id 直接回「用户名或密码验证失败」，原样照抄后 `Login Succeeded`。
- **服务方 Agent 文档优先**：用户给出 `https://xuanyuan.cloud/agents.md` 这类规范且要求「先阅读并遵守」时，未读文档前不生成 pull 命令或排错方案；镜像源默认口径以该文档为准（§1 第 8 条），不凭印象沿用 `registry-mirrors`。

### 相关文档

| 文档 | 作用 |
|---|---|
| `docs/经验总结/20261112-2140-测试服务器整机重建与备份体系入仓-经验总结.md` | 本次 12 实例整机重建全过程与证据链 |
| `docs/LESSONS.md` L52~L56 | 教训账本：脚本入库/口令双份、content store 复用、占位符、systemd 不继承 env、并行协调 |
| `docs/开发流程-派活与验收清单.md` §三 | 多子代理并行派活的通用口径 |
| `dev-support/svbackup/` + `.agents/skills/srv-backup-restore/SKILL.md` | 备份体系（仓库资产 + 作业手册），与实例重建是两件事 |
| `docs/架构设计/测试服务器备份与恢复体系-说明文档.md` | 备份体系设计与决策记录 |
| **`dev-support/db-env/docker-compose.yml`** | **13 容器的声明式配方（容器骨架）**，2026-11-13 从在役容器 inspect 抄录；用法见其 `README.md` 与本文 §10 |

## 10. 声明式配方（docker compose）：新机器一键起，在役机器只作参照

13 个容器的**容器骨架**（镜像 / 端口 / 内存 cap / shm / ulimit / privileged / init / 卷 / env 键）已落
`dev-support/db-env/docker-compose.yml`，事实源是 2026-11-13 对在役容器的逐字段 `docker inspect`。配套
`.env.example`（变量名模板，无口令）与 `README.md`（前置步骤 / 内存分档 / 变量映射 / 后置步骤表）。

**用法**：口令从 `.local/db-env.env`（本机）或 `/root/db-env.env`（服务器 600）取；
`docker compose --env-file /root/db-env.env up -d` 起轻档 10 个（RSS ≈ 4.6G），加 `--profile heavy` 再起
OceanBase + 磐维 A/B（RSS ≈ 4.9G）。分档就是为「控制内存不撑爆」——默认不把 3.8G 的 OceanBase 拉起来。

**五个实测约束**（2026-11-13 在真实服务器 `config --quiet` + `--dry-run up` 验证）：

1. **只在全新机器用**。compose 靠 `com.docker.compose.*` label 认领容器，在役容器没有这些 label 而
   `container_name` 唯一 ⇒ 在役机器 `up` 必撞 `Conflict. The container name ... is already in use`。
   想在役机器切 compose 管理只能删容器重建，会丢磐维 `run.sh`/`.bashrc` 补丁这类容器层改动，得不偿失。
2. **`network_mode: bridge` 必须显式写**。否则 compose 建 `db-env_default` 网络、容器换到 172.18.x，
   打断备份采集「借 xc3-mysql8 走 `172.17.0.1` 网关」的回退通道，也改变磐维 `listen_addresses` 的网段。
3. **已在役的命名卷要 `external: true`**。只写 `name:` 会报
   `volume "xxx" already exists but was not created by Docker Compose` 并试图"创建"同名卷。
4. **镜像必须先 `docker tag` 回 canonical 名**。配方写的是 `pingcap/tidb:v6.5.6`，而经轩辕域拉下来的是
   `docker.xuanyuan.run/pingcap/tidb:v6.5.6`；不补 tag 时 compose 会去 Docker Hub 拉，实测撞
   `toomanyrequests: unauthenticated pull rate limit`。
5. **MariaDB 卷是配方与现状唯一的差异**：在役是匿名卷、配方改成命名卷，首次 `up` 建空卷，旧数据要先
   用 `docker run --rm -v <旧卷>:/from -v <新卷>:/to alpine cp -a /from/. /to/` 迁移。

**编排不出来的后置步骤**（GaussDB 的 gs_initdb、Vastbase 初始化、磐维三处补丁 + `.bashrc` 注入、
HighGo 口令直写、OceanBase 业务用户补建、TiDB/MSSQL/Oracle 的内部参数）不在 compose 里，README 第四节
逐实例给了步骤与本文对应章节号。`docker compose config --quiet` 是语法门禁；`--dry-run up -d` 可看计划
（dry-run 的 `Creating/Created` 只是计划文本，实测不落盘真正资源）。

