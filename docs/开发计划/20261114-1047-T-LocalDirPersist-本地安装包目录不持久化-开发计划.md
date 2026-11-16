# T-LocalDirPersist：本地安装包目录重启即丢（不持久化）— 开发计划

- 日期：2026-11-14 10:47
- 任务编号：T-LocalDirPersist
- 来源：用户 2026-11-14 反馈「本地安装路径好像没有持久化，每次都需要调」
- 性质：缺陷修复（持久化**时机**缺口，非读写链路损坏）
- 状态：**计划（未实施，生产代码零改动）**

---

## 一、判定：链路没坏，丢的是保存时机

**「写坏 / 读不回」不存在**。全仓 `localUpdateDir` 的赋值点只有一个——设置面板 `apply()`（`config/PluginSettingsPanel.kt:1534`），且仅有点 **OK / Apply** 才会走到。而这条功能的主流程「选目录 → 检测本地包 → 安装」**全程直接用输入框现值，不经过任何保存点**，也没有任何提示要按确定。用户走完流程（大概率不点 OK），重启即空——与「每次都需要调」逐字吻合。

| 面 | 事实 | 证据 |
|---|---|---|
| 唯一赋值点 | `s.localUpdateDir = updateSection.normalizedDirForPersist()` 只在 apply 里 | `grep "localUpdateDir =" src/main` 仅 1 处（PluginSettingsPanel.kt:1534） |
| 检测/安装不落盘 | 「检测本地包」「打开」「热更新安装」全部读 `localDirField.text` 现值 | `UpdateSection.kt` runCheck(LOCAL)（:702 取 field 文本）、openLocalDir（:507）、安装链 `installFromLocal(pkg)` |
| XML 读写链完好 | `@State` 挂 `mp-sql-visualizer.xml`；`roamingType=DISABLED` 只是不云同步，本地照写 | `PluginSettings.kt:23-25`、`:799` getState、`:802` loadState 换引用 |
| apply→state 已测 | `testLocalUpdateDirBinding` 断言 apply 后 state 非空、reset 回读一致 | `PluginSettingsPanelBindingTest` |
| 机制真机可用 | 用户其他设置（语言等）长期持久化正常 | 历史反馈面无「全部设置重启即丢」 |

**排除项**：无启动清零路径（startup 只读不写）；其余 5 处 `loadState` 调用点全部基于 `state.copy(...)`（携带现值，不会抹掉本字段）；并行合流没有冲掉接线（master `7d7cbef8e` 实测接线三处俱全）。

## 二、次因：热更新会话里 OK 被静默吞（dogfooding 正好撞上）

在册坑 `pitfall-hot-update-settings-apply-cnfe`：热更新后，**已打开的**设置对话框属于旧 classloader，点确定时 apply 链抛 CNFE 被平台吞掉——**该会话里所有设置都不落盘**，完全退出 IDE 才恢复。本地安装包功能本身就是热更新入口（用户装完包继续在同一会话里调设置），正好踩中。此项已在册待办「热更新后探测设置页可用性，否则提示重启」，**本计划不动它**（见 D-3），只修主因。

## 三、方案：写穿（照语言下拉先例）

本仓已有成熟先例：语言下拉**选中即写穿**（`PluginSettingsPanel.kt:771-777`——不依赖 OK→apply 链路，用户从没抱怨语言丢）。本字段照抄形态：

### 3.1 写穿时机（两处，均只在**解析为合法目录**时）

| 编号 | 时机 | 说明 |
|---|---|---|
| W1 | `browseLocalDir()` 选完目录、立即校验返回 Ok | 浏览是主入口，选完即存 |
| W2 | 输入防抖校验（`validateLocalDirAsync` 回渲染处）结果为 Ok | 手动输入/粘贴也存；错误态**不写穿**（保持现状语义：错误原文只在用户显式点 OK 时经 apply 落盘，且状态行报错不受影响） |

### 3.2 写穿实现

