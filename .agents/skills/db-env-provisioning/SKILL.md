---
name: db-env-provisioning
description: 数据库真机环境搭建与验证作业法——在远程 Linux 靶机上用 Docker 部署国产/商业数据库（瀚高、海量、OceanBase、GaussDB、SQL Server、MariaDB、达梦、金仓、磐维 PanWeiDB 等）、选镜像源、找不到官方镜像时按矩阵取证并降级到第三方重打包镜像、配端口与凭据、登记 DBX，并用插件自己的渲染套件在真引擎上验证。当用户要求「装个 XX 数据库」「补真机环境」「部署测试实例」「镜像拉不动」「找不到官方镜像」「实例连不上」「真机读数怎么取」时使用。
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

第五件是**读台账**：`.local/test-databases.md` 里已有全部实例的连接、凭据、已知坑；`tmp/xc3-env-init/*.md`
是每个实例的部署原文与探针读数。**先看有没有现成实例能用**，别上来就装——`114.66.55.245` 那台
DBX 测试主机上本来就有一批（MySQL/PG/Oracle/DM/金仓/GBASE/虚谷/StarRocks/Doris…），很多时候只缺驱动。

内存账（15G 宿主的实际配法，超了会 OOM 或让引擎起不来）：瀚高 3g、海量 A 4g、海量 B 2g、OceanBase 8g
（容器内 `OB_MEMORY_LIMIT=6G`）、SQL Server 2g、GaussDB 4g、MariaDB 2g。全部同时在线时宿主要留出
buff/cache 之外的余量，装新实例前先看 `free -g` 的 available。

**内存闸门（起新库前必看读数，不是「大概够」）**：2026-11-06 磐维两个实例上线后，16G 宿主 + 11 个既有容器
的 `available` 只剩约 **960 MB**（`Mem: 16003 total / 9068 used / 966 available`）。且**国产库常有引擎内存下限**，
不是想压就压：磐维 `max_process_memory` 低于约 5 GB 直接拒启（见 §2 磐维段）。所以流程是
**先 `free -m` 看 available → 不够就先停/降配既有实例 → 再起新的**，别先起后救。

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
   `The value "0.0.0.0,localhost" ... is incorrect`），只能改文件：

```bash
ip=$(hostname -I | awk '{print $1}')
sed -i "/^listen_addresses/c\\listen_addresses = 'localhost,${ip}'" /database/panweidb/data/postgresql.conf
```

另外两条与产品语义绑定的规矩：

- **兼容档是安装期参数，不能切片**。3.4.0 的 `CREATE DATABASE` **没有** `DBCOMPATIBILITY` 选项
  （`\h create database` 实证），`-e dbcompatibility=A|B|PG` 只在首装时生效 ⇒ **A/Oracle 与 B/MySQL 必须两个实例**，
  别指望单实例内切档（实例内所有库连同 `template*` 都是同一个 `datcompatibility`）。
- **内存有引擎下限，且下限不是容器 limit**。`max_process_memory` 低于
  `2GB + cstore_buffers(512MB) + (udf_memory_limit - UDF_DEFAULT_MEMORY) + shared_buffers + preserved memory(2241MB)
  ≈ 5057MB` 时直接 `FATAL: the values of memory out of limit`。想省内存就压 `shared_buffers`
  （1GB→256MB，实测常驻 RSS 从 1.42GB 降到 0.9GB），`max_process_memory` 抬到 8GB 即可（它是记账上限，不是预占）。

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
| `mysql:8.0` 容器秒退，日志 `Fatal glibc error: CPU does not support x86-64-v2` | 官方 `mysql:8.0` 是 **Oracle Linux 9** 基底，glibc 以 x86-64-v2 为基线；靶机是 QEMU 虚机（`/proc/cpuinfo` 无 `sse4_1/sse4_2/popcnt`，只有 `cx16`） | 换 **`mysql:8.0-debian`**（Debian 12）。已拉的 OL9 版在本机不可用，别反复试 |
| 灌进库的中文变 `C3A5C2BCC2A0…`（HEX 可验） | 容器内 `LANG` 为空 → mysql 客户端 `--default-character-set=auto` 落到 **latin1**，initdb 阶段 UTF-8 种子被双编码 | 初始化脚本**首行**加 `SET NAMES utf8mb4;` 后重灌；重建卷时这行必须保留（原始副本留 `.orig`） |
| 容器起来了但外部连不上（容器内/localhost 正常） | MySQL 的 `root` 默认只允许 `localhost` | 起容器加 `-e MYSQL_ROOT_HOST=%` |
| `gs_preinstall` 报 `This cluster is missing the rdtscp or avx instruction` 并退出 | 预检要求 `lscpu` 同时有 avx 与 rdtscp，QEMU vCPU 没有；**引擎本体不需要** | 把 `script/local/PreInstallUtility.py` 里 `check_cpu_instructions` 的 `raise` 改成 `pass`（留 `.orig`） |
| `gs_checkos` 报 GAUSS-51400（`find /etc/sysconfig/network-scripts` 失败）或 GAUSS-50612（`ethtool -g eth0` Operation not supported） | cgroup v2 下 `checkDockerEnv()` 恒假 ⇒ 9 处「容器内可容忍」分支失效 | 在 `checkDockerEnv()` 加 `/.dockerenv` 判定（首选）；或补 `mkdir -p /etc/sysconfig/network-scripts` |
| 容器重启后 `FATAL: could not create listen socket for "172.17.0.x:17700"` | `listen_addresses` 写死了安装时的容器 IP，重启换了 IP | 启动脚本里按 `hostname -I` 重写 `postgresql.conf` 的 `listen_addresses`（`gs_guc` 不收 `*`/逗号值） |
| 起库报 `the values of memory out of limit ... = 5057MB` | `max_process_memory` 被压到引擎下限以下 | 抬回 ≥5GB（记账上限非预占），改压 `shared_buffers` |
| `CREATE DATABASE … DBCOMPATIBILITY 'A'` 报 `option "dbcompatibility" not recognized` | 该产品的兼容档是**安装期**参数，不是建库选项 | 另起一个容器按目标档安装，别在单实例里切档 |

