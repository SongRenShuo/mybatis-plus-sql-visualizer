---
name: project-gauss-multimodule-repro-fixture-20261125
description: Gauss 双模块并行 SQL 捕获复验装置（demo 仓
  gauss-multi-module-a/b）——用户报「第二个模块启动后第一个模块丢 SQL」，MySQL 不复现、真实环境双 Gauss
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1e0efec0-3604-43a3-a92e-43d582632f93
---

2026-11-25 用户报新现象（与 [[project-multimodule-console-yield-20261121]] 修复方向相反）：多模块场景下**第二个模块启动后，第一个模块的 SQL 就捕获不到了**。用户用 demo 的两个模块（MySQL）复验未复现；其真实环境两个模块都连 Gauss，故要求准备两个 Gauss 模块复验，并确保可编译、可启动、接口可调用后提交。

复验装置（demo 仓 `F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer-demo`，Maven 聚合多模块，子模块各自 parent=spring-boot-starter-parent 2.6.3、互不继承根 pom）：

- `gauss-multi-module-a`：端口 8091，表 `gm_item_a`，包 `cn.song.gaussmultia`
- `gauss-multi-module-b`：端口 8092，表 `gm_item_b`，包 `cn.song.gaussmultib`
- 两者结构对称、各自独立可运行；聚合 pom `<modules>` 与根 README 子项目表/数据库初始化表需同步登记

数据源：GaussDB Kernel 503.1.0 @ `jdbc:gaussdb://103.36.220.245:25882/xc3_test`，用户 `xc3`；驱动 `com.huaweicloud.gaussdb:gaussdbjdbc:506.0.0.b058-jdk7`（本地 `~/.m2` 已有）。DBX 连接 id `11ba236d-28e2-426d-b798-74bf1e7fc72a`。

库侧已就绪（2026-11-25 实测）：`gm_item_a` / `gm_item_b` 建在 `public`（`search_path="$user",public`，`xc3` schema 不存在故无遮蔽），`id SERIAL PRIMARY KEY`、`create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP`（catalog 显示 `pg_systimestamp()`），各 3 行种子（alpha-1..3 / beta-1..3）；未加 schema 前缀的查询全部通过。

接口契约（每模块）：`GET /item/list`、`GET /item/detail/{id}`、`POST /item/create?name=`、`DELETE /item/delete/{id}`、`GET /item/pulse?times=n`（循环查询、连续产 SQL）、`GET /healthz`（模块名/端口/count）。

配置硬约束：MyBatis `log-impl` 必须是 `org.apache.ibatis.logging.slf4j.Slf4jImpl` + `logging.level.<mapper 包>=DEBUG`，不能用 `StdOutImpl`——行级让位判据依赖日志行前缀的 PID，理由与失效形态见 [[pitfall-multimodule-repro-slf4j-pid-contract]]。

根因（2026-11-25 已证实并修复，`ad4f5994b`）：与 Gauss / 多数据源无关。EP 控制台通道只在控制台创建时装配，捕获服务未创建时 `getDefaultFilters` 返回空 → 先启动的应用永远没有 EP；桥是捕获开启后补挂的（单独跑时靠桥正常）；后建控制台的 EP 一产出就置位进程级 `epActive`，旧控制台的桥随即被全局短路 → 只剩后启动的。截图 `EP 通道已短路 3,721 行` + `产出 0 条` 就是这条链。修法＝provider 恒返回 Filter + Filter 内惰性取 manager（500ms 限频），捕获开启后同一实例自动生效。详见 [[pitfall-ep-provider-attach-before-capture-20261125]]。截图里 Oracle/Druid 未登记实现是增强白名单的另一条账，与本现象无因果。

施工注意：demo 仓工作树有两处他人未提交改动（`mybatis-plus-demo/.../UserServiceImpl.java`、`SchedulerDemoTask.java`），提交新模块严禁 `git add -A`。本机 `JAVA_HOME`=JDK17、PATH `java`=JDK8，mvnd 在 `D:/HuanJing/Maven/maven-mvnd-1.0.3/bin/mvnd.cmd`；8091/8092 实测空闲。

状态（2026-11-25）：装置已交付并本地提交，**缺陷本身未复现/未定位**，等用户带插件在 IDE 里复验。

- 编译：`mvnd -pl gauss-multi-module-a,gauss-multi-module-b -DskipTests package` → 两模块 SUCCESS（约 34s）。
- 启动：JDK8 `java -jar` 双进程，healthz 分别返回 `{"module":"A","port":8091,"count":3}` / `{"module":"B","port":8092,"count":3}`。
- 接口：list / detail / create / pulse / delete 两侧全通（create 回 id=4、delete 回 deleted=1，测后已清理至各 3 行）。
- 日志形态：SQL 三段齐全，行首 PID 分别 78764(A) / 74264(B)，符合 `logLinePid` 两段式日期前缀正则。
- 提交：`f2ac4a49`（18 文件，模块+pom+README）与 `ff3baa6f`（库初始化登记 + 复验观察点）、`46d5c87d`（复现顺序）；插件仓修复 `ad4f5994b` + 读数回填 `4f7fa0e4f`。
- 推送与 CI：2026-11-26 已推双仓 master（Gitee/GitHub 均 OK，GitHub 走令牌通路），push run `45753951013` 结论 success。版本归属：`2026.11.26-beta.1` 不含修复（复现用）、`2026.11.26-beta.2` 含修复（验证用）。

