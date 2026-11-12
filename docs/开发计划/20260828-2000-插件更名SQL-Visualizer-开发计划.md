# 插件更名为 SQL Visualizer 开发计划

> **创建日期**：2026-08-28
> **类型**：品牌更名（显示层变更，零功能变更）
> **前置**：无强依赖（可与 M36.5 并行，建议 M36.5 合入后再发版）
> **优先级**：高
> **状态**：✅ 已交付（`plugin.xml` name=SQL Visualizer 在库；2026-11-13 对账回填——下文清单为立项时计划项，实际以代码为准）

---

## 一、需求概述

将插件显示名从 **MyBatis-Plus SQL Visualizer** 更名为 **SQL Visualizer**，覆盖所有用户可见的代码文案与面向用户的文档。

**硬性约束**：

| 约束 | 说明 |
|------|------|
| Marketplace 插件 ID 不可变 | `<id>cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer</id>` 保持原样，这是升级链路的唯一身份标识 |
| 已安装用户必须正常升级 | IDE 判定"同一插件的新版本"依据的是插件 ID（非显示名），ID 不变则升级路径完全不受影响 |

**兼容性结论（需求分析核心）**：

1. **JetBrains Marketplace 升级**：插件身份由 `<id>` 决定。Marketplace 页 slug（`/plugin/33633-mybatis-plus-sql-visualizer`）随首发固化，**改名后 URL 不变、页面仍可达**。上传新版本后 listing 显示名同步为新的 `<name>`（发布时在后台确认即可）。
2. **Gitee 自更新链路**（`StartupUpdateCheck` / `PlatformPluginInstaller`）：按 `PLUGIN_ID` 匹配 manifest、按 `INSTALL_DIR_NAME`（`mybatis-plus-sql-visualizer`，zip 顶层短名）定位安装目录——两者都不含显示名，不受影响。
3. **Install from Disk**：zip 顶层目录名由 `rootProject.name` 决定，与 `<name>` 无关。
4. **用户配置/历史记录**：以 ID + 配置 key 存储，与显示名无关，升级后全部保留。

---

## 二、现状盘点（引用面调查结果）

### 2.1 需要修改的（显示名硬引用）

| 位置 | 引用数 | 内容 |
|------|--------|------|
| `src/main/resources/META-INF/plugin.xml:16` | 1 | `<name>MyBatis-Plus SQL Visualizer</name>`（核心改名点） |
| `plugin.xml:24` | 1 | 英文描述首句 `MyBatis-Plus SQL Visualizer is a debugging tool...` |
| `messages/MpSqlVisualizerBundle.properties` | 16 | `plugin.name`、`configurable.settings.displayName`、`action.notFound.title`、`reverse.dialog.title`、`dialog.preview.title`、8 个 gutter tooltip、`log.toolwindow.title`、`notification.group.updates`、`action.cn.song...log.text` |
| `messages/MpSqlVisualizerBundle_zh.properties` | 16 | 上述 key 的中文版（显示名保持英文 `SQL Visualizer`，与现状 zh 版保持英文一致） |
| `reverse/JavaCodeGenerator.kt:741` | 1 | 生成代码头部注释 `// SQL → MyBatis-Plus xxx 反向生成（MyBatis-Plus SQL Visualizer）` |
| `config/PluginSettingsConfigurable.kt:16`、`config/ProjectPluginSettingsConfigurable.kt:20` | 2 | 仅 KDoc 注释 |
| `test/.../PluginDistributionSelfCheckTest.kt:38` | 1 | 断言 `<name>MyBatis-Plus SQL Visualizer</name>` |
| `test/.../JavaCodeGeneratorTest.kt` | 2 | 生成代码注释黄金用例 |
| `README.md:1,137` | 2 | 标题 + Settings 路径 |
| `docs/发行说明/marketplace-custom-page.html:334,350` | 2 | Settings 路径 |
| `CHANGELOG.md` | — | 新增更名条目 |

### 2.2 明确不修改的（改动会破坏升级/构建）

