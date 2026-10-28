# PSI 跨线程读取协议

> 适用：插件内一切「在非 EDT 线程读 PSI / Document」的代码，以及「在 EDT 上做文件/文档 I/O」的代码（第 5 节，2026-10-23 补）。
> 出处：T-PsiCaptureProtocol（竞品第八轮报告 §9.4 O-35，来源 §7.2 Z-16）。
> 工具与守卫：`psi/PsiSnapshot.kt`（两段式快照，`capture(element)` 捕获 + `validate()` 校验）、`convention/BannedApiSourceScanTest`（psiCapture / edtReadContract 两条规则）。
> 最后核实：2026-10-27（`PsiSnapshot` 的 `capture` / `validate` / `Valid` 形态未变）。

---

## 1. 为什么要写这份协议

IDEA 的 PSI / Document 读取有两条独立约束，容易被混为一谈：

1. **读权限**——后台线程必须在 `ReadAction` 内读 PSI / 平台模型，否则抛
   `Read access is allowed from inside read-action only`（262 平台对 EDT 直调 PSI 的硬抛也是同一族问题）。
2. **元素有效性**——PSI 元素是**版本化**的：捕获一个 `PsiElement` 之后，只要发生文档写
   （用户输入、平台 reparse、其他插件的写），该元素就可能失效或区间漂移。

只做第 1 条（补 `ReadAction`）能消除权限异常，但消除不了第 2 条：后台拿着一个已经失效的元素
去读 `textRange` / `containingFile`，结果是**错行号**或偶发失效异常——这类缺陷在 headless 测试里
几乎不复现，只在真机（用户一边打字一边点 gutter）出现。本协议把两类约束分开写清，
并给出三条合规形态。

既有约定起点是 `ui/SqlPreviewService.kt` 的 `previewAsync` KDoc：
「锚点元素可先在 EDT 捕获，块内读取」——那份 KDoc 只说了「可以捕获后读」，没规定
**捕获与读取之间发生写操作怎么办**。本协议补的就是后半句。

## 2. 三条合规形态

### 形态 A：EDT 直读（默认形态）

适用条件：读发生在 EDT（daemon 快速路径、`AnAction.actionPerformed`、菜单/弹窗回调、
`invokeLater` 排入的回调）且不做慢操作。

要点：

- EDT 天然持有读权限，**不需要**包 `ReadAction`；包了也无害（`compute` 在 EDT 内联执行）。
- 「EDT 合法」不等于「可以慢」：PSI 定位/解析在 2025.3+ 属慢操作，EDT 上跑会被
  `Slow operations are prohibited on EDT` 拦下——所以形态 A 只适合 O(1) 纯语法判定
  （`WrapperAnchorDetector.anchorElement` 这类零 resolve 的快速路径）。

反例：把 `WrapperLocator` 全链路定位、实体注解解析放进 `getLineMarkerInfo`
（gutter provider 每个 leaf 都会被回调一次）。

### 形态 B：后台线程 + 自包 `ReadAction`（不跨线程传递元素）

适用条件：读发生在后台线程，且**被读的对象是在同一个后台块内解析出来的**（元素不跨线程传递）。

要点：

- `ReadAction.compute { … }`（243 编译目标下用 `ThrowableComputable` 重载，262 标 deprecated
  但保留，仓内统一 `@Suppress("DEPRECATION")`）或 `ReadAction.nonBlocking`。
- 代表：`log/CallerMethodRangeResolver.kt`（自包 `ReadAction`，EDT 与后台均可直调）、
  `log/MapperXmlLocator.kt`、`ui/SqlPreviewService.previewAsync`。

反例：在后台块内 `PsiManager.findFile(vf)` 之后再 resolve 实体链——这属于形态 B 的正确用法，
但如果把结果 PSI 元素**带出**块外再用（存字段、传回调），下一次使用就退回形态 C 的风险面。

### 形态 C：EDT 捕获 + 后台校验读（跨线程传元素）

适用条件：触发点在 EDT（用户点击 / 光标事件），但后续读取成本高（候选收集、遍历、行号预计算），
必须挪到后台。

要点（**必须走两段式**）：

