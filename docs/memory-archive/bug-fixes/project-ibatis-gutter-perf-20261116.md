---
name: project-ibatis-gutter-perf-20261116
description: T-IbatisGutterPerf（2026-11-16 三刀全部落地+双仓已推+CI 绿）：iBatis 索引化/三态兜底 + 正缓存收回（P2.1）+ MyBatis 同族收回（T-MapperGutterEvict）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4043a4bb-4569-4ef9-8e28-84e10cd22b20
---

用户 2026-11-16 真机 2026.3（demo 根工程）反馈 `ibatis2-demo/.../UserDaoImpl.java`：预览图标 + 跳转图标加载很慢，且「出现过一次后无故不显示」。计划与实施记录：`docs/开发计划/20261116-0925-T-IbatisGutterPerf-iBatis-DAO调用行图标首开慢与无故消失-开发计划.md`（§九 全量读数 + §9.6 P2.1）。

根因：`ui/nav/IbatisXmlStatementTargets.sqlMapListing` 仍是老形态（`FileTypeIndex` 全项目 2493 XML 逐个 PSI 解析筛 303 个 `<sqlMap>`；进程级单槽 + 5s TTL；三工程同开互踢），且 `runCatching{}.getOrDefault(emptyList())` 把 `IndexNotReadyException`/取消吞成空清单当正常结果缓存 → 慢趟换装摘图标且平台不自动重跑。MyBatis 侧 F1''' 已索引化，iBatis 是漏网面（LESSONS L74 来源）。

落地提交（master，双仓已推）：
- `b5ccf14c7`：`xml/ibatis/SqlMapNamespaceIndex`（键 `all` / `ns:` / `stmt:`，`<sqlMapConfig>` 内嵌子元素排除）+ `plugin.xml` 注册 + 内核/集成 7 例。
- `bcad37b84`：定位链改索引查询 + PSI 校验（TTL/清单代/单槽/计数缝全部退役）；provider 四段式；不再吞 PCE/IndexNotReady；Registry `sqlvis.gutter.ibatisIndexedResolve` 回退；`[gutter-perf] … source=index|scan` 日志；守卫 `IbatisGutterFailBranchGuardTest`（已登记元守卫）；顺带修 `ownerNamespace` 从 identifier 起爬超 6 跳取 null 的潜伏缺陷。
- **`8eca5211f`（P2.1，同日沙盒复验报障）**：`IbatisXmlStatementTargets.lookupStatementAnchors` 三态 `Found / Absent / Unknown`，provider 五段式——`Absent` 分支 `resolvedIbatisKeys.remove(key)` 并摘图标（改 namespace / 删语句后图标必须消失），`Unknown`（dumb/索引重建中/PSI 瞬时取不到）才保留缓存命中；判据是「查询是否成功执行」而不是「返回是否为空」。详见 [[pitfall-anti-jitter-cache-needs-eviction]]、LESSONS L76。
- **`c0bc9b043`（T-MapperGutterEvict，同日用户令「接着修」）**：MyBatis 两跳转 provider 同族收口——`MybatisXmlTargets.lookupInterfaceMethod` / `MapperXmlLocator.locateOrUnknown` 各出三态，两 provider 五段式（dumb 门前移 / Found 入缓存 / Unknown 保留 / Absent `remove` 并摘图标）；`catch IndexNotReadyException` 收敛在 resolver 内、provider 体零 catch；守卫加「`Absent` 必须 `remove(`」项，矩阵补 Unknown/Absent 四例。
- **`e4b3fdd27`（§9.9，同日沙盒第二轮报障）**：① `GutterWarmScheduler` 延迟派发体补 `runReadAction`（池线程无读动作 → 3 条 SEVERE `Read access ...`；单测 seam 同步执行会掩盖该类缺陷 → 扫描守卫 + LESSONS **L78**）；② iBatis XML 语句行箭头加调用点门槛（`IbatisJavaCallSites.lookupCallSite` 三态，Absent 摘箭头并收回缓存，回退 Registry `sqlvis.gutter.ibatisXmlArrowRequiresCaller`）。
- **`2eecbdcba`（§9.10，同日沙盒第三轮报障）**：`IbatisJavaCallSites.matches` 的「任意前缀尾段 `.id`」让 `User.xml` 的 `selectById` 跳到 `OdLogistics60233DaoImpl.java`——改为只认「裸 `id`」与「`本文件 namespace.` + `id`」精确形态（点击 / Ctrl+Click / 存在性门槛三处同修）→ LESSONS **L79**（容错尾缀匹配在多命名空间语料里必然误命中）。
- **`501db7d89`（T-MechanismSweep，同日用户令「下一批待办，现在做」）**：P4 横向审计落地——`ui/ref/MybatisXmlTargets.findMapperFileByNamespace`（XML 属性 reference 热路径）与 `psi/XmlCrossNsFragmentResolver.mapperFragments` 均改走 `MapperNamespaceIndex.getMapperXmlFiles`，两处「FileTypeIndex 全项目扫 + 单槽 TTL」状态机退役（净 **-266/+66 行**；跨命名空间片段顺带消掉 5s 旧值窗口）；删 iBatis Registry 回退通道（`legacySqlMapFiles`/`indexedResolveEnabledProvider`/plugin.xml registryKey）与 `[gutter-perf]` 恒定的 `source=` 字段；新增 `MapperFileLookupIndexTest` 源码扫描防回退（含老形态自证）。**保留** `psi/jpa/HbmXmlEntityResolver`：复查发现它不 PSI 解析非 hbm XML（先按 `.hbm.xml` 后缀过滤再建 PSI），索引化收益 < 成本。
- `70387d3cf` / `cfbf47168` / `c261b4fd2` / `39203c4b5` / `7aa154062` / `46b88b547`：CHANGELOG、计划 §九+§9.6~§9.8、索引行、架构文档 02、LESSONS L74/L76。

读数：定向 12 类 **109/0/0/0**；EP/本地化守卫 32/0；verifyPlugin 三腿 BUILD SUCCESSFUL；P2.1 后定向 9 类 **95/0/0/0**、MyBatis 同族后 8 类 **93/0/0/0**、T-MechanismSweep 后 **25 类 264/0/0/0**、§9.9 后 **49 类 408/0/0/0**、§9.10 后 **14 类 160/0/0/0**；反向四次分别恰 2/2/1/2/1 红→恢复 0 红。CI：`43790850727` / `43792204927` / `43799874396` / `43803678741` / `43807238480` / `43808724126` 六轮均 **8/8 全绿**（末轮 head `ba8b53dc5`，即五刀全推完）。**用户真机复验已通过**（2026-11-16：「复验第一个正常」「现在正常了」）。

沙盒（runIde IU-2024.3.7.1）实测：新索引在建工程时一次性建（`Indices to be built: cn.song.mybatisplus.sqlMapNs(v = 1)`）；`UserDaoImpl` 20 个调用点全部 `source=index`、耗时 0ms×17 / 4 / 5 / 22ms；7 次预览 `len=84/85` 无一次 `len=0`；0 ERROR、0 SlowOperations。

**Why:** 「机制替换只修报障那条链」会漏掉同形态的平行实现（本次是 F1''' 次日复报）；「抗抖动缓存只进不出」会把瞬时失败兜底变成永久错误态（同日二次报障）。
**How to apply:** 下一批候选（都已不属本任务）：① `psi/jpa/HbmXmlEntityResolver` 若日后要索引化，需新开一套 `.hbm.xml` 实体索引（先量 JPA 预览冷路径的真实耗时再决定）；② `ui/highlight/XmlCompletionSymbols` 的 FileTypeIndex（另有 dumb 闸，暂留）；③ 沙盒/真机复验：iBatis 改 namespace 后图标应立即消失（`state=absent`），MyBatis 删接口方法/改 `<mapper namespace>` 同样立即消失；跨命名空间 include 片段改动后预览应立即生效。用户复验前须**先关沙盒再 runIde**（沙盒自更新只走 update 通道，不拉本地源码）。相关：[[project-gutter-first-open-delay-20261114]]、[[pitfall-slowpass-listing-starvation-and-headscan]]