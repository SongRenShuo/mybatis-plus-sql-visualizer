# 方言 Database Lab（T-DialectLabMatrix）

方言真实验证环境的配方资产与操作指引。结构借 DBX Database Lab 的三件套思路：钉版本配方 +
healthcheck + 不起库即可 CI 校验的脚本化守卫（`DockerComposeLabConsistencyTest`）。

## 资产清单

| 文件 | 用途 |
|---|---|
| `../src/test/resources/docker-compose-dialects.yml` | 五开源库配方（mysql:8.0.36 / postgres:16.2 / mariadb:11.2 / mssql:2022-CU12 / oracle-free:23.5-slim），钉版本 + healthcheck |
| `docker-compose.dialects-cn.example.yml` | 国产库示例片段（DM8 / KingbaseES，镜像名占位待 load 后替换） |
| `load-dm8.ps1` / `load-kingbase.ps1` | 官方 tar 包导入脚本（docker load + 后续步骤提示） |
| `vendor/drivers/`、`vendor/docs/` | 公开渠道拿不到的厂商驱动 jar 与官方手册 PDF（磐维 V1 驱动 + V2 应用开发指南），供增强捕获字节层用例与离线查阅；约定与清单见 `vendor/README.md` |
| `DockerComposeLabConsistencyTest`（测试源码） | 配方一致性守卫：钉版本 / 端口避让 / 必填 env / healthcheck 四硬约束 + 本 README 端口引用一致性 |

## 五开源库：一键起环境

```bash
docker compose -f src/test/resources/docker-compose-dialects.yml up -d
docker compose -f src/test/resources/docker-compose-dialects.yml ps   # 等待全部 healthy
```

端口（1xxxx 避让段，与国产库示例互不冲突）：MySQL **13306** / PostgreSQL **15432** /
MariaDB **13307** / SQL Server **11433** / Oracle Free **11521**。凭据：root/system 账户
`p05pass`（SQL Server sa 为 `P05pass!strong`），库名 `p05`。

## 国产库（达梦 DM8 / 人大金仓 KingbaseES）

### 取得路径（T-DL-01 调研结论：路径 A 半自动——tar 须人工取得，load 可脚本化）

| 库 | 官方渠道 | 形态 | 要点 |
|---|---|---|---|
| DM8 | https://eco.dameng.com/download/ | Docker 镜像 tar（如 `dm8_20250206_x86_rh6_rq_single.tar`） | 实名注册；无 Docker Hub 官方镜像；找不到时联系达蒙商务 |
| KingbaseES | https://www.kingbase.com.cn/download.html | 镜像 tar 精简包（如 `kdb_x86_64_V009R001C002B0014.tar.gz`） | 按 CPU 架构选包；企业版需 License（官网免费申请开发授权 https://www.kingbase.com.cn/sqwjxz/index.htm） |

官方部署手册：DM8 https://eco.dameng.com/community/article/a2dac57039639185037baceca8773a10 、
KingbaseES https://help.kingbase.com.cn/v8/install-updata/install-docker/index.html 。

社区第三方镜像（Docker Hub 等）存在许可证与分发合规风险，仅作调研记录，配方只指向官方渠道。

### 导入与启动

```powershell
# 1) 官网下载 tar 后（以 DM8 为例，金仓同理换 load-kingbase.ps1）：
powershell -ExecutionPolicy Bypass -File database-lab/load-dm8.ps1 -Tar C:\Downloads\dm8_20250206_x86_rh6_rq_single.tar

# 2) 按脚本提示，用 docker images 查到的实际镜像名替换 docker-compose.dialects-cn.example.yml 的占位

# 3) 起容器：
docker compose -f database-lab/docker-compose.dialects-cn.example.yml up -d
```

默认端口：DM8 **15236**（容器 5236，SYSDBA/SYSDBA）、KingBase **15433**（容器 54321，
system/p05pass 以脚本环境变量为准）。

## 验证命令样例（外部实例模式，T-DL-04）

与五开源库完全相同的 `DialectDockerVerificationTest` 套件（TC 套件 + 保留字全量扫描），
方言名与设置页下拉一致（`DialectRegistry.byName`）：

```bash
# 达梦 DM8（JDBC URL 按实际端口/实例；驱动达梦 JDBC，测试机需可达）
./gradlew test --tests '*DialectDockerVerificationTest*' \
  -Dmpvs.dialect.jdbcUrl="jdbc:dm://localhost:15236?schema=SYSDBA" \
  -Dmpvs.dialect.user=SYSDBA -Dmpvs.dialect.password=SYSDBA \
  -Dmpvs.dialect.name="DM（达梦）"

# 人大金仓 KingbaseES
./gradlew test --tests '*DialectDockerVerificationTest*' \
  -Dmpvs.dialect.jdbcUrl="jdbc:kingbase8://localhost:15433/test" \
  -Dmpvs.dialect.user=system -Dmpvs.dialect.password=p05pass \
  -Dmpvs.dialect.name="KingBase（人大金仓）"
```

注：DM/KingBase 的 JDBC 驱动不在 Maven Central 公开仓库（厂商分发），跑上述命令前需把
`DmJdbcDriver18.jar` / `kingbase8.jar` 装进本地 maven 仓库或直接放入测试 classpath；
未配置驱动时该命令会在建连阶段报 `No suitable driver`——属环境缺失而非用例失败。

## 验证状态台账（诚实口径）

| 环节 | 状态 |
|---|---|
| 五开源库配方一致性 | 已守卫（DockerComposeLabConsistencyTest，每日常规测试自动跑） |
| 五开源库真实容器 | 本机 Docker 可用时由 Testcontainers 用例自动执行；compose 环境为手动补充 |
| DM8 / KingBase 真实容器 | **环境受限未验证**——tar 包未取得（实名/许可），取得后按上节命令执行并在开发计划 §八 回填结果 |
| DM / KingBase 文档级 | DialectDocGoldenTest 持续在跑（官方文档口径） |
