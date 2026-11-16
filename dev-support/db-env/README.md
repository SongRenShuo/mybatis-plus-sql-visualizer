# 测试服务器数据库环境配方（docker compose）

「不大-北京-16G」（`103.36.220.245`）上 13 个数据库容器的**声明式配方**，供重装后快速复用。

事实源是 2026-11-13 对 13 个在役容器逐字段 `docker inspect` 抄录的结果（镜像/端口/内存/shm/ulimit/privileged/init/卷/环境变量键），
不是凭记忆写的。**改配置先改这里**，再在服务器上重放；容器与配方不一致时以配方的改动为依据反推。

作业法（每类引擎的坑与后置步骤）在 `.agents/skills/db-env-provisioning/SKILL.md`，本文只讲这套配方怎么用。

## 一、快速开始

```bash
# 0) 宿主前置（只在全新机器上做一次）
mkdir -p /opt/xc3-vastbase/{install,data,log,backup,configargs} /opt/xc3-vastbase-b/{install,data,log,backup,configargs}
mkdir -p /www/xc3/{gaussdb,opengauss-official/data,oceanbase/ob,oceanbase/obd-cluster}
mkdir -p /www/panweidb/data /www/panweidb-ora/data /root/xc3-mysql8-init
chown -R 1000:1000 /opt/xc3-vastbase /opt/xc3-vastbase-b /www/xc3/opengauss-official/data
printf '127.0.0.1,localhost\n' > /opt/xc3-vastbase/configargs/host_ips_names.txt
printf '127.0.0.1,localhost\n' > /opt/xc3-vastbase-b/configargs/host_ips_names.txt
chown -R 1000:1000 /www/xc3/oceanbase        # OceanBase 容器内 uid 非 root

# 1) 口令：真实值在 .local/db-env.env（本机）或 /root/db-env.env（服务器，600）
cp .env.example .env          # 本地临时用；服务器直接用 --env-file 指向外部文件

# 2) 起「轻档」10 个容器（RSS ≈ 4.6G，宿主 15G 下留足余量）
docker compose --env-file /root/db-env.env up -d

# 3) 需要时再加 heavy 档 3 个（OceanBase + 磐维 A/B，RSS ≈ 4.9G）
free -m | head -2             # 先看 available；不足 3G 先腾
docker compose --env-file /root/db-env.env --profile heavy up -d

# 4) 按下面「后置步骤」表逐实例补编排不出来的初始化
```

镜像拉不动走轩辕域（见 skill §1 第 8 条）：docker.io 系用 `docker.xuanyuan.run/<路径>`，
拉完 `docker tag` 回 canonical 名再 `up`。TiDB 必须 v6.5.x（v7.5+ 换 Rocky/OL9 基底，本机 QEMU 起不来）。

## 二、内存分档（2026-11-13 第二轮治理后的实测值）

| 档 | 容器 | cap | 空载 RSS |
|---|---|---|---|
| 轻档（默认） | mariadb / mysql8 / tidb / mssql / highgo / vastbase / vastbase-b / gaussdb / opengauss / oracle-free | 512m / 768m / 1536m / 1536m / 512m / 1536m / 1024m / 1536m / 1024m / 2500m | ≈ 4.6G |
| heavy | oceanbase / panweidb-test / panweidb-ora | 5632m / 1536m / 1536m | ≈ 4.9G |

两档全起约 9.5G RSS，宿主 16G 下 `available` 约 3~4G。**别被 cap 总和骗**：cap 是记账上限不是预占，
真正要盯的是 `free -m` 的 available 与 `docker stats` 的 anon 常驻。swap 需 4G（`/swapfile`，skill §2.5 配方）。

## 三、变量映射（compose `.env` ↔ 服务器 `/etc/svbackup/db.env`）

两套变量名不同源（compose 侧是为了让配方自解释），**值必须一致**：

