---
name: srv-backup-restore
description: 测试服务器（不大-北京-16G）的备份与恢复作业法——restic + 七牛 Kodo 的日常备份、滚动保留、容量闸门、逐库恢复、整机重建、告警链路，以及新增数据库/容器时怎么扩采集脚本。当用户要求「备份服务器」「恢复某个库」「备份失败排查」「加一套环境要改备份吗」「整机没了怎么重建」时使用。
---

# srv-backup-restore 测试服务器备份与恢复作业法

对象是「不大-北京-16G」（`103.36.220.245`，Ubuntu 22.04 / 16C / 15G / Docker 28，SSH 走 DBX 连接
`2af473a2-f9fc-4478-846a-4b5202d606c5`）。本文件把 2026-10-31 这一轮从选型到联调踩出来的东西固化下来。

## 三条铁律

1. **这台机器不在主流云上**（无元数据端点、裸 KVM、厂商还挂着自己的 agent），**完全没有控制台快照能力**，
   且账号是**永久使用、无到期回收**（2026-10-31 用户确认）。结论：没有「装坏了点一下回滚」这条退路，
   「数据不丢」全靠这套异地备份——这也让本文档第三节的恢复演练从「建议」变成「必须定期做」。
2. **备份桶必须私有**。七牛未实名时「私有」权限是灰的，只能建公开空间——那种情况下**不要建**，先实名。
3. **恢复才是验收标准**。只跑通 `restic backup` 不算数，必须能按本文档把库灌回临时库并比对行数。

## 一、体系全貌

| 层 | 位置 | 作用 |
|---|---|---|
| 采集 | `/usr/local/bin/svbackup-collect.sh` | 枚举运行中的容器，按镜像族做逻辑导出 + 元数据快照 |
| 主流程 | `/usr/local/bin/svbackup-run.sh` | 采集 → 上传 → 滚动保留 7 份 → 9GB 容量闸门 |
| 告警 | `/usr/local/bin/svbackup-notify.sh` | 失败时推送到管理后台（机器令牌） |
| 调度 | `svbackup.timer` / `svbackup.service` | 每天 **UTC 19:00 = 北京 03:00**（`RandomizedDelaySec=300`，实际落在 19:00–19:05），`Persistent=true` |
| 失败兜底 | `svbackup-alert@.service` | service 的 `OnFailure=` 目标，写本地台账并推后台告警 |
| 凭据 | `/etc/svbackup/{credentials,pass,db.env,notify-token,notify-api}` | 全部 600，目录 700 |
| 仓库 | 七牛 Kodo 私有空间 `songsrv-backup`（华东-浙江 / `cn-east-1`） | restic 仓库，S3 兼容端点 `s3.cn-east-1.qiniucs.com` |

> 历史遗留：`/usr/local/bin/svbackup-dump-all.sh` 是第一版（硬编码容器名与库名），已被
> `svbackup-collect.sh` 取代，**不要再用**；它仍在机器上只是为留痕。
> `/etc/svbackup/notify-url` 是**可选的成功心跳**（run.sh 会读，当前不存在，属正常）。

restic 二进制在 `/root/.local/bin/restic`（0.19.1）。**这台机器到 GitHub 极慢且必断**（实测能握手、
能拿到部分字节，84KB 也要 8 秒然后超时），下大文件必失败，升级只能走
「本地经代理下载 `.bz2` → DBX SFTP 上传（单文件上限 16MB，本地路径须在系统临时目录）→ 服务器 `bunzip2`」，
`restic self-update` 在本机不可用。

## 二、日常操作

```bash
# 立即跑一次（采集+上传+保留+闸门）
systemctl start svbackup.service
journalctl -u svbackup -n 50 --no-pager
tail -30 /var/backups/svbackup/logs/$(ls -t /var/backups/svbackup/logs | grep '^run-' | head -1)

# 看快照与仓库体积
set -a; . /etc/svbackup/credentials; set +a
R=/root/.local/bin/restic; O="-o s3.region=cn-east-1"
$R $O snapshots
$R $O stats --mode raw-data

# 定时器状态（下次执行时间按 UTC 显示）
systemctl list-timers svbackup.timer
```

判读要点：
- **仓库体积**要盯着 10GB 免费额度；闸门在 9GB 会先降档到 3 份，仍超则中止并告警（不会扣费）。
- **`forget` 不等于释放空间**，必须 `--prune`；`prune` 会重打包，常规路径带 `--max-repack-size 2G`，
  9GB 降档路径是 `4G`。
