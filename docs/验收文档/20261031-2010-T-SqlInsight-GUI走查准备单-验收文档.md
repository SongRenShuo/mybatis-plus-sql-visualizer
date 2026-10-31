# T-SqlInsight GUI 走查准备单（20261031）

> 用途：给 GUI 走查代理在 **`mpsv-sql-insight` 工作树**执行验收前用的操作单。功能细节与判定标准**不在此重复**——逐项路径与预期见 [`20261024-1500-T-SqlInsight-合并master后验收-验收文档.md`](./20261024-1500-T-SqlInsight-合并master后验收-验收文档.md) §四（4.1~4.11）。本单只解决「环境别缺、顺序别乱、读数别丢」。

## 一、基线

- 分支 `t-sql-insight`，合并后 HEAD `bef08646`（含 dev 截至 `0459d682` 的全部内容）。
- 本次合并带入的 **dev 侧新能力**（此前只在 dev 沙盒部分验过，走查时顺带确认可用、不冲突）：
  - FR-0045 四项：禁用 API 检查（inspection）、SQL 优化留痕导出、外部模板渲染 hook（默认关）、MCP `NOT_A_SQL_DATASOURCE`
  - R45：结构化 EXPLAIN 通道**默认开**（原默认关，走查时不要把「默认就有建议条」当回归）
  - NEW 批：环境体检入口、换源重渲染、Community 执行入口预置灰、版本亮点通知（一次性）
  - D202：五个巨文件已拆（`SqlPreviewPanelExecution` 等），行为应零变化

## 二、环境准备（**先全绿再开始走查**，缺一项后面全是误报）

| # | 项 | 检查命令 / 做法 | 缺失后果 |
|---|---|---|---|
| 1 | `idea.key` 在沙盒 config 内 | `runIde` 前确认 `<沙盒>/config/idea.key` 存在；缺失从 `%APPDATA%\JetBrains\IntelliJIdea2026.2\idea.key` 复制 | 只弹 Manage Licenses，进不去 |
| 2 | license agent | `./gradlew runIde -Pmpvs.licenseAgent=C:/Users/SongRenShuo/.qwdn/patch/qwdn-afb.jar` | 弹试用横幅 |
| 3 | JDK | `JAVA_HOME=~/.jdks/corretto-17.0.14` | 构建期即失败 |
| 4 | 最近工程预置 | 沙盒 `config/options/recentProjects.xml` 预置 `mybatis-plus-sql-visualizer-demo` 路径 | 启动停在别的工程 |
| 5 | 数据源描述文件 | demo 仓无 `.idea/dataSources.xml` 时，从 `dev-support/test-demo/templates/dataSources.xml` 复制 | 插件认不到数据源，多项走查无从触发 |
| 6 | MySQL 库 | 需 `mp_demo`（灌 demo `db/schema.sql`，11 表）与 `mpj_demo` | 运行洞察类全废 |
| 7 | 构建/起沙盒 | `./gradlew buildPlugin` 或直接 `runIde`（走当前源码） | — |

## 三、走查顺序（先冒烟再逐项，失败即停并记录）

1. **冒烟四项**（约 10 分钟）：SQL 日志捕获与还原 → 分析 SQL → 参数场景 → 运行 SQL 分析入口。任一失败先记录，不继续。
2. **六项此前「未执行」的 GUI 验收**（本次重点，按验收文档 §4.4~§4.9 逐格勾）：
   - 4.4 离线 Trace 导入
   - 4.5 对象使用与影响
   - 4.6 本地 Schema 比较
   - 4.7 优化候选
   - 4.8 SELECT 结果比较
   - 4.9 写 SQL 影响面与事务关联
3. **合并带入项复看**（§4.10 / §4.11，W12 定案后的形态，别按旧稿找控件）：
   - 预览数据源指示行（纯文本三态 + 模块级限定词，**无自建换源控件**）
   - 换源走平台原生 `Data Sources` 菜单；红条/横幅动作是「打开 Database 工具窗口」
4. **dev 新能力顺带确认**（每项 1~2 分钟，只判「入口在、能用、不报错」，不细走）：
   - 设置页：禁用 API 子页、外部模板子页、环境体检按钮
   - EXPLAIN 结果面板：建议条**默认可见**（R45 转正）
   - 预览「更多」菜单：导出优化留痕
   - 升级后版本亮点通知（一次性，重启后不再弹）

## 四、记录模板（每项一行，走查时直接填）

```
| 功能 | 结果(通过/失败/部分) | 实际形态（看到什么） | 与预期的偏差 | 截图名 |
```

判定口径沿用验收文档：**表里「未执行」是事实不是待办占位，走完一格就在 §四 对应表勾选并写日期**。

## 五、红线

- **只看不写库**：走查不得对业务库执行写操作；SELECT 结果比较只在验收文档规定的只读 auto-commit 连接上进行。
- **不新增断言式改动**：走查若发现缺陷，记录路径 + 实际形态 + 预期，**不要顺手改代码**（合并态代码变了，走查读数就作废）。
- **环境受限项如实登记**：某项因缺环境跑不了，写「环境受限未验证」，禁止用推断代替。