## 8. 与插件验证的关系（为什么值得花这个功夫）

插件的方言能力最终要落到「渲染出来的 SQL 在真引擎上能不能跑」。这一轮靠真机抓出三个只有真引擎才暴露的
问题：SQL Server 无排序分页的 `OFFSET/FETCH` 非法、OceanBase EXPLAIN 输出形态登记错档、premain 补挂延迟
单位量级错（10s 变成 10^10ms）。这些在 H2 兼容模式与「MySQL 承载 MariaDB 驱动」的替代读数里全是绿的。

所以：**装环境不是目的，取到引擎级读数才是**。装完立刻按 §4 第三档跑一遍，跑不出读数就等于没装。

## 9. 灾难重建：整套环境从零恢复（靶机随时可能被重装）

**前提变了**（2026-11-05 用户定调）：测试服务器的服务**可能随时被重装系统**，容器、卷、数据、initdb 结果全部
丢失。所以「部署」不再是手工操作，而必须是**声明式 + 幂等可重放**的：

- **唯一事实源是清单**：`.local/db-env-instances.tsv`（gitignored，可含明文凭据），模板见
  `scripts/instances.example.tsv`；台账 `.local/test-databases.md` 记录连接/凭据/坑。
- **重放引擎是脚本**：`scripts/bootstrap-db-env.sh <清单>`——已存在的容器跳过、镜像缺失才拉（带镜像源回退）、
  按 `verifyCmd` 轮询就绪、最后打印端口汇总并列出未就绪项。**可反复执行**，失败重跑只补没起来的那些。
- **种子数据要能自动灌**：把 `.sql` 放清单的 `initDir`（挂到 `/docker-entrypoint-initdb.d:ro`），镜像首次
  初始化时自动执行；每条 `.sql` 首行必须 `SET NAMES utf8mb4;`（见 §7 的双编码坑）。

### 重建顺序（靶机是全新系统时照这个走）

```bash
# 0) 宿主就绪：Docker 在跑、daemon.json 有可用 mirror（见 §1）
docker info >/dev/null && echo ok
free -g | head -2                      # 按 §0 的内存账排布实例，别一次全上

# 1) 上传清单与种子（本地 → 靶机）
#    .local/db-env-instances.tsv          实例清单（含凭据）
#    /root/<实例>-init/*.sql              种子脚本（首行 SET NAMES utf8mb4;）
scp .local/db-env-instances.tsv root@103.36.220.245:/root/
scp -r dev-support/db-seed/<实例>/*  root@103.36.220.245:/root/<实例>-init/

# 2) 一键重建
ssh root@103.36.220.245 'bash /root/bootstrap-db-env.sh /root/db-env-instances.tsv'

# 3) 外部可达性 + 数据正确性（两档都要取，别只看容器 Running）
#    - 从本机用 DBX/客户端实连一次（SELECT 1 / VERSION()）
#    - 中文正确性用 HEX 钉死：SELECT HEX(name) —— 必须等于 HEX('张三') = E5BCA0E4B889
#    - 表与行数：SHOW TABLES + 逐表 COUNT(*)

# 4) 收尾：台账补行 → DBX 登记（首个查询可能 CONNECTION_OUT_OF_SCOPE，放行一次）→
#    若参与 CI，更新仓库 secrets（-PmpvDbUrl/-PmpvDbUser/-PmpvDbPassword）
```

### 重建时不做的事

- **不要**为了「快点」改用 apt 装系统级数据库（§7 已记 mariadb 的冲突案例）；
- **不要**动宿主系统参数（`vm.max_map_count` 等）——对既有实例是风险；
- **不要**在没有清单的情况下手工 `docker run` 一串——那样下次重装还得从头踩一遍；
- **不要**把凭据写进 `docs/`、`src/`、skill 本体——只落 `.local/`。

### 恢复演练（可选但建议）

判定「这套东西真能重建」的唯一证据是**真跑一次**：删掉一个非关键容器 → 用脚本重建 → 复读端口/表数/中文 HEX。
不做演练的声明只能算「应该有」，与 skill §4 的三档证据同一口径。