| 项 | 原因 |
|----|------|
| `<id>`（plugin.xml + `UpdateSource.PLUGIN_ID`） | Marketplace 与自更新链路身份，改名即断升级 |
| 包名 `cn.song.mybatisplus.visualizer`、group 坐标 | 内部标识，与显示名无关；改动风险巨大且无用户可见收益 |
| `MpSqlVisualizerBundle` 类名与文件名、bundle key 名 | 内部资源标识，用户不可见 |
| `rootProject.name = "mybatis-plus-sql-visualizer"` | 决定 zip 顶层短名，`PlatformPluginInstaller.INSTALL_DIR_NAME` 硬依赖它定位安装目录 |
| 仓库地址 / Gitee URL / Marketplace slug URL | ID 派生物，改名无效 |
| `pluginIcon.svg` 路径 | 约定路径，与名称无关 |
| `<vendor>`、`UPDATE_MANIFEST` 链接 | 不含显示名 |

### 2.3 历史文档策略

`docs/开发计划/*`、`docs/发行说明/*`、`docs/memory-archive/*`、覆盖矩阵等历史文档中的旧名（约 40+ 处文件）是**当时事实的历史记录，不改**——统一改写历史反而让归档失真。仅面向用户/未来读者的文档（README、Marketplace 页、发布流程说明、CHANGELOG）纳入本次修改，其中旧名以"原 MyBatis-Plus SQL Visualizer"方式说明更名事实。

---

## 三、技术方案

### 3.1 Phase 1：plugin.xml 核心改名

1. `<name>MyBatis-Plus SQL Visualizer</name>` → `<name>SQL Visualizer</name>`
2. 英文描述首句改为 `SQL Visualizer is a debugging tool for MyBatis-Plus, MyBatis-Plus-Join, ...`（仍满足 Marketplace 约束：拉丁字符开头、≥40 字符）
3. 中文描述段不动（描述的是框架而非插件名）
4. 描述内的 Marketplace slug 链接、Gitee Issues 链接不动
5. 顺带修正 `plugin.xml:83` 注释中 Settings 路径描述

### 3.2 Phase 2：i18n bundle 文案

对 `MpSqlVisualizerBundle.properties` 与 `MpSqlVisualizerBundle_zh.properties` 全量替换：

```
MyBatis-Plus SQL Visualizer  →  SQL Visualizer
```

覆盖 16 处/文件（`plugin.name`、设置页 displayName、弹窗标题、gutter tooltip、通知组名、toolwindow 标题、action text）。中英两份显示名统一为英文 `SQL Visualizer`（品牌名不做翻译，与现状策略一致）。

注意点：`reverse.dialog.title` / `dialog.preview.title` 是 `插件名 - 副标题` 拼接格式，替换后为 `SQL Visualizer - MP Java Code`，长度变短，无需布局调整。

### 3.3 Phase 3：代码内硬编码与测试同步

1. `JavaCodeGenerator.kt:741`：生成代码头注释改为 `（SQL Visualizer）`
2. 同步更新 `JavaCodeGeneratorTest` 中两处期望注释（黄金用例断言）
3. `PluginDistributionSelfCheckTest.kt:38`：断言改为 `<name>SQL Visualizer</name>`（该测试本身是插件分发自检，属改名联动的正确修改点）
4. `PluginSettingsConfigurable.kt` / `ProjectPluginSettingsConfigurable.kt` KDoc 注释更新（无行为影响）

### 3.4 Phase 4：面向用户文档

1. **README.md**：标题改为 `# SQL Visualizer`，正文首段补一句更名说明（原 MyBatis-Plus SQL Visualizer，仍全面支持 MyBatis-Plus/MPJ/JPA），`Settings → Tools` 路径更新
2. **docs/发行说明/marketplace-custom-page.html**：两处 Settings 路径更新
3. **CHANGELOG.md**：`[Unreleased]` 增加更名条目；`build.gradle.kts patchPluginXml.changeNotes` 在**发版时**同步加入（遵循现有"单一来源、发版时同步修改"规则，本计划阶段不动）
4. **docs/发行说明/Marketplace发布流程-说明文档.md**：若有旧名步骤描述则顺带更新，并新增一条注意事项——"上传新版后在 Marketplace 后台确认 listing 显示名已同步为 SQL Visualizer"

