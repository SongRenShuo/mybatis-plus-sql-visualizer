# `database-lab/vendor/` — 厂商二进制与官方文档（离线夹具）

存放**无法从公开渠道稳定下载**的国产数据库 JDBC 驱动与厂商官方手册，供字节层织入校验与离线查阅。

归口在 `database-lab/` 下（该目录本就是「方言真实验证环境的配方资产」），避免在仓库根再开一级目录。

两条边界：

- **不进插件发行包**：`buildPlugin` 只打 `src/main` 的代码与资源，`vendor/` 不参与编译 classpath、不进 zip。
- **不是 `libs`**：这里的东西只被测试读取（`unzip` / `javap` / 字节织入），不会被任何生产代码加载。

## 目录

| 目录 | 内容 | 命名 |
|---|---|---|
| `drivers/` | 驱动 jar（字节层织入 + JVM 链接期校验用） | `<product>-jdbc-<version>.jar` |
| `docs/` | 厂商官方手册 PDF（离线查阅；查证事实以手册章节号写进计划/调研报告） | 保留厂商原文件名 |

## 当前清单

| 文件 | 大小 | sha256 | 来源 |
|---|---|---|---|
| `drivers/panweidb-jdbc-1.0.0.jar` | 844,003 B | `b17065f589a10bdb418b87b47f6ba53a2bfa24a42617cdf7828ec98240ca1156` | 磐维 PanWeiDB V1 驱动（厂商渠道；墨天轮同名 2.0.0 需登录） |
| `drivers/panweidb-jdbc-2.0.0.jar` | 890,254 B | `ed4e0dcade650c5d1760bc37ad76218155f5159a5171af780d6567bb1b95fb5d` | 磐维 V2 驱动（用户从墨天轮下载，2023-12-28 构建；类形与 V1 一致） |
| `docs/磐维数据库（PanWeiDB）V2.0-S3.0.0_Beta应用程序开发指南.pdf` | 3,288,977 B | `9b79be7e7fe265312f064a0c21998808b2ca52272e0a2411855af52556fba698` | open.10086.cn 官方文档 |
| `docs/磐维数据库_V2.0.0 开发者指南.pdf` | 8,301,620 B | `0b08de3ea5575cfdef2226f14e6a7538f96c72e1c6f21b91baa1d6ab129d49aa` | 用户提供；1955 页，表 1-1~1-5 是方言兼容模式的权威依据 |
| `drivers/gdb_mysql-connector-java-oracle-5.1.46.99.jar` | 1,129,905 B | `ccf2f4cbce0dc4978a38683fe059b8dfb736347111301d5a0d51a67d236d7a2f` | 中兴 GoldenDB 自研 JDBC 驱动（用户提供，2026-11-07）。**原始文件名** `gdb_mysql-connector-java-oracle-5.1.46.99(1).jar`，末尾 `(1)` 是浏览器下载重名后缀，入库时已去掉。Manifest：`Implementation-Version 5.1.46.99`、`Bundle-Version 5.1.46.99_2025-0619-0817`、`Bundle-SymbolicName com.goldendb.jdbc`、`Built-By root`、`Created-By 1.8.0_281`；`META-INF/serial.txt` 记 gerrit `JZ_GoldenDB/Driver/JDBC`、分支 `ZXCLOUD-GoldenDB-connector-java-0.0.1-oracle`、rev `6a1c68f`、buildnum 907 |
| `docs/GoldenDB开发支持.pdf` | 3,329,496 B | `3e3df8b988351e235079b612b1321d9033412885078733e0128783bb25b4aab7` | 用户提供；379 页，封面「ZXCLOUD GoldenDB 数据库 / GoldenDB产品手册_开发支持 / **产品版本 V6.1.03.10**」。本仓引用的页码按此文件（清单见 `docs/开发计划/20261107-*-T-GoldenDbDialect-*` §一） |

消费方：

- `sub-projects/sql-visualizer-agent` 的 `StatementWeaverVendorCoverageTest.磐维字节层_驱动jar在位时必须真实织入且过校验`——自动遍历 `drivers/panweidb-jdbc-*.jar`，**每一代都验**（V1/V2 同轮）。
- GoldenDB 驱动**暂无消费方**：`com.goldendb.*` 三面尚未登记进 `DriverRegistry` / `WeaveSpecs`（立项见 `docs/开发计划/20261107-*-T-GoldenDbDialect-*`）。登记后由同一条「字节层_驱动jar在位时必须真实织入且过校验」用例按 `drivers/gdb_mysql-connector-java-*.jar` 自动遍历。

## 约定

1. **只放公开渠道拿不到或极不稳定的原件**；能稳定公开下载的一律在文档里写链接 + 校验和，不落盘。
2. **新增文件必须登记上表**（名称 / 大小 / sha256 / 来源），并注明哪个测试消费它。
3. **查找顺序**：测试优先读 `-D<name>.jar` 或环境变量（如 `PANWEI_JAR`），缺省回落到 `database-lab/vendor/drivers/<name>`（从 `user.dir` 逐级向上找）；文件不在位时用例**显式 skip**，不静默通过。
4. **许可**：这些是厂商二进制与文档，仅作仓库内测试与离线参考，发行包不包含它们；对外再分发前请与厂商确认授权。
5. **体积**：单文件 ≤ 20MB（GitHub 硬限 100MB）；超限改用外链 + 校验和，不入库。

## 与 `.local/` 的分工

`.local/` 是**机器本地**文件（凭据、本机路径覆盖），整目录被 git 忽略；`vendor/` 是**要入库的厂商原件**。两者不要混放。
