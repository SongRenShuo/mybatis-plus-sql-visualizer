---
name: srv-backup-restore
description: 测试服务器（不大-北京-16G）的备份与恢复作业法——restic + 七牛 Kodo 的日常备份、滚动保留、容量闸门、逐库恢复、整机重建、告警链路，以及新增数据库/容器时怎么扩采集脚本。当用户要求「备份服务器」「恢复某个库」「备份失败排查」「加一套环境要改备份吗」「整机没了怎么重建」时使用。
---

# srv-backup-restore 测试服务器备份与恢复作业法

对象是「不大-北京-16G」（`103.36.220.245`，Ubuntu 22.04 / 16C / 15G / Docker 28，SSH 走 DBX 连接
`2af473a2-f9fc-4478-846a-4b5202d606c5`）。本文件把 2026-10-31 这一轮从选型到联调踩出来的东西固化下来。

## 四条铁律

1. **这台机器不在主流云上**（无元数据端点、裸 KVM、厂商还挂着自己的 agent），**完全没有控制台快照能力**，
   且账号是**永久使用、无到期回收**（2026-10-31 用户确认）。结论：没有「装坏了点一下回滚」这条退路，
   「数据不丢」全靠这套异地备份——这也让本文档第三节的恢复演练从「建议」变成「必须定期做」。
2. **备份桶必须私有**。七牛未实名时「私有」权限是灰的，只能建公开空间——那种情况下**不要建**，先实名。
3. **恢复才是验收标准**。只跑通 `restic backup` 不算数，必须能按本文档把库灌回临时库并比对行数。
4. **脚本入库 + 仓库口令离线双份**（2026-11-12 整机重装事故后立）。脚本的唯一事实源是
   `dev-support/svbackup/`，服务器上的 `/usr/local/bin/svbackup-*.sh` 只是部署副本；
   `/etc/svbackup/pass` 必须在密码管理器与另一台设备各存一份。**口令只随服务器存在＝备份永久失效**——
   重装后 AK/SK 还在、仓库对象还在，但 `restic snapshots` 只回 `wrong password or no key found`。

## 2026-11-12 整机重装实录（必读，本次事故的全部教训）

用户重装系统后机器全新：Docker 29.9.3、零容器零镜像零卷、`/www/xc3`、`/opt/xc3-vastbase`、
`/etc/svbackup/`、四个 `svbackup-*.sh`、systemd 单元**全部蒸发**。SSH 主机密钥也变了（DBX 有两个
`known_hosts`：`C:\Users\USER\.ssh\known_hosts` 与
`C:\Users\USER\AppData\Roaming\com.dbx.app\plugin-data\io.dbx.ssh\known_hosts`，都要 `ssh-keygen -R` 后
追加 `ssh-keyscan` 的新密钥，否则报 `Host key changed` / `Unknown server key`）。

处置与结论：

- **旧仓库数据放弃**：`--insecure-no-password snapshots` 实证 `wrong password or no key found`；
  本地全工作区、三台存活机（香港/百度云/华为云）都无 `pass` 副本。用户 2026-11-12 拍板「口令不要了」，
  旧快照不再追回；**随后又授权「另起一套新口令的仓库」**。
- **新仓库另起，不覆盖旧对象**：用户授权后，用**同一七牛 bucket 的新前缀**建独立仓库——
  `RESTIC_REPOSITORY=s3:s3.cn-east-1.qiniucs.com/songsrv-backup/v2`（旧仓库是根前缀，对象原封不动），
  `restic init` 成功（repo `29b6eef669`）、写入 smoke 快照 `755ba0c5`、`snapshots`/`stats` 均正常。
  **绝不能对同一 bucket 用 `restic init` 覆盖**——那会重写 `config`/`key` 让旧数据彻底不可恢复；
  加 `/v2` 这类路径前缀是最省事的隔离（不用新建 bucket、不用新 AK/SK）。
  新口令写 `/etc/svbackup/pass`（600，32 字符随机），本地离线副本 `.local/svbackup-pass-v2.txt`
  （gitignored），**用户还需在密码管理器/另一台设备再存一份**（口令离线双份铁律）。
  `credentials` 只改 `RESTIC_REPOSITORY` 一行，AK/SK 沿用原值。
- **脚本改为仓库资产**：原脚本只活服务器上，无法逐字复刻；本次按架构文档与旧行为重写，落在
  `dev-support/svbackup/`（含 `install-svbackup.sh`、`README.md`、三个 systemd 单元模板）。
  改脚本先改仓库再重新部署，README 记了部署与验收清单。
- **restic 二进制装法订正**：全新系统**没有 `bunzip2`**，用 `python3 -c "import bz2,shutil; ..."` 解包
  （`.bz2` 10MB 在 SFTP 16MB 上限内，解出的二进制 30MB 超上限，不能直接传二进制）。