- **截至 2026-10-31，定时器尚未真实触发过**（`list-timers` 的 `LAST` 为 `n/a`，现有快照全部来自手工
  `systemctl start`）。首跑后要回看 `LAST`，确认自动调度真的生效。
- 日志里出现 `FAIL <容器>__<库>` 时看同目录 `*.log`：多数是凭据或工具路径问题，见第五节。

## 三、恢复：三档

### 3.1 只取一个文件（最快，日常最常用）

```bash
set -a; . /etc/svbackup/credentials; set +a
R=/root/.local/bin/restic; O="-o s3.region=cn-east-1"
$R $O snapshots                                   # 选一天
$R $O ls latest /var/backups/svbackup/dumps       # 看里面有什么
$R $O dump latest /var/backups/svbackup/dumps/<日期>/dbs/<容器>__<库>.dump > /tmp/x.dump
```

### 3.2 单库恢复到临时库并比对（这是「演练」的标准动作）

先整份还原到磁盘：

```bash
set -a; . /etc/svbackup/credentials; . /etc/svbackup/db.env; set +a
R=/root/.local/bin/restic; O="-o s3.region=cn-east-1"
rm -rf /tmp/verify && mkdir -p /tmp/verify
$R $O restore latest --target /tmp/verify
D=/tmp/verify/var/backups/svbackup/dumps/<日期>/dbs
```

**GaussDB / openGauss / Vastbase（gs_restore）**——以 `omm` 身份走 peer 认证，不需要口令：

```bash
C=xc3-gaussdb-kernel503; LIB=/opt/gaussdb/app/lib; BIN=/opt/gaussdb/app/bin
docker exec -u omm -e LD_LIBRARY_PATH=$LIB $C $BIN/gsql -d postgres -tAc "CREATE DATABASE restore_verify"
docker cp "$D/xc3-gaussdb-kernel503__xc3_test.dump" $C:/home/omm/g.dump
docker exec $C chown omm:omm /home/omm/g.dump        # 关键：docker cp 落地是 root:600，omm 读不到
docker exec -u omm -e LD_LIBRARY_PATH=$LIB $C $BIN/gs_restore -d restore_verify /home/omm/g.dump
```

**HighGo（pg_restore）**——注意这个容器**没有 `postgres` 库**，要连 `xc3_test` 建库：

```bash
C=xc3-highgo; LIB=/opt/HighGo6.0.1-cluster/lib; BIN=/opt/HighGo6.0.1-cluster/bin
P() { docker exec -e PGPASSWORD="$HG_PASS" -e LD_LIBRARY_PATH=$LIB $C $BIN/psql -h 127.0.0.1 -p 5866 -U "$HG_USER" "$@"; }
P -d xc3_test -c "CREATE DATABASE restore_verify"
docker exec -i -e PGPASSWORD="$HG_PASS" -e LD_LIBRARY_PATH=$LIB $C $BIN/pg_restore \
  -h 127.0.0.1 -p 5866 -U "$HG_USER" -d restore_verify < "$D/xc3-highgo__xc3_test.dump"
```

**OceanBase / MariaDB（纯 SQL）**——dump 里带 `CREATE DATABASE xc3_test`，**必须先改库名**再导入，
否则会砸掉源库：

```bash
# $OB_USER 在 /etc/svbackup/db.env 里是 xc3@test；root@test 与它同口令，两者都可用
sed 's/`xc3_test`/`restore_verify`/g' "$D/xc3-oceanbase__xc3_test.sql" \
  | mysql -h127.0.0.1 -P25881 -u"${OB_USER:-root@test}" -p"$OB_PASS"
```

**SQL Server（BACKUP 文件）**：

```bash
docker cp "$D/xc3-mssql__xc3_test.bak" xc3-mssql:/var/opt/mssql/backup/r.bak
docker exec xc3-mssql /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P "$MSSQL_PASS" \
  -Q "RESTORE DATABASE restore_verify FROM DISK='/var/opt/mssql/backup/r.bak' WITH MOVE 'xc3_test' TO '/var/opt/mssql/data/r.mdf', MOVE 'xc3_test_log' TO '/var/opt/mssql/data/r.ldf'"
```

比对行数（通用套路）：列出源库每张表，逐表 `count(*)` 与 `restore_verify` 比，全等才算过。
2026-10-31 实测基线：GaussDB 12 张、OceanBase 11 张、HighGo 5 张，**逐表全等**。

清理（别把临时库留在机器上）：

```bash
# GaussDB
docker exec -u omm -e LD_LIBRARY_PATH=$LIB $C $BIN/gsql -d postgres -tAc "DROP DATABASE IF EXISTS restore_verify"
# OceanBase / MariaDB
mysql ... -e "DROP DATABASE IF EXISTS restore_verify"
# 收尾
rm -rf /tmp/verify; docker exec xc3-gaussdb-kernel503 rm -f /home/omm/g.dump
```

> DBX 的 SSH 工具会把 `DROP DATABASE` 判成破坏性命令并拦截，属预期行为——确认目标是临时库后带
> `confirmDestructive: true` 重发。

### 3.3 整机重建（服务商跑路 / 机器彻底没了）

1. 新机器装 Docker，`daemon.json` 从快照里的 `meta/daemon.json` 还原（含国内镜像源）。
2. 装 restic（走第二节的「本地代理 + SFTP」那条路）。
3. 取元数据：`$R $O dump latest /var/backups/svbackup/dumps/<日期>/meta.tar.zst > meta.tar.zst`，
   解开后有 `docker-images.txt`（含 digest）、`docker-inspect.json`（端口/挂载/env 全量）、各 `start.sh`。
4. 按 digest 拉镜像 → 复跑 `start.sh` 重建容器 → 按 3.2 逐库灌回。
5. 恢复 `svbackup-*` 脚本与 `/etc/svbackup/*`，重新 `systemctl enable --now svbackup.timer`。

预期 30~60 分钟，大头是拉镜像。

## 四、新增环境要不要改脚本

**同类引擎不用改**：脚本按镜像名匹配族——MySQL 族含 `mysql`/`mariadb`/`oceanbase`/`tidb`/`percona`
（走 `mysqldump`）；PG 族含 `postgres`/`opengauss`/`gaussdb`/`vastbase`/`highgo`/`kingbase`/`dameng`/`dm8`
（`gs_dump` 优先、`pg_dump` 兜底）；`mssql` 走 `BACKUP DATABASE`。库名靠 `SHOW DATABASES` /
`pg_database` / `sys.databases` 自动枚举，不写死。

**要改的三种情况**：
1. 全新引擎（Oracle/Redis/Mongo…）——当前落到「未知族」分支，只做挂载点文件级 tar 并告警。要给它们做
   逻辑导出，就在 `svbackup-collect.sh` 的 `case` 里加一个族函数（Redis 用 `BGSAVE` 后拷 RDB，Mongo 用
   `mongodump`）。
2. 容器 env 里没有标准密码变量且 `db.env` 也没有对应条目——补 `/etc/svbackup/db.env`（600）。
3. 端口非标——脚本已按 `docker port` 取容器内端口，一般不用动。

### 4.1 「未知族」分支是仓库膨胀的最大风险（2026-11-08 实测踩坑）

**事故**：PanWeiDB（openGauss 系，镜像 `shine_fei/panweidb:3.4.0`）镜像名不含任何已知族关键字，
落进「未知族」分支 → **对它的数据卷做文件级 tar** → 两个容器各打出 **3 GB** 的 `.tar.zst`。
快照体积从 3 MiB（11-05）暴涨到 6 GiB（11-06）、12 GiB（11-07），**两天打爆 10 GB 免费额度**。

**根因链**：镜像名匹配 → 未命中 → 文件级 tar → 数据库原始数据卷（GB 级）被整卷打包。
**这类引擎要特别警惕**：PG 系的国产分支（PanWeiDB / 虚谷 / 金仓 / 达梦…）镜像名千奇百怪，
很容易一个都不匹配。

**三条处置**（按优先级）：
1. **先 SKIP，再考虑逻辑导出**。测试实例（如 PanWeiDB）备份价值低，加一条 SKIP 分支最省事：
   ```sh
   *panwei*)
     warn "→ $c [$img] 跳过：测试实例，不做文件级 tar（避免原始数据卷撑爆仓库）" ;;
   ```
   放在 `case` 里 `*)` 之前即可。**不要**因为「它能用 gs_dump」就归进 PG 族——那需要同时满足
   ①镜像名命中 ②`probe_tool` 能找到工具 ③`pg_env` 能找到库路径 ④`db.env` 里有该实例的凭据，
   四条缺一就导出失败（实测：凭据缺失 → `Invalid username/password`）。