1. **EDT 侧捕获**：`PsiSnapshot.capture(element)`——记下所属 `VirtualFile`、`Document`、
   `textRange` 与当时的 `modificationStamp`。捕获本身是 O(1) 平台读，可在点击路径上做。
2. **后台侧校验**：`snapshot.validate()`（须在读权限内调用）——判「文件仍有效 / stamp 未变 /
   区间仍在界内」，任一不符即返回失效原因。
3. **如实降级**：校验不过就**不要读**——gutter 不渲染标记、跳转不跳、候选视为 0，
   并留一条 `LOG.debug` 诊断（标记缺失无害，不打扰用户）。**禁止**拿着失效区间继续算行号。

代表：`ui/gutter/SqlGutterLineMarkerProvider`（最近执行记录反查，收口 `validatedRecentAnchor`）、
`ui/gutter/StringSqlMethodGutterLineMarkerProvider`（方法定义锚点候选收集，收口 `validatedMethodRows`）。

反例（改造前的真实形态）：

```kotlin
// EDT 捕获 elt（gutter 点击）→ 后台块内直接读 elt.textRange
app.executeOnPooledThread {
    ReadAction.compute<Pair<String?, Int>, Throwable> {
        val file = elt.containingFile?.virtualFile ?: return@compute null to 0   // 捕获后文件被改/删即失效
        val doc = FileDocumentManager.getInstance().getDocument(file)
        val line = doc.getLineNumber(elt.textRange.startOffset) + 1             // 区间漂移 → 错行号
        relativeToProject(project, file.path) to line
    }
}
```

`ReadAction` 在，权限合规；但元素失效面完全裸露——这正是 Z-16 指出的缺口。

### 形态 C 的边界（不做的事）

- **不做文本交叉验证**。Z-16 原形态含「PSI text 与文档内容交叉验证」，在**读侧**没有增量价值：
  PSI 树由**已提交**的文档内容构建，编辑器里的未提交变更只体现在 `Document` 上——以
  「PSI 文本 == 文档对应区间文本」判定，用户刚敲完字（PSI 尚未 commit）就会被误判为失效，
  标记/记录静默消失。真实变更面已由 `modificationStamp` 覆盖。
- **不做写回**。Z-16 的 commit document 属写侧动作，本插件这两条链只读，故用
  `Document.getModificationStamp`（`DocumentImpl` 该 getter 是裸字段读，无 `assertInsideCommand`，
  javap 实证）而不是提交文档。
- **不覆盖同线程捕获-读取**。EDT 内捕获后立即读（形态 A）不走校验——否则每次编辑后
  gutter 标记都会短暂消失，属过度校验。

## 3. 乙类：靠约定而非快照的读（跳转族）

`log/EntryJumpResolver.kt` / `log/EntrySourceMatcher.kt`（`SourcePathResolver`）一族读的是
`ModuleManager` / `LocalFileSystem` 平台模型，**不碰 PSI / Document**，因此不适用形态 C 的元素快照；
它要的是「调用方持读权限」这一条：

- 现网调用面全部合规：`log/SqlLogPanel.kt` 与 `log/HistorySqlLogPanel.kt` 的右键跳转在 EDT；
  `log/CallerMethodRangeResolver.kt` 自包 `ReadAction`（属形态 B）。
- 该约定自 T-PCP-03 起有双重保障：`SourcePathResolver.resolveVirtualFile` 入口
  `assertReadAccessAllowed()` 硬断言（后台裸调即抛，违约暴露在调用点），
  加上守卫规则 `edtReadContract`（调用点文件须含 `ReadAction` 或登记在 EDT 白名单）。

反例：将来把「跳转到源码」挂到后台索引回调里裸调 `EntryJumpResolver.resolve`——
源码级规则与运行期断言都会拦下。

## 4. 守卫

| 规则 | 位置 | 拦什么 |
|---|---|---|
| `psiCapture` | `convention/BannedApiSourceScanTest` | `executeOnPooledThread` 池化块内出现 PSI 读令牌（`containingFile` / `viewProvider.document` / `.textRange` / `.textLength`）却无校验令牌（`PsiSnapshot` / `validatedRecentAnchor(` / `validatedMethodRows(`），且无 `// psi-capture: <理由>` 标注、文件不在豁免表 |
| `edtReadContract` | 同上 | 跳转族调用点文件既无 `ReadAction` 又不在 EDT 白名单 |
| 断言锚点 | 同上 | `SourcePathResolver.resolveVirtualFile` 丢失 `assertReadAccessAllowed()` |