- **缺口令时新体系不假绿**：`svbackup-run.sh` 在缺 `/etc/svbackup/pass` 时 `fail` 并告警，
  不会静默写成一个读不出来的仓库；timer 可以照常 `enable --now`（下一次触发在 UTC 19:00）。
- **未知族默认 SKIP**：新采集脚本 `SKIP_FILE_LEVEL_TAR=1`，未知引擎只告警不打包，
  复刻 2026-11-08 PanWeiDB 整卷 tar 打爆额度的教训；另有 500MB 体积哨兵。
- **整机重建时数据库本身不靠备份恢复**：凭据台账 `.local/test-databases.md` + 实例清单
  `.local/db-env-instances.tsv` + 种子 SQL + `bootstrap-db-env.sh` 才是重建依据（见
  `db-env-provisioning` skill §9）；备份只负责「历史业务数据」，且这次因口令丢失没取回。

## 一、体系全貌

| 层 | 位置 | 作用 |
|---|---|---|
| **仓库资产（事实源）** | `dev-support/svbackup/` | 三个脚本 + 三个 systemd 单元 + `install-svbackup.sh` + `README.md`；**改脚本先改这里** |
| 采集 | `/usr/local/bin/svbackup-collect.sh`（部署副本，700） | 枚举运行中的容器，按镜像族做逻辑导出 + 元数据快照 + 体积哨兵；未知族默认 SKIP |
| 主流程 | `/usr/local/bin/svbackup-run.sh`（部署副本，700） | 采集 → 上传 → 滚动保留 7 份 → 9GB 容量闸门 → 失败告警；缺口令明确 fail |
| 告警 | `/usr/local/bin/svbackup-notify.sh`（部署副本，700） | 失败时推送到管理后台（机器令牌），无令牌静默跳过 |
| 调度 | `/etc/systemd/system/svbackup.timer` / `.service` | 每天 **UTC 19:00 = 北京 03:00**（`RandomizedDelaySec=300`），`Persistent=true` |
| 失败兜底 | `svbackup-alert@.service` | service 的 `OnFailure=` 目标，推后台告警 |
| 凭据 | `/etc/svbackup/{credentials,pass,db.env,notify-token,notify-api}` | 全部 600，目录 700；**`pass` 必须离线双份** |
| 仓库 | 七牛 Kodo 私有空间 `songsrv-backup`（华东-浙江 / `cn-east-1`） | restic 仓库，S3 兼容端点 `s3.cn-east-1.qiniucs.com` |

> 历史遗留：`/usr/local/bin/svbackup-dump-all.sh` 是第一版（硬编码容器名与库名），已被
> `svbackup-collect.sh` 取代，**不要再用**；2026-11-12 重装后服务器上已无此文件。
> `/etc/svbackup/notify-url` 是**可选的成功心跳**（当前不存在，属正常）。

restic 二进制在 `/root/.local/bin/restic`（0.19.1）。**这台机器到 GitHub 极慢且必断**（实测能握手、
能拿到部分字节，84KB 也要 8 秒然后超时），下大文件必失败，升级只能走
「本地经代理下载 `.bz2`（10MB）→ DBX SFTP 上传（单文件上限 16MB，本地路径须在系统临时目录）
→ 服务器 `python3 -c "import bz2,shutil; ..."` 解包」——**全新系统没有 `bunzip2`，别照旧写法**；
解出的二进制 30MB 超 SFTP 上限，不能跳过压缩直传。`restic self-update` 在本机不可用。
部署整套体系：`bash /root/svbackup-src/install-svbackup.sh`（幂等，只动脚本/单元/暂存目录）。

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

**先分清两件事**：① 数据库实例本身靠「实例清单 + 凭据台账 + 种子 + `bootstrap-db-env.sh`」重建
（见 `db-env-provisioning` skill §9，与备份仓库无关）；② 历史业务数据才靠 restic 快照。
2026-11-12 实测：口令丢失时 ② 直接失效，但 ① 完整可用——所以**口令丢了不等于环境没了**。

1. 新机器装 Docker，`daemon.json` 从快照里的 `meta/daemon.json` 还原（含国内镜像源）。
   重装后 SSH 主机密钥会变，**两份 `known_hosts` 都要 `ssh-keygen -R` + 追加 `ssh-keyscan` 新密钥**。
2. 装 restic（走第二节的「本地代理 + SFTP + `python3 bz2` 解包」那条路，全新系统无 `bunzip2`）。
3. 取元数据：**前提是仓库口令还在**（离线副本恢复 `/etc/svbackup/pass`）；
   `$R $O --password-file /etc/svbackup/pass dump latest .../meta.tar.zst > meta.tar.zst`，
   解开后有 `docker-images.txt`（含 digest）、`docker-inspect.json`（端口/挂载/env 全量）、各 `start.sh`。
   口令不在就跳过本步，按 ① 的清单重建实例。
4. 按 digest 拉镜像 → 复跑 `start.sh` 重建容器 → 按 3.2 逐库灌回（有快照才做）。
5. 恢复备份体系：**从插件仓 `dev-support/svbackup/` 取脚本**（`install-svbackup.sh` 一把部署），
   `/etc/svbackup/*` 从离线副本恢复，重新 `systemctl enable --now svbackup.timer`。