2. **真要逻辑导出**：三处都要补——`case` 关键字、`probe_tool` 的可执行路径列表、`pg_env` 的库路径列表。
   漏一处就是 `rc=1 size=0`。
3. **加体积哨兵**：采集后 `du -sh` 本次 dump，超过阈值（如 500 MB）就告警。这次若能早发现，
   不至于两天涨到 12 GB。

## 五、排障速查（都是实测踩过的）

| 现象 | 根因 | 处置 |
|---|---|---|
| `Access Denied` / `SignatureDoesNotMatch` | AK/SK 不对；写操作 region 没传 | 七牛控制台**已存在的 SK 只显示星号**，拿不到明文，只能新建一对。region 已含在端点 `s3.cn-east-1.qiniucs.com` 里，实测**读操作不带也能跑**，但脚本与文档统一带 `-o s3.region=cn-east-1`，避免写操作踩坑 |
| `invalid option -- 'd'` | 老版 pg_dump/gs_dump 不认 `-d` | 用位置参数写库名 |
| `must be used with -f/--file` | 该版 `-Fc` 不允许写 stdout | 容器内 `-f /tmp/x.dump` 再 `docker cp` |
| `error while loading shared libraries: libcjson/libpq_ce` | 缺 `LD_LIBRARY_PATH` | openGauss 用 `/usr/local/opengauss/lib`，GaussDB 用 `/opt/gaussdb/app/lib` |
| 卡住不动、日志停在 `Password:` | 工具在等交互式口令 | 所有导出命令加 `-w`（pg_dump）或 `-W`（gs_dump），并把 stdin 接 `/dev/null`；脚本已有超时（枚举 90s / 导出 300s）+ **每库最多 2 次尝试**（第 112–119 行的 `for attempt in 1 2` 循环，间隔 3 秒） |
| `cache lookup failed for index N` | 源库有悬挂索引（库侧问题） | 脚本会自动重试一次，通常第二遍就过；仍失败则手工 `systemctl start svbackup.service` 重跑 |
| `unable to locate cache directory` | systemd 下无 `HOME` | service 里 `Environment="HOME=/root"` + `XDG_CACHE_HOME` |
| 告警发不出、`http=000` | 用了 `workers.dev` 域名 | 国内服务器不可达，必须用 `https://sql-visualizer.jvtools.cn/api/v1/notify/backup` |
| **`Invalid username/password,login denied`** | 该实例的凭据不在 `db.env` 里（`dbuser_for`/`dbpass_for` 的 case 没覆盖它） | 要么补 `/etc/svbackup/db.env`，要么按 §4.1 直接 SKIP 该实例。**别硬修**——测试实例不值得为它维护一条凭据链 |
| 仓库体积暴涨、`ABORT: 仍超 9GB` | 某个容器被「未知族」分支做了文件级 tar | 先 `restic stats --mode raw-data` 看总量，再 `restic ls latest /var/backups/svbackup/dumps/<日期>/volumes` 找出大文件，按 §4.1 定位是哪个容器 |

### 5.1 超标了怎么降下来（实测有效）

`forget --keep-last N --prune` **只能删快照，压不掉单快照内部的体积**——这次收紧到 3 份后仍是 11.8 GB。
必须**按 ID 精确删掉大快照**再 prune：

```sh
set -a; . /etc/svbackup/credentials; set +a
R=/root/.local/bin/restic; O="-o s3.region=cn-east-1"
$R $O snapshots                                   # 看体积，找异常大的
$R $O forget <id1> <id2>                         # 精确删（注意：是 `forget <id>`，没有 --forget 这个 flag）
$R $O prune --max-repack-size 2G                 # 真正释放空间
$R $O stats --mode raw-data                      # 确认降下来
```

2026-11-08 实测：删掉 11-06/11-07 两个大快照 + prune 后，**11.8 GiB → 1.16 MiB**，回到免费额度内。
**注意**：`prune` 才会重打包释放空间，`forget` 只是取消引用；两者必须都做。

## 六、告警链路

- 令牌在管理后台「账号令牌」页签发（仅超管可见），绑定「无权限（仅令牌）」账号；
- 令牌只能调 `POST /api/v1/notify/backup`，**读不到任何业务数据**；每令牌 30 次/小时；
- 服务器侧落 `/etc/svbackup/notify-token`（600）与 `notify-api`；
- 手工验证：`/usr/local/bin/svbackup-notify.sh high '测试' '联调'`，返回 `http=200` 且后台通知台账有记录即通。
