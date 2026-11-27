# v2026.11.27 发行说明（Mapper 方法行预览与自研 Wrapper 支持及性能收口）

- 版本：`2026.11.27`（正式版，Marketplace 与 Gitee 双通道同步）
- 发布日期：2026-11-27
- 提交范围：上一正式版 `2026.11.25`（`452692060`）之后到本版发行提交的全部用户可见增量，含三轮内测 `2026.11.26-beta.1` / `beta.2` / `2026.11.27-beta.1` 的全部内容（三段内测段由本节取代，内测发行版保留共存）。按最终能力面合并为 11 项。其余 mcprush 收录、内容营销、盘点、验收册、记忆精简、CI 与测试类提交用户不可见。逐条根因见 `CHANGELOG.md` 的 `[2026.11.27]` 段，三轮内测的逐条细目见 `docs/发行说明/` 三篇内测说明留档
- 兼容 IDE：2024.3（243）/ 2025.3（253）/ 2026.2（262）/ 2026.3（263）

> 三轮内测的主要内容都在这一版里，从 `2026.11.25` 或更早版本升级直接看下面 11 项即可。

## 这一版有什么

按新功能 → 修复 → 优化排序。

### 新功能

1. **Mapper 接口方法行也能预览 SQL（T-MapperMethodSqlPreview，`12f3232b8` + `3d6bb9b57`）**：方法名行新增预览图标，点击弹出该方法对应语句的动态 SQL 预览；此外光标停在方法名/参数/返回类型上按 `Alt+Shift+Q`（右键菜单同一入口）也能直接预览，此前这条路会落到「未找到可转换的 Wrapper」。两条入口走与 XML 语句行点击完全一致的装配与渲染链（参数表、`<include>`、`${}` 注入同一套）；同名多方言（`databaseId`）变体弹选择器；没有 XML 语句的方法（含 MP 内置 CRUD）仍回落原有链，不出错源提示。该方法行因此有两枚图标——左预览、右既有跳转箭头；开启同类插件共存让位时只让跳转、预览保留（三家竞品 Java 侧都只有跳转箭头，无预览能力），只装 MyBatisCodeHelperPro 时开让位该行不再为空。不加新设置项，随「MyBatis mapper XML」组开关与总闸。

2. **自研 Wrapper 子类（MPJ 套壳封装）现在能预览了（T-ServiceJoinWrapperPreview，D256，`736b6a272`）**：`CustomJoinWrapper extends AbstractLambdaJoinBaseWrapper<…>` 这类把 MPJ 整套复制改名、或在自己框架里再包一层的 Wrapper，此前插件只认官方类名——声明行没有 gutter 图标，Service 封装里的 `repository.selectJoinList(Dto.class, wrapper)` 点了没反应。现在沿类型父链识别（FQN 前缀 / 接口同名组合 / 依赖未索引时按链方法兜底），声明行与 `selectJoinList` / `selectJoinOne` / `selectJoinMap` / `selectJoinCount` 调用行都能直接预览。

### 修复

3. **方法行两枚图标固定为「预览在左、跳转在右」（T-MapperPreviewOrder，`031fb9089` + `6c1dc40a6`）**：第三次报障。此前预览与跳转在同一个 provider 里产出，首次渲染偶发「预览在右、过一会跳到左边」，与 XML 语句行的稳定顺序不一致。前两版修法（同 provider 双 marker、锚点 offset 小一档）都只让收集顺序偏向自己，真机多轮 daemon 复用下仍会翻转。现在改用平台的分列机制：预览固定 `Alignment.LEFT`、跳转保持 `CENTER`，`EditorGutterComponentImpl.processIconsRowForY` 按 alignment 分列排布（先排完所有 LEFT，再把 CENTER 整体放其右侧），左右序与 marker 收集顺序、daemon 增量复用、provider 注册顺序全部无关。另补 `MapperPreviewDaemonOrderTest` 钉住该行为。

4. **Mapper 接口方法的 Wrapper 参数不再多出一枚预览图标（T-MapperPreviewDedupAndAnnotationShortcut，`0263bf788`）**：`List<Order> selectByXxx(@Param("ew") Wrapper<Order> ew)` 这种签名里，参数名上曾经也出 Wrapper 预览图标，与方法行的 SQL 预览图标同款同行，看着像「图标重复」。参数在无方法体的接口/抽象方法里只是 `${ew.customSqlSegment}` 占位符、不是可预览的链起点，现在不标；实现类与接口 `default` 方法（有 body）的参数照旧。

5. **注解 SQL 的快捷键按方法定位，不再弹全文件列表（T-MapperPreviewDedupAndAnnotationShortcut，`0263bf788`）**：光标停在 `int createDemoTable();` 这类方法声明行（注解在上一行）时按 `Alt+Shift+Q`，此前会列出整个文件的注解 SQL 让人挑；现在直接预览该方法自己的 `@Select`/`@Update` 等注解 SQL。光标在类体空白处时仍保留文件级列表兜底。

6. **两个应用同时开着时，先启动的那个不再丢 SQL（T-EpAttachBeforeCapture，`ad4f5994b`）**：控制台捕获通道原先只在控制台创建那一刻装配，先启动应用、之后才点「开始捕获」时，这个控制台拿不到 EP 通道；后启动应用的控制台一旦捕获到 SQL，会把进程级的接管标志置位，前一个控制台的兜底解析也随之中止——表现为「两个应用单独跑都能看到 SQL，同时跑只剩后启动的那个」。现在捕获通道对任何控制台都先装配、捕获开启后自动生效，两边各收各的。