`psiCapture` 的边界（如实登记，两个方向都有）：

- 漏报：PSI 读被挪进池化块**调用的另一个函数**即脱离扫描面（规则只看池化块本体）；
  只覆盖 `executeOnPooledThread` 这一在用的池化原语（仓内唯一；`runAsync` / `supplyAsync` /
  `ReadAction.nonBlocking` 仓内 0 处）。
- 过报：块内对**块内新解析**的对象读 `textRange`（非捕获元素）也会命中——遇到时把该读挪出块外，
  或加 `psi-capture:` 标注说明其非捕获元素。

豁免登记形态 = 文件 + 站点数 + 理由（站点数漂移即红）。当前唯一豁免：
`actions/ConvertToSqlAction.kt` 4 处（XML 选区/全文件语句候选，属「文件级捕获 + 块内重取 PSI」，
不是元素级快照；未在 T-PCP-02 的锚点范围内）。

## 5. 丙类：EDT 上的文件 I/O（2026-10-23 实证补充）

本协议原先只谈「跨线程读 PSI/Document 的元素有效性」。2026-10-23 的 `T-PreviewChainNoise` 收口
（`9809a5b7`）证明了**同一条链上的第二个独立约束**：EDT 上做文件 I/O 同样是违规，而且此前一直被
当成「读写 PSI 之外的旁枝」。

- **现象**：预览执行链有一处 `VirtualFile.inputStream` 读盘落在 EDT 上，日志里出现 9 条
  `Plugin to blame: SQL Visualizer` 的 SEVERE（平台慢操作断言）。改造后降到 0 条。
- **规则**：EDT 上不做 `VirtualFile.inputStream` / `document.setText` 这类文件或文档 I/O。
  读盘挪后台（形态 B），改文档必须派 EDT 且包 `WriteAction`——注意 262 起
  `FileDocumentManager.isDocumentUnsaved` 取代了已从接口移除的 `Document.isModified()`，
  而 BGT 改文件 document 即使用 `WriteAction` 包裹也救不回来（EDT-only）。
- **同族两处**（同批收口）：`createBlocking` 之类阻塞 API 在裸池线程（`executeOnPooledThread`）
  上缺进度上下文会打 SEVERE——兜底形态是缺上下文才补 `EmptyProgressIndicator`，已有指示器不替换；
  注册在父 disposable 上的子监听不要在 `dispose()` 里手动二次摘除（ObjectTree 后序子先父后，
  二次摘会抛「Can't remove document listener」）。
- **验证边界**：这三类都**只在 runIde 沙盒与真机暴露**，headless 测试全绿不代表没问题。
  判断「有没有用户受影响」看 IDE 日志里的 `Plugin to blame` 计数，不看测试读数。

跳转族在 EDT 上只做平台模型读（`ModuleManager` / `LocalFileSystem`），不涉及文件 I/O，
因此不受本节约束，仍按第 3 节「持读权限」一条执行。

## 6. 落地备注（Z-16）

- 锚点更正：第八轮报告 §7.2 Z-16 的引用里出现的 `StackLocator` 是**比喻性**引用——它是
  agent 子项目的 Java 调用栈定位工具（`sub-projects/sql-visualizer-agent/.../util/StackLocator.java`），
  不读 PSI / Document，不能作代码锚点。真实落点是两个 gutter provider 的「EDT 捕获 → 后台读」链。
- 现状核实：绝大多数后台 PSI 读已自带 `ReadAction`（清单见开发计划 §一），本任务不是「批量补权限包裹」，
  而是补形态 C 的失效校验与守卫。
- 真机走查（留人工）：快速编辑 mapper 文件（触发写）同时观察 gutter 标记——无异常、无错值标记；
  日志面板右键跳转在文件被外部修改后行为如实（不跳到错位置）。