| compose 变量 | db.env 变量 | 实例 |
|---|---|---|
| `MARIADB_ROOT_PASS` / `MARIADB_XC3_PASS` | `MDB_ROOT_PASS` / `MDB_PASS` | xc3-mariadb |
| `MYSQL8_ROOT_PASS` | `MYSQL8_PASS` | xc3-mysql8 |
| `MSSQL_SA_PASS` | `MSSQL_PASS` | xc3-mssql |
| `HIGHGO_SUPER_PASS` | `HG_PASS`（业务用户另见 `HG_XC3_PASS`） | xc3-highgo |
| `VASTBASE_PASS` | `VB_PASS` | xc3-vastbase / -b |
| `OPENGAUSS_PASS` | `OG_PASS` | xc3-opengauss |
| `OCEANBASE_SYS_PASS` / `OCEANBASE_TENANT_PASS` | `OB_SYS_PASS` / `OB_PASS` | xc3-oceanbase |
| `ORACLE_PROBE_PASS` | （demo 工作树 `oracle-demo/application.yml`） | xc3-oracle-free |

口令的**唯一权威台账**是 `.local/test-databases.md`（gitignored）；`.local/db-env.env` 是它的派生文件。

## 四、后置步骤：编排不出来的初始化

compose 只解决「容器怎么起」。下面这些必须按 skill 的对应段手工/脚本补：

| 实例 | 后置步骤 | skill 参考 |
|---|---|---|
| **GaussDB** | 镜像无 entrypoint，起的是 tail 常驻容器。必须：`gs_initdb -D /home/omm/probe/data -U omm -w '<口令>' --nodename --host-ip --undostoragetype=page --dbcompatibility=A` → `gs_ctl start -Z single_node` → 建 xc3 角色与 xc3_test 库 + 授权。容器内 gsql 直连要 `LD_LIBRARY_PATH=/opt/gaussdb/app/lib` | §2 GaussDB 段 |
| **Vastbase A/B** | entrypoint 自动解包初始化（约 150s），但宿主目录必须先 `chown 1000:1000`、`host_ips_names.txt` 就位；就绪后要 `GRANT CREATE,USAGE ON SCHEMA public TO xc3` + `ALTER SCHEMA public OWNER TO xc3` | §2 Vastbase 段、§3 public schema |
| **磐维 A/B** | 首装约 10 分钟。三处补丁必须打在「解包之后、checkos 之前」：CPU 预检 `raise`→`pass`、`Common.py` 的 `checkDockerEnv()` 加 `/.dockerenv`、`run.sh` 按当前 IP 重写 `listen_addresses`。**`docker run -e dbcompatibility` 经 systemd 传不进去**，必须 `docker create` + `docker cp` 注入 `/root/.bashrc` 的 `export dbcompatibility=B|A` + `docker start`。装完 `ALTER USER pwadmin PASSWORD '<口令>'` | §2 磐维段、§9 |
| **HighGo** | initdb 已建超管 highgo，但业务用户 xc3 因 passwordcheck 拒含姓名/下划线的口令 → 先建合规占位密码再直写 `pg_authid.rolpassword`，然后 `ALTER USER xc3 VALID UNTIL 'infinity'`；建 xc3_test 库并授权 | §3 瀚高段 |
| **openGauss** | 初始超管是 `opengauss`（无 omm）。建 xc3 用户、建 xc3_test、`GRANT CREATE,USAGE ON SCHEMA public` + `ALTER SCHEMA public OWNER` | §3 public schema |
| **OceanBase** | `MODE=mini` 只自动建租户 `test`、库 `xc3_test`、`root@test`，**不建业务用户 xc3**。用 `root@test` 执行 `CREATE USER 'xc3' IDENTIFIED BY '<口令>'` + `GRANT ALL ON xc3_test.*`（租户内用户名不带 `@test`） | §3 OceanBase 段 |
| **TiDB** | 起容器后设 root 口令：`ALTER USER 'root'@'%' IDENTIFIED BY '<口令>'` | §9 新增实例 |
| **MSSQL** | 引擎内 `max server memory` 默认无限（2147483647）→ 先 `sp_configure 'show advanced options', 1; RECONFIGURE` 再压 512 | §2.5 |
| **Oracle Free** | SGA 默认 1536/1536/512 → `alter system set sga_max_size=512M scope=spfile` 等三条 + 重启容器 | §2.5 |
| **MariaDB / MySQL8** | entrypoint 自动建用户与库（MySQL8 的 initdb 脚本首行必须 `SET NAMES utf8mb4;`，否则中文双编码） | §7 |

## 五、已知边界（2026-11-13 在真实服务器上 dry-run 验证后补写）

- **只能用于全新机器，不能在在役机器上 `up`**。实测 `docker compose --dry-run up -d` 对 13 个在役容器
  全部显示 `Container xxx Creating`——compose 靠 `com.docker.compose.*` label 认领容器，在役容器没有这些 label，
  而 `container_name` 是唯一的 ⇒ 真跑会撞 `Conflict. The container name "/xxx" is already in use`。
  在役机器想"切成 compose 管理"只能删容器重建，那会丢掉容器层改动（磐维的 `run.sh` 补丁与 `.bashrc` 注入、
  Vastbase 的 start.sh 等），**得不偿失**。所以：新机器用配方，在役机器只以配方为"应然状态"参照。
- **必须 `network_mode: bridge`**：compose 默认会建 `db-env_default` 网络，容器 IP 落到新网段（如 172.18.0.x）。
  这会让备份采集的「借 xc3-mysql8 走 `172.17.0.1` 网关」回退通道失效、也改变磐维 `listen_addresses` 绑定的
  网段。配方里 13 个服务都显式 `network_mode: bridge`，复用 Docker 默认 bridge 网段（172.17.0.x），与现状一致。
- **已在役的命名卷要 `external: true`**：只写 `name:` 的话 compose 会报
  `volume "xxx" already exists but was not created by Docker Compose` 并试图"创建"它。`external: true`
  才是"别管它、直接用"。
- **MariaDB 卷是唯一的差异项**：在役是匿名卷（hash 名），配方改为命名卷 `xc3-mariadb-data`，首次 `up`
  会建空卷。要保留现有数据先迁移：
  ```bash
  OLD=$(docker inspect xc3-mariadb -f '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Name}}{{end}}{{end}}')
  docker run --rm -v "$OLD":/from -v xc3-mariadb-data:/to alpine sh -c 'cp -a /from/. /to/'
  ```
- **镜像必须先 tag 回 canonical 名**：配方里写的是 `pingcap/tidb:v6.5.6` 这类 canonical 名，而经轩辕域拉下来的是
  `docker.xuanyuan.run/pingcap/tidb:v6.5.6`。不补 tag 的话 compose 会去 Docker Hub 拉——实测直接撞
  `toomanyrequests: unauthenticated pull rate limit`。每个镜像 `docker tag <mirror>/<name> <name>` 一次。
- **镜像基底**：`mysql:8.0` 默认 tag 与 `tidb:v7.5+` 都是 OL9/Rocky 系，本机 QEMU 虚机缺 `x86-64-v2` 会
  `Fatal glibc error`。配方里已钉 `mysql:8.0-debian` 与 `tidb:v6.5.6`，**别升**。
- **端口**：13 个容器宿主端口都在 20000-29999（安全组整段放行）。`database-lab/` 那套本机配方用
  1xxxx 段，两套互不冲突，各自有守卫。
- **磐维/Oracle 备份**：备份采集脚本文档里落「未知族」被 SKIP（不做文件级 tar，防仓库膨胀），
  这两类实例的数据不在 restic 逻辑导出覆盖内——重建时只重建实例。
- 本配方**不含 `healthcheck`**（现役容器没有，加会改变 `up` 的等待语义），也不含 OceanBase/磐维的
  初始化编排（见第四节）。

## 六、与其它资产的分工

| 资产 | 职责 |
|---|---|
| `dev-support/db-env/docker-compose.yml`（本文） | 13 容器的**容器骨架**：镜像/端口/内存/卷/env |
| `.agents/skills/db-env-provisioning/SKILL.md` | 每类引擎的初始化坑、后置步骤原文、内存治理口径 |
| `dev-support/svbackup/` | 备份体系（采集/上传/恢复），与实例重建是两件事 |
| `.local/db-env-instances.tsv` | 实例索引（无凭据）：回答「该有哪些实例」 |
| `.local/test-databases.md` | 凭据台账：回答「口令是什么」 |