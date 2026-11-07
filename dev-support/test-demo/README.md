# 测试 demo 与本地数据库

本目录把「跑全量测试所需的测试 demo、数据库脚本和 IDE 数据源模板」收在一起，避免开发者只克隆插件仓库后直接跑 `testShards`，在 demo 普查用例上成片失败。

测试 demo 仍是独立仓库，插件仓库不复制它的源码：

- 仓库：<https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer-demo>（MIT）
- 本插件版本对应的快照：见 [`demo-revision.txt`](demo-revision.txt)（当前 `fcb1faf`，2026-11-07——GoldenDB 方言 demo 用例与连接 profile 随 T-GoldenDbDemoCases 进快照；`db/*.sql` 仍为 `74cc257` 期数据转储，GoldenDB 用例走端点连接不经本地转储）
- 默认位置：插件仓库的**兄弟目录** `../mybatis-plus-sql-visualizer-demo`（与 `buildSrc`、测试类的默认约定一致）

## 先看结论：哪些测试需要什么

| 测试入口 | 测试 demo | 本地 MySQL | 说明 |
|---|---|---|---|
| `./gradlew testFast` | 不需要 | 不需要 | 纯逻辑快测 |
| `./gradlew test --tests "…"` | 看用例 | 看用例 | 普通平台测试不需要；`Demo*` 普查类需要 demo |
| `./gradlew testShards` | **需要** | 不需要 | 5 个 `Demo*` 用例会硬断言 demo 文件存在；DB 集成类不在分片里 |
| `./gradlew testDatabase` / `testDb*` | **需要** | **需要** `mpj_demo` | 测试读取 demo 的 `.idea/dataSources.xml`；缺库时多数用例显式跳过 |
| `./gradlew e2eFakeIde` | **需要**（且需 Maven 与 `~/.m2`） | **需要** `mpj_demo` + 初始数据 | 启动真实的 `mybatis-plus-join-demo` 应用 |

因此：

- 只改纯逻辑：`testFast` + 定向 `test` 即可，不必装数据库。
- 准备跑 `testShards`：先执行一次本目录的设置脚本，确保 demo 在位。
- 要跑 `testDatabase` / `e2eFakeIde`：再初始化 MySQL（见下）。

## Gradle 预检

`testShards` 与 `testDatabase` 会先执行 `verifyDemoFixture`：检查 demo 目录和测试实际读取的关键文件，缺失时**提前失败并给出设置命令**，不会等 5 个分片跑完才在 `Demo*` 断言上成片报红。

```bash
./gradlew verifyDemoFixture                 # 单独检查
./gradlew testShards                        # 自动先跑 verifyDemoFixture
./gradlew testShards -PmpDemoProjectDir=<demo 路径>
./gradlew testShards -PskipDemoFixtureCheck=true   # 明确跳过预检（仍可能因缺 demo 失败）
```

## 一键设置

Windows（Git Bash 或 PowerShell 均可）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/test-demo/setup-test-env.ps1
```

```bash
bash dev-support/test-demo/setup-test-env.sh
```

脚本会：

1. 若默认位置没有 demo，则 `git clone` 并检出 `demo-revision.txt` 指定的提交；
2. 校验测试实际读取的关键目录/文件；
3. 把 `templates/` 下的最小 IDE 数据源文件复制到 `<demo>/.idea/`（已存在则保留，不覆盖）；
4. 若本机有 `mysql` 客户端，创建 `mpj_demo` / `mp_demo` 并执行本目录 `db/` 下的初始化脚本；
5. 打印各测试入口的下一步命令。

常用参数/环境变量：

| PowerShell | Bash | 含义 |
|---|---|---|
| `-DemoDir <path>` | `DEMO_DIR=<path>` | 指定 demo 目录 |
| `-Revision <sha>` | `DEMO_REVISION=<sha>` | 指定要检出的提交 |
| `-CheckOnly` | `CHECK_ONLY=1` | 只检查，不 clone、不建库 |
| `-SkipDb` | `SKIP_DB=1` | 只准备 demo，不初始化数据库 |
| `-DbHost/-DbPort/-DbUser` | `MYSQL_HOST/MYSQL_PORT/MYSQL_USER` | MySQL 连接参数，默认 `127.0.0.1:3306` / `root` |
| `-DbPassword <pwd>` | `MYSQL_PASSWORD=<pwd>` | 密码；缺省读 `MYSQL_PWD`，不落盘 |
| `-MysqlBin <path>` | `MYSQL_BIN=<path>` | `mysql` 客户端路径；缺省从 PATH 查找。phpstudy / XAMPP 等自带客户端不在 PATH 时用这个 |
| `-RequireDb` | `REQUIRE_DB=1` | 数据库不可用时以非 0 退出（默认只警告） |

## 数据库与 SQL 脚本

当前测试只依赖两个库：

| 数据库 | 脚本 | 用途 |
|---|---|---|
| `mpj_demo` | [`db/mpj_demo.sql`](db/mpj_demo.sql) | `testDatabase` / `testDb*` 与 `e2eFakeIde` 的主库；表 `user`、`user_address`、`area`、`car_device_info`、`station_info` 等 |
| `mp_demo` | [`db/mp_demo.sql`](db/mp_demo.sql) | 部分测试的数据源名/库名语料；建表脚本与 `mybatis-plus-demo` 对齐 |

手工初始化（MySQL 5.7+/8.0）：

```bash
mysql -h127.0.0.1 -P3306 -uroot -p \
  -e "CREATE DATABASE IF NOT EXISTS mpj_demo DEFAULT CHARSET utf8mb4; CREATE DATABASE IF NOT EXISTS mp_demo DEFAULT CHARSET utf8mb4;"

mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 \
  mpj_demo < dev-support/test-demo/db/mpj_demo.sql

mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 \
  mp_demo < dev-support/test-demo/db/mp_demo.sql
```

验证（`mpj_demo` 的初始数据量应大于 0）：

```bash
mysql -h127.0.0.1 -P3306 -uroot -p -N -B \
  -e "SELECT COUNT(*) FROM mpj_demo.user; SELECT COUNT(*) FROM mpj_demo.car_device_info;"
```

> 脚本自带 `DROP TABLE`，可重复执行；但会清掉这两个库里的同名表，不要指向有业务数据的库。
> `mpj_demo` 的 `user` 表是反引号保留字，手工查询时记得加反引号。

## IDE 数据源文件

`testDatabase` / `testDb*` 不自己建数据源，而是读 `<demo>/.idea/dataSources.xml` 与 `dataSources.local.xml`：

- [`templates/dataSources.xml`](templates/dataSources.xml)：`mpj_demo@localhost` 与 `mp_demo@localhost` 的 JDBC URL；
- [`templates/dataSources.local.xml`](templates/dataSources.local.xml)：用户名 `root`。

设置脚本会把它们复制到 `<demo>/.idea/`（只在文件不存在时复制）。如果你已经用 IDEA 打开过 demo 并建过同名数据源，脚本不会覆盖；只要名称里包含 `mpj_demo` 即可被测试识别。

密码不写入模板：测试常量使用 `root/root`（本地开发库），与 demo 的 `application.yml` 一致。

## 路径覆盖

三个默认路径都可以覆盖：

- 分片/demo 普查：`-PmpDemoProjectDir=<demo 路径>`（`testShards` 透传给测试 JVM）
- `e2eFakeIde`：`-Pe2eDemoDir=<demo 路径>`，或环境变量 `MPSV_DEMO_ROOT`
- 手工跑单个测试：`-DmpDemoProjectDir=<demo 路径>`

如果 demo 放在别处，设置一次环境变量即可：

```bash
export MPSV_DEMO_ROOT=/path/to/mybatis-plus-sql-visualizer-demo
```

`.local/local.env` 也可写 `MPSV_DEMO_ROOT=…`（见 `.local/local.env.example`）。

## 可选：JAR 态夹具

`JpaJarClassFileAnnotationTest` 需要 `jpa-jar-model-lib/target/jpa-jar-model-lib-1.0.0.jar`；缺 jar 时该用例按既有约定显式 SKIP，不会失败。要覆盖这条链路：

```bash
cd ../mybatis-plus-sql-visualizer-demo
mvn -pl jpa-jar-model-lib package
```

`e2eFakeIde` 还需要 demo 的 Maven 依赖已在 `~/.m2` 中（离线运行）；首次可先在 demo 根执行 `mvn -o compile` 或按 `dev-support/e2e-fake-ide/README.md` 准备。

## 更新 demo 快照

demo 更新后，测试的普查断言可能依赖新的文件/行号，不能盲目跟随最新版：

1. 在 demo 仓库确认目标提交（如 `git log -1`）；
2. 更新 [`demo-revision.txt`](demo-revision.txt)；
3. 同步两个 SQL 脚本（保留本目录脚本头部的来源/许可注释）：
   - `mybatis-plus-join-demo/src/main/resources/db/schema.sql` → `db/mpj_demo.sql`
   - `mybatis-plus-demo/src/main/resources/db/schema.sql` → `db/mp_demo.sql`
4. 跑 `testShards`，确认 `Demo*` 普查用例与计数断言仍通过；失败时先判断是 demo 形态变化还是插件回归。

## 常见失败

| 现象 | 原因 | 处理 |
|---|---|---|
| `demo 文件应存在: …/mybatis-plus-sql-visualizer-demo/...` | demo 不在默认兄弟目录 | 跑设置脚本，或设 `-PmpDemoProjectDir` |
| `testDb*` 大量 SKIP / “demo 项目数据源配置不可读” | 缺 `.idea/dataSources.xml` | 跑设置脚本复制模板，或用 IDEA 打开 demo 建 `mpj_demo` 数据源 |
| `mysql client not found` | phpstudy / XAMPP 等自带客户端不在 PATH | `-MysqlBin <.../mysql.exe>` 或 `MYSQL_BIN=<.../mysql.exe>`；也可按输出手工执行 SQL |
| `testDb*` 连接失败 / `Communications link failure` | MySQL 未启动、库未建、密码不是 `root` | 按上文初始化；确认 `127.0.0.1:3306` 可连 |
| `e2eFakeIde` 编译 demo 失败 | 缺 Maven 或 `~/.m2` 离线依赖 | 安装 Maven，先在 demo 目录执行 `mvn -o compile` |
| `e2eFakeIde` 端到端断言失败 | demo 版本与 `demo-revision.txt` 不一致 | 检出指定提交，或更新快照并复跑插件测试 |