6. 口令确实找不回时：旧仓库对象留在七牛不删（不产生费用风险，仍在 10GB 免费额度内），
   新体系用**新口令 + 新仓库**起一套（或用户明确放弃备份）；**不要**拿新口令去 `restic init`
   同一个 bucket，那会覆盖旧仓库的 config/key 让旧数据彻底不可恢复。

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
1. **先 SKIP，再考虑逻辑导出**。**2026-11-12 起入仓的 `svbackup-collect.sh` 已经默认这么做**：
   未知族一律 `warn` 后跳过，`SKIP_FILE_LEVEL_TAR=1`（默认值），只有显式设成 0 才走旧的文件级 tar 分支；
   另加 500MB 体积哨兵（`SVBACKUP_SIZE_WARN_MB` 可调）。所以新部署的体系**不会再犯这次事故**，
   要改这条默认行为得先改 `dev-support/svbackup/svbackup-collect.sh` 并重新部署。
   测试实例（如 PanWeiDB）备份价值低，加一条 SKIP 分支最省事：
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
| **`wrong password or no key found` / `an empty password is not allowed`** | **restic 仓库口令丢失**（2026-11-12 重装事故） | AK/SK 有效、仓库对象仍在，但数据读不出。先从离线副本恢复 `/etc/svbackup/pass`；找不回就按用户决定放弃旧数据，用新口令另起仓库（**不要对同一 bucket `restic init`**）。缺口令时 `svbackup-run.sh` 会明确 `fail` 并告警，不会静默假绿 |
| `Host key ... changed` / `Unknown server key` | 重装后 SSH 主机密钥变了 | `ssh-keygen -R 103.36.220.245` 对**两份** known_hosts 各做一次（系统与 `%APPDATA%\com.dbx.app\plugin-data\io.dbx.ssh\`），再 `ssh-keyscan` 新密钥追加进去 |
| `bunzip2: command not found` | 全新系统无 bzip2 工具 | 用 `python3 -c "import bz2,shutil; ..."` 解包 restic 的 `.bz2`；不要改成传 30MB 裸二进制（超 SFTP 16MB 上限） |
| `仓库体积读数解析失败（12.343）` | `svbackup-run.sh` 把 `Total Size: 12.343 KiB` 第 3 列当数值、单位带前导空格让 `case` 匹配不上 | 改 `awk '/Total Size:/{print $3, $4}'` 分离数值+单位再 `read -r num unit`；降档路径同样要改（2026-11-12 实跑修复） |
| `prune 返回非 0` / `repository is already locked` | 前一次 backup 留下 stale lock，`forget --prune` 拿不到锁 | 给 `forget` 加 `--retry-lock 2m`；仍撞就 `restic list locks` + `restic unlock` 清残留锁。缺口令时脚本应明确 fail 而非假绿 |
| `→ <容器> 找不到 psql/gsql，跳过（FAIL）` | `svbackup-collect.sh` 的 `probe_tool` 只用 `command -v`，国产库的 `pg_dump`/`vsql` 装在 `/opt/...` 且不在 PATH | 候选改绝对路径走容器内 `test -x`、命令名走 `command -v`；库名枚举的客户端候选也补 `vsql`/`gsql` 绝对路径（2026-11-12 Vastbase 两实例实测） |

> **主流程必须真跑一次才验收**：`install-svbackup.sh` 装完只保证文件就位与 timer enabled，必须
> `systemctl start svbackup.service` 跑一次完整链路，看 `journalctl -u svbackup` 与
> `/var/backups/svbackup/logs/run-*.log` 的「1/4 采集 → 2/4 上传 → 3/4 forget → 4/4 闸门 → rc=0」五步齐全。
> 上面三个 bug 都是这次真跑才暴露的；修复后用 `sha256sum` 核对仓库 `dev-support/svbackup/` 与
> 服务器 `/usr/local/bin/svbackup-*.sh` 一致，再复跑一次确认 `仓库体积 <N> B（闸门 9663676416 B）` 正确解析。

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

## 七、相关文档（改体系时同批核对）

| 文档 | 作用 |
|---|---|
| `dev-support/svbackup/`（含 `README.md`） | 脚本的**仓库事实源**；改脚本先改这里再 `install-svbackup.sh` 重新部署 |
| `docs/架构设计/测试服务器备份与恢复体系-说明文档.md` | 体系设计与决策记录（§九 有 2026-11-12 重建后现状） |
| `docs/经验总结/20261112-2140-测试服务器整机重建与备份体系入仓-经验总结.md` | 本次事故全过程与证据链 |
| `docs/LESSONS.md` L52 | 教训账本：脚本入库 + 口令离线双份 |
| `.agents/skills/db-env-provisioning/SKILL.md` §9 | 数据库实例本身的灾难重建（与备份仓库是两件事） |