```kotlin
// UpdateSection 内：
private fun writeThroughIfValid() {
    val check = LocalUpdateDir.parseWithPlatformPluginsDir(localDirField.text)
    if (check !is LocalUpdateDir.DirCheck.Ok) return
    val normalized = check.dir.toString()
    if (normalized == settings.state.localUpdateDir) return          // 幂等：同值不触发持久化周期
    settings.loadState(settings.state.copy(localUpdateDir = normalized))  // 换引用强制落盘（:1538 注释的实证纪律）
    localDirField.text = normalized                                  // 回写归一值，isModified 归零（照 apply 的回写）
}
```

- 用 `loadState(state.copy(...))` **换引用**，不用原地改——`apply()` 处 :1538 的注释明确实证过「原地改可能不被 XmlSerializer 检测到」。
- 写穿后 `isModified()` 归零（field 已同步为归一值）⇒ 平台 Apply 按钮变灰属正常（与语言写穿的 `languagePending` 常亮语义相反，见 D-1 理由）。

### 3.3 与 OK/Apply 的关系

`apply()` 原样保留（含「错误态存 trim 原文」分支——用户填了错误路径后显式点 OK 仍会落盘原文，重开页面状态行照报原因）。写穿只是给「合法目录」加了一条不依赖 OK 的落盘通道；两条链幂等（同值不重复写）。

---

## 四、决策点（未拍板按「推荐」实施）

| 编号 | 问题 | 选项 | 推荐 | 理由 |
|---|---|---|---|---|
| D-1 | 写穿后 Apply 灰掉是否符合预期 | A 归零（推荐）/ B 像 languagePending 一样保持常亮 | **A** | 路径已落盘，无「待应用」的东西；常亮反而误导。语言那边的常亮是因为「重建表单」还需用户确认 |
| D-2 | 错误态是否也写穿原文 | A 仅 Ok 写穿（推荐）/ B 任何文本都写穿 | **A** | 错误路径落盘没有用户价值，还可能把「打错一半」的中间态固化；现状 apply 语义保留 |
| D-3 | 热更新 OK 失效的探测提示是否并入本计划 | A 并入 / B 单独立项（在册待办） | **B** | 那是全设置面的横切问题（涉及 configurable 生命周期探测），与本字段无关；避免本计划范围膨胀 |
| D-4 | 撤销（Revert）语义 | A 接受写穿后 Revert 不能回到空（推荐）/ B 把写穿退回为仅提示 | **A** | 与语言写穿先例一致；真要清空可以把输入框删空再点「检测」（Disabled 态提示「此项关闭」，此态**允许写穿为空**——见 P-2） |

> 补充（D-4 的实现细节）：Disabled（输入框为空）时**允许写穿空值**——用户删空目录＝显式关闭功能，应当被记住。即写穿条件为「Ok 或 Disabled」，中间错误态不写。

---

## 五、待验证前提（实施者先证伪再动手）

| 编号 | 前提 | 证伪方式 |
|---|---|---|
| P-1 | 写穿后平台 Apply 按钮行为正常（灰掉不异常；后续改其他设置仍能正常启用） | 单测：写穿后 isModified()==false；再改 autoCheck → isModified()==true |
| P-2 | Disabled 态写穿空值后，重启回读为空、状态行显示「未设置（此项关闭）」 | 单测 + 沙盒 |
| P-3 | 写穿触发的 `loadState` 不会与 400ms 防抖的 BGT 扫描竞态出脏读 | 扫描只读、写穿在 EDT；单测并发压一轮 |
| P-4 | `mp-sql-visualizer.xml` 实际落盘含 `<localUpdateDir>`（XmlSerializer 对该字段名无保留字问题） | `PluginSettingsPersistenceTest` 补一轮 XmlSerializer round-trip 用例 |

---

## 六、实施拆解