### 3.5 Phase 5：验证

1. **引用清零检查**（脚本化）：

   ```bash
   # 期望：仅 plugin.xml 描述中框架名、历史文档命中；src + README + marketplace 页应清零
   grep -rn "MyBatis-Plus SQL Visualizer" src/main README.md docs/发行说明/marketplace-custom-page.html
   ```

2. **构建验证**：`gradlew buildPlugin`；解压 build/distributions zip 确认顶层目录仍为 `mybatis-plus-sql-visualizer/`（自更新兼容关键项）
3. **verifyPlugin**：`gradlew verifyPlugin` 确认改名后结构校验通过
4. **测试**：全量 `testShard1~4` 通过（重点 `PluginDistributionSelfCheckTest`、`JavaCodeGeneratorTest`、Settings 相关）
5. **运行时抽查（runIde）**：
   - Settings → Tools 树节点显示 SQL Visualizer
   - gutter tooltip / 弹窗标题 / SQL 日志 toolwindow 标题 / 更新通知组显示 SQL Visualizer
   - 中英语言切换下均正确
6. **升级回归（发布后）**：
   - 用旧版本（2026.8.21 已装环境）触发 Gitee manifest 自更新，确认提示、下载、Install from Disk 目录定位、旧配置保留全部正常
   - Marketplace 后台确认 listing 名称与 slug URL

---

## 四、发版与升级路径（给用户的确定性）

| 环节 | 行为 | 用户影响 |
|------|------|----------|
| Marketplace 在线更新 | ID 不变 → 识别为同一插件新版本 | 正常出现更新提示，一键升级，配置/历史全保留 |
| Marketplace listing | 上传新版后显示名同步为 SQL Visualizer，slug URL 不变 | 旧收藏链接/搜索不受影响（旧名搜索过渡期由 Marketplace 跳转） |
| Gitee 自更新 | PLUGIN_ID / INSTALL_DIR_NAME 不变 | 自更新链路无感知 |
| IDE 已安装目录 | 目录名由 zip 顶层短名决定，不变 | 无残留旧目录、无重复安装 |

## 五、风险与回滚

| 风险 | 概率 | 缓解 |
|------|------|------|
| Marketplace 后台 listing 未同步（仍显示旧名） | 低 | 发布 checklist 中显式确认项 |
| 误改 `<id>` / `rootProject.name` 导致升级断链 | 低 | 3.2.2 节"不改清单"写入验收检查；zip 顶层名验证步骤兜底 |
| 黄金用例注释断言遗漏导致测试红 | 低 | Phase 3 已枚举全部 3 处（生成器 1 + 测试 2） |
| 历史截图（README/images、Marketplace 截图）UI 内仍显示旧名 | 必然 | 截图重制工作量大，列为独立后续任务；发布前仅抽查核心 2~3 张 |

回滚：全部为文本替换，`git revert` 单次提交即可。

## 六、验收清单

- [ ] `plugin.xml` `<name>` 与描述首句已更新，`<id>` 未动
- [ ] 两份 bundle 各 16 处文案替换完成，`grep` 清零验证通过
- [ ] `JavaCodeGenerator` 注释 + 2 处测试黄金用例同步
- [ ] `PluginDistributionSelfCheckTest` 断言更新并通过
- [ ] README / marketplace-custom-page / CHANGELOG 更新
- [ ] zip 顶层目录名仍为 `mybatis-plus-sql-visualizer`
- [ ] buildPlugin / verifyPlugin / 全量测试通过
- [ ] runIde 抽查 5 项显示名（设置树、tooltip、弹窗、toolwindow、通知）
- [ ] 发布后旧版本自更新回归 + Marketplace listing 名称确认
