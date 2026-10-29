---
name: project-embedded-identify-investigation
description: @Embedded 报障排查定案（20261026）：master 识别链四路取证全为正确，根因未定需 W0 取证 + W1 诊断日志，禁止盲修
metadata:
  type: project
---

外部插件用户报「@Embedded 注解无法正常识别」，用户在主分支默认沙盒复现（2026-10-26 上午）。当日远端排查定案：**master（`048d4e03`）识别链实测无回归**，四路证据：①沙盒 idea.log 三次预览（JPQL len=103/派生 197/自定义 HQL 293）与生产管线探针渲染（99/157/253）差额=持久化 `formatSql: true` 的换行；②同次预览落盘 `demo/.idea/sql-visualizer/exec/mpsql-exec.sql` 含 `booking.created_time`（对话框与 exec 共用同一 `result.sql`，`prewarmExecTempFile`）；③以 demo 仓 jpa-hibernate-demo 逐字形态（javax 短名导入+Lombok+UpdateInfo 无内层 @Column）走真实链路探针全绿（探针测试已删，源码在计划附录 A）；④既有 @Embedded 定向测试全绿。识别代码自 `6919d8ef`（10-13）起 master 零改动；[[project-sql-insight-campaign]]/[[project-magpie-research-gems]] 未合 master 不在嫌疑。

**三版本对账（20261026 用户指示「用 16 版本对比」）**：`2026.10.16` tag（`92232d23`）/ 10-19 tip（`5846ac3c`，版本串恰为 2026.10.16，全史无 2026.10.19 版本串）/ HEAD 三方探针渲染**逐字节一致**（复现 HQL len=253、派生 157、JPQL 同文），且窗口内复现链（gutter/ArgResolver/HqlConverter/JpaMetadataReader/previewCustomDaoHql/resolveHqlTable）零行为 diff——**该复现手段下「问题」三版同样（不）存在，SQL 与真库 schema 逐列相符**。用户所观察异常必在：①参数面板/停靠预览/SQL 转 Java/执行链等非 SQL 面（执行链正是窗口大改区）；②外部用户工程特有形态（H1~H5）；③首次点击 IndexNotReadyException 失败（IDE 启动 24s 内点预览必报「未找到可转换的内容」，三版同源）。**拿不到「错误输出长什么样」前禁止预防性改动。**

**根因指认与修复（20261026 用户补充「jar 里的不正常」→ T-EmbeddedHostCascade，提交 `b3970e1f`）**：前序=W1 诊断日志（`8267394d`，`[T-EmbeddedIdentify]` 前缀：命名档来源/嵌入列清单/降级 WARN/resolveHqlTable 三出口）+ W0 复现方法 `CommonDaoHibernateImpl#findBookingByValueBean`（源码形态，W1 日志读数全对）。取证证明 HEAD 对二进制 JAR 实体本就正确（W3 读数存档 + 重建真实模型 JAR 的 previewCustomDaoHql 全链 E2E：真表名+三列 component-path 前缀逐项对）；机械成立的缺口＝实体在 lib/JAR 模块且**该模块有自己的 resources 但无命名配置**时，6919d8ef 的模块收范围必 miss（无 resources 目录才回落工程级），而策略配在宿主 App 模块。修复＝`resolveDetailed(project, anchor, hostAnchor)` 宿主级联（probe-hit-host 新标签）+ `JpaEntityResolver.resolve(entityClass, hostAnchor)` 重载（缓存键加宿主维度）+ resolveHqlTable 自动传上下文；两档 miss 不回工程级（防外溢契约不变，`test同模块不外溢` 钉住）。验证：级联 4/0/0/0 + JAR 全链 2/0/0/0 + 嵌入簇 9 类 59/0/0/0；反向短路恰 1 红。🆕坑＝Kotlin 加默认参数改 JVM 签名后陈旧测试字节码 NoSuchMethodError（Gradle 内容哈希不触发重编），删陈旧 class 即消。**遗留**：用户沙盒的 jar 异常未在任何本轮构建上复现——用户重建后重测 jar 语料（`WmsInventoryJarQueryService.findAvailableJarInventory` gutter），仍异常则贴 `[T-EmbeddedIdentify]` 行归因；CRUD/Specification/Criteria 等单参 resolve 调用未接级联，同症状再按同款扩展。**✅ 当日收口**：demo 补第三档语料（lib 自带 resources 无命名配置，demo 仓 `1b37378`）后用户实测两条读数全对（表名+三列前缀+三层嵌套+参数回填），源码与 JAR 形态双双闭环；修复已随 `ceac4186` 进 2026.10.26 正式版。

**§七 复盘（提交 `0c1e8043`）**：既有测试没接住的四层缺口——①判定层（6919d8ef 只测纯函数，「模块判不出下一步去哪」无用例，且当时回落工程级仅在实体模块无 resources 时可达）；②锚点形态（既有命名测试锚点全在 light fixture 内存源码，构造不出真实文件布局）；③桶位错位（JAR 形态 W3 取证是 Heavy 桶仅全量跑，且断言「注解读得到」非「命名档与列名」——取证绿冒充行为绿）；④锚点不一致（根因级：同链路找实体类用宿主 scope、判命名用实体模块，无人对账）。补测：HostCascadeTest 4→6 例（+无宿主锚点不级联不外溢、+缓存按宿主隔离〔反向＝摘 JpaCacheKey 宿主维度恰 1 红〕）；JpaJarEmbeddedPreviewChainTest 2→3 例（+源码形态 lib 模块 workspace 复刻端到端）。防复发三手段：查找范围类改动逐分支造真实布局夹具/锚点一致性用例/Heavy 桶能力进定向清单须标桶位。嵌入簇 9 类 62/0/0/0。

**取证可复用手法**：默认 runIde 沙盒=`.intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-*/`（log/idea.log + config/options + plugins/）；预览对话框 len=紧凑 SQL+formatSql 换行，与 exec 文件同源可互证；持久化设置先查 `config/options/*.xml`（[[pitfall-persisted-experimental-setting-changes-path]]）。

**Why**: 避免下个会话从「找 master 回归」重头查一遍（本轮已证伪）；也防止看到「识别失效」就直接动展平代码。
**How to apply**: 该报障续查时先看本档与计划 §六实施记录是否已有 W0 结论；没有就先补 W0/W1。