| 里程碑 | 内容 | 完成判据 |
|---|---|---|
| M1 写穿 | `UpdateSection.writeThroughIfValid()`（Ok/Disabled 两态、幂等、换引用）；`browseLocalDir` 与防抖 Ok/Disabled 分支各接一处；`openLocalDir` 前不改（打开不该改配置） | 单测 T1~T4 全绿 |
| M2 测试 | T1 写穿后 state 非空 + isModified 归零；T2 Disabled 写穿空值；T3 错误态不写穿；T4 XmlSerializer round-trip（PluginSettingsPersistenceTest 补例）；反向验证＝摘掉 browse 处写穿调用 → T1 红复绿 | 定向全绿 + 反向成对读数 |
| M3 文档 | CHANGELOG `[Unreleased]` Fixed 一句；计划索引登记 | 守卫全绿 |

改动面：`config/UpdateSection.kt`（写穿函数 + 两处调用）、`PluginSettingsPersistenceTest.kt` / `UpdateSectionTest.kt`（用例）、`CHANGELOG.md`。**不新增**设置字段、不动 `PluginSettings`。

---

## 七、验收（交用户，GUI）

| 编号 | 步骤 | 预期 |
|---|---|---|
| A1 | 浏览选一个目录（不点确定/应用）→ 状态行出读数 → **重启 IDE** | 路径仍在输入框，状态行直接出「已找到 N 个安装包…」，启动检测生效 |
| A2 | 删空输入框 → 重启 | 输入框空、状态行「未设置（此项关闭）」 |
| A3 | 填一个错误路径 → 不点确定 → 重启 | 输入框为空（错误态未写穿），重填即恢复 |
| A4 | 写穿后打开其他设置页改一项 → 确定 | 两项都落盘 |

---

## 八、文档同步

| 文档 | 内容 |
|---|---|
| `CHANGELOG.md` `[Unreleased]` | Fixed：本地安装包目录重启后不再丢失（选完即存，无需点确定） |
| `docs/索引/开发计划.md` | 本计划索引行 |

---

## 九、实施记录

- 实施分支：`t-localdir-persist` @ 工作树 `.worktrees/t-localdir-persist`（计划提交 `6b3ad9de4` 之上切出；并行登记 `t-localdir-persist` / 沙盒 `wt-localdir-persist`；本节回填**工作树副本**）。
- M1 落点：`UpdateSection.validateLocalDirAsync` 的 BGT 决议（Available/UpToDate/Empty/NoValidPackage → 归一路径；Disabled → 空串；DirInvalid/Failed → 不写）+ EDT 落盘 `writeThrough(normalized)`（换引用 `loadState(state.copy(...))`、同值幂等、回写归一值使 isModified 归零）。browse 走同一条防抖校验链即写穿；`openLocalDir` 不写配置（打开不该改配置）。
- M2 落点：`UpdateSectionTest` 三例（写穿 ValidDir / Disabled 写穿空值 / 错误态不覆盖已写穿值）+ `PluginSettingsPersistenceTest.testStateRoundTripKeepsLocalUpdateDir`（XmlSerializer 真实序列化往返，P-4/P-7 一次钉住）。
- M3 落点：CHANGELOG `[Unreleased]` Fixed 一句（按用户口径简写）。
- **定向读数**：终跑宽定向 **292 / 0 失败 / 0 错误 / 0 跳过**（update 全包 + UpdateSectionTest + Binding + Persistence + 5 守卫）。
- **反向验证（成对读数）**：摘掉 validateLocalDirAsync 里的 `writeThrough` 调用 → `testLocalDirWriteThroughOnValidDir` 与 `testLocalDirErrorStateNotWriteThrough` 恰 2 红（Disabled 例断言空态本就为空、自然通过，符合预期）；还原后终跑全绿。
- **过程记录**：两轮 `insufficient memory ... G1 virtual space`（Commit 74.2/74.7GB 打满）——`./gradlew --stop` 停空闲 daemon 回收 1.3GB + `-PtestHeapSize=320m` 降测试堆后恢复；另有测试 helper 返回类型笔误（Path/File）一轮编译红。
- **not run**：`testShards` 全量（按 20261113 用户口径改走 CI：合 master 推双仓触发 push-test，后台轮询读数）；runIde 沙盒与真机 GUI 验收（写穿后重启路径保留 = 验收 A1，交用户）。