7. **预览链两处 UI 线程卡顿与一处回填丢失（T-PreviewFallbackEdtSlowOps / T-PerfSweep34 券01、券05）**：① `Alt+Shift+Q` 落在 Mapper 接口方法、且 Wrapper 链没有命中时，兜底要按 namespace 找同名 XML 语句，这步原先跑在 UI 线程的预览回调里——`idea.log` 每次留两条 `SEVERE Slow operations ... Plugin to blame: MyBatis SQL Visualizer`，索引要追平时界面还会卡一下；现在锚点解析放回后台线程的读动作里完成（`c5685aee8`）。② 同一处兜底链还有第二条分支漏改——光标停在 `deleteAllInBatch()` 这类 Repository 声明行时「按接口泛型找实体」会去查文件名索引并读工程的 Hibernate/Spring 配置文件，这条也在 UI 线程上；现在同样移到后台（`3e434734f`）。③ 预览里遇到未解析的 `<include refid>` 时，弹框里点「从文件选择」、选好 XML 后文本框没有任何变化——回填动作被排在一个「等模态框关掉才执行」的队列上，而那时框已经关了；现在选完即回填（`3e52b138f`）。

8. **从日志面板点「设置」不再停在 SQL 日志页中间（T-SqlLogSettingsJumpAnchor，`52322da26`）**：设置页按页面缓存内容与滚动位置，齿轮入口只切页不做定位，于是 SQL 日志这种多分组长页会沿用上次停的位置。现在只切页时落点复位到页顶；搜索与引导链接的带目标定位（滚动 + 高亮）不受影响。

9. **join 消费族加了守卫（D256，`84ae88a2d` + `0c15797e5`，随 `736b6a272` 合流进入发行树）**：wrapper 未识别时不再输出 `WHERE id IN (?, ...)` 这类凭空条件的错误 SQL。两笔原始提交写在 11-23，但当时只在 D256 分支上，直到本版 D256 合流才进 master；上个正式版的产物不含它。

### 优化

10. **切换「同类插件共存让位」不再拖慢整个项目（T-PerfSweep34 券02，`56162eaf6`）**：这个开关只影响行号旁的图标，但原先切换后会让所有已打开项目重新做一遍全量代码分析，大工程下要等一会儿才恢复。现在只重算当前打开着的那些文件。图标刷新效果不变。

11. **发现 SQL 自动打开日志窗口不再顶掉你正在用的面板（T-AutoShowYield，`12b84ae03`）**：新 UI 同一侧栏同一时刻只显示一块面板，此前自动展开会把正在使用的 API 调试 / HTTP Client / Database 顶掉（表现为「我在看接口响应，日志窗抢上来了」）。现在同一侧栏已有其他工具窗口在显示时不展开，SQL 照常捕获、切回面板即可看到；设置页 SQL 日志组新增子开关「自动展开时让位给正在使用的面板」（默认开，父开关关闭时禁用），关掉即恢复每次必开。

**另有两项内部提速**：gutter 图标的 4 个 provider 原先对每个语法元素都先读一次插件设置再判断「这个元素是不是我的目标」，现在先做那个几乎零成本的判断（`a33d99a4a`）；补全上下文预热加了每项目次数上限，冷启动后连续打开很多 mapper 文件时不再每个文件都触发一次较重的实体解析预热（`0b581d5b1`）。纯内部重排与节流，无行为变化。

## 验证读数

- 三轮内测全部走 CI release 全量门禁：`2026.11.26-beta.1` run 45578089046、`2026.11.26-beta.2` run 45748639475、`2026.11.27-beta.1` run 45922059780，均 conclusion=success（beta.1 首轮 agenttest 与 11.27-beta.1 首轮 test(1) 各有一次瞬态红，rerun 后全绿；后者为 LESSONS L90 在册 flaky）。
- T-PerfSweep34 券01/券03 已做真机验收（`e27f4324d`）；券02 补了 `GutterYieldRefreshScopeGuardTest`（方法体切片三条判据 + 自证用例），反向验证过——退回无参 `restart()` 守卫红、恢复即绿。
- T-MapperPreviewOrder 有 `MapperPreviewDaemonOrderTest` 钉住 alignment 分列行为。
- 本版相对 `2026.11.27-beta.1` 为**零代码增量**（`git diff --stat 2026.11.27-beta.1..HEAD -- src/main src/test` 为空），仅版本号与文案变更；beta.1 的 CI release 已在本代码状态上 15/15 全绿。
- 产物级文案与包自检由 CI 在 `:buildPlugin` 之后对真正发布的那份 zip 跑（`check-change-notes.py` 与 `inspect-plugin-zip.py`）。
- tag 前源码级判据 `check-change-notes.py --version 2026.11.27` + `--self-test` 绿。

## 边界与说明

- `update/preview.update.json` 指向本版；`update/update.json` 由用户本人维护
- 本期无新增能力类别，`plugin.xml` 描述与市场页功能矩阵不动（Mapper 方法行预览与自研 Wrapper 属既有预览能力的扩展面，不算新类别）
- 本期无新增已知问题
- 本版为 `2026.11.27` 正式版，三轮内测段已按能力面合入本节，内测 changeNotes 块与 CHANGELOG 段同批删除，逐条细目留在 `docs/发行说明/` 三篇内测说明

构建记录：https://github.com/SongRenShuo/mybatis-plus-sql-visualizer/actions/runs/45927546245
