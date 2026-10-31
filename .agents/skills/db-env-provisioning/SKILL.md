---
name: db-env-provisioning
description: 数据库真机环境搭建与验证作业法——在远程 Linux 靶机上用 Docker 部署国产/商业数据库（瀚高、海量、OceanBase、GaussDB、SQL Server、MariaDB、达梦、金仓等）、选镜像源、配端口与凭据、登记 DBX，并用插件自己的渲染套件在真引擎上验证。当用户要求「装个 XX 数据库」「补真机环境」「部署测试实例」「镜像拉不动」「实例连不上」「真机读数怎么取」时使用。
---

# db-env-provisioning 数据库真机环境作业法

靶机固定是「不大-北京-16G」（`103.36.220.245`，Ubuntu 22.04 / 16C / 15G / Docker 28，SSH 走 DBX 连接
`2af473a2-f9fc-4478-846a-4b5202d606c5`）。本文件是把 2026-10 这一轮部署十来个实例踩出来的东西固化下来，
**照单执行能省掉至少一半返工**。

三条铁律先记住：

1. **端口只用 20000–29999**（用户定的，安全组已整段放行）。容器内端口照旧（5432/3306/1433/2881），
   宿主一律映射到这个段里，别把 5432 这类常用端口暴露出去。
2. **凭据只落 `.local/`**（`.local/test-databases.md`），仓库里的 skill/文档只写 `host:port` 与库名。
   同理，厂商镜像的 license/trial 状态、密码策略这些坑要写进台账，别只留在脑子里。
3. **容器一律 `--restart unless-stopped`**，并且**数据卷/数据目录不要随手重建**——多数实例的账号与库
   是首次 initdb 时按环境变量建的，重建等于从头再来一遍凭据流程。

## 0. 开工前先查五件事

```bash
docker ps --format '{{.Names}}\t{{.Status}}\t{{.Ports}}' | sort   # 已有什么、端口占用
free -g | head -2                                                # 余量（见下「内存账」）
docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}'      # 镜像是不是已经有了
cat /etc/docker/daemon.json                                      # 已配的 mirror（不代表好用）
```

第五件是**读台账**：`.local/test-databases.md` 里已有全部实例的连接、凭据、已知坑；`tmp/xc3-env-init/*.md`
是每个实例的部署原文与探针读数。**先看有没有现成实例能用**，别上来就装——`114.66.55.245` 那台
DBX 测试主机上本来就有一批（MySQL/PG/Oracle/DM/金仓/GBASE/虚谷/StarRocks/Doris…），很多时候只缺驱动。

内存账（15G 宿主的实际配法，超了会 OOM 或让引擎起不来）：瀚高 3g、海量 A 4g、海量 B 2g、OceanBase 8g
（容器内 `OB_MEMORY_LIMIT=6G`）、SQL Server 2g、GaussDB 4g、MariaDB 2g。全部同时在线时宿主要留出
buff/cache 之外的余量，装新实例前先看 `free -g` 的 available。

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

拉下来后重打本地标签再起容器，脚本里写不带 mirror 前缀的名字：

```bash
docker pull docker.m.daocloud.io/library/mariadb:10.11.11
docker tag  docker.m.daocloud.io/library/mariadb:10.11.11 mariadb:10.11.11
```

例外：`mcr.microsoft.com`（SQL Server）直连即可，不走 Docker Hub 也不受上面这套影响。
`dockerproxy.net` 逐层 curl 是最后的兜底（瀚高那次 daemon 对多分层镜像挂死就是用它救的：
按 registry v2 API 下 blob → 校验 sha256 → 组 manifest → `docker load`；注意并发 8 路会 429，要串行）。

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

## 3. 凭据与权限：真正卡人的都在这

**瀚高 `passwordcheck`**（最费时间的一个）。initdb 会拒含 `_` 和 `-` 的密码，要求大小写+数字+特殊字符，
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

**动手前先验权限**，别等建表才发现：

```sql
select current_user, current_database(),
       has_database_privilege(current_user, current_database(), 'CREATE') as db_create,
       has_schema_privilege(current_user, 'public', 'CREATE') as pub_create;
```

这条是给「别人给的实例」用的：官方 openGauss 5.0.0 那台（`114.66.55.245:20105`）驱动能连、密码也对，
但两个权限都是 false，任何 DDL 类验证都做不了——**换实例，别硬修**（改权限要超级用户，我们没有）。

**OceanBase** 的 MySQL 协议登录名是 `user@tenant`（如 `xc3@test`、`root@sys`），少了 `@tenant` 连不上。

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

## 5. 登记 DBX 与驱动

**DBX 登记**（用户惯例）：加进 **Song 分组**，命名 `<服务器短名> | <产品名+版本>`，如
`不大 | OceanBase CE 4.3.5`、`不大 | Vastbase G100 V3.0 (B 模式)`。

三个工具限制要提前知道，免得反复返工：

- `dbx_add_connection` **没有分组参数**，新建的连接只能落在默认位置，得请用户手动拖进 Song 分组；
  MCP 也没有 edit/update 连接的工具（只有 add/duplicate/remove）。
- **`db_type` 不是任意值**：`oceanbase` 不支持（`INVALID_CONNECTION_TYPE`），用 `mysql` 类型 + 用户名带租户；
  `highgo/vastbase/kingbase/dameng/sqlserver/opengauss` 原生支持。
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

拉驱动不方便时让服务器自己去阿里云 maven 镜像拿：
`curl -sL -o <file> https://maven.aliyun.com/repository/public/<group/path>/<artifact>-<ver>.jar`。
下完先验内容再信：`unzip -l <jar> | grep -c "org/postgresql/"`、`unzip -p <jar> META-INF/services/java.sql.Driver`。

## 6. 台账与收尾

- **连接/凭据/已知坑** → `.local/test-databases.md`（gitignored）。新增实例补一行：URL、账号、容器名、
  镜像、停起命令、该实例特有的坑。
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

## 8. 与插件验证的关系（为什么值得花这个功夫）

插件的方言能力最终要落到「渲染出来的 SQL 在真引擎上能不能跑」。这一轮靠真机抓出三个只有真引擎才暴露的
问题：SQL Server 无排序分页的 `OFFSET/FETCH` 非法、OceanBase EXPLAIN 输出形态登记错档、premain 补挂延迟
单位量级错（10s 变成 10^10ms）。这些在 H2 兼容模式与「MySQL 承载 MariaDB 驱动」的替代读数里全是绿的。

所以：**装环境不是目的，取到引擎级读数才是**。装完立刻按 §4 第三档跑一遍，跑不出读数就等于没装。
