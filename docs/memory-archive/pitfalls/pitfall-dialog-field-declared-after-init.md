---
name: pitfall-dialog-field-declared-after-init
description: 本仓第二次实测同型缺陷——类的 init 块若会同步触发列表/渲染器回调，其依赖字段必须声明在 init 之前，否则构造期
  NPE（createdAtFormat ThreadLocal null）；改这类对话框时把「字段声明顺序」当契约检查
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d315d25a-d32d-4fbf-b870-92694ad42276
---

**缺陷形态**：Kotlin 中 `init { … }` 在类体里**按声明顺序**执行。对话框/面板若在 `init` 里同步建列表并触发渲染器回调（或注册监听器并被回调），而回调读取的字段声明在 `init` **之后**，构造期该字段还是 null → NPE。用户侧表现是「打开某个对话框必崩」，栈指向渲染器 `getListCellRendererComponent`。

**本仓两处实测（同一根因，第二次）**：

1. `AgentManager.consentAskGuardMs` / `lastConsentAskAtMs`（R2 去重窗）声明在 init 块之后、SETTINGS_CHANGE 监听回调链读之——构造期同步事件下去重窗失效 + 时刻表 NPE；`1631c47` 修复＝两属性上移 init 前（KDoc 已钉「须声明在 init 之前」防回落）。
2. `SqlSuppressManagerDialog.createdAtFormat`（`ThreadLocal.withInitial { SimpleDateFormat(...) }`）声明在 :412，而构造链是 `init { … refreshRuleList(select = null) }`（:113–122）→ `refreshRuleList`（:298 `jList.setSelectedValue`）同步驱动 `RuleRowRenderer.getListCellRendererComponent`（:230/:195）→ `createdAtText`（:417 `createdAtFormat.get()`）→ `NullPointerException: Cannot invoke "java.lang.ThreadLocal.get()" because "this.createdAtFormat" is null`。**只要规则数 ≥1（有行要渲染）必现**。修法同上：字段声明移到 `init` 之前。

**20261021 第三次实测（UI-042，用户真机「复制 SQL 并关闭按钮整个不见」）**：`SqlPreviewDialog` 的 `copyAndCloseAction` 声明在 `init { …init()… }` 之后，`init()` 同步回调 `createActions()` 读到 null——**平台对 null 动作不抛错，只是不渲染该按钮**（与上两处 NPE 形态不同：这里是**静默消失**，不炸、不打日志）。修复＝字段上移；台账登记 UI-042（master `ab674aaa`）。
**同轮查出守卫本身也是瞎的**：`DialogWrapperInitOrderGuardTest` 的 `blockEnd()` 只按 `{}` 深度算函数范围，而 `createActions` 是**表达式体**（`= arrayOf(` 跨多行）→ 函数体被截成一行，该字段整段落在扫描面外。已修两处：表达式体按「括号配平+缩进回退」界定、多行签名（`) {` 换行）先推进到含 `{` 的行；反向验证放回缺陷后守卫精确命中 `copyAndCloseAction（声明@97）`。

**判据/检查动作**：改任何「构造器里建列表、注册监听、刷新 UI、声明动作数组」的对话框或面板时，先通读字段声明顺序——凡是构造期回调链会读到的字段，一律排到 `init` 之前（**测试缝属性同理**：捕获构造期产出的字段若声明在 init 后，它的 `= null` 初始化会在捕获之后执行、把捕获结果抹掉）；仓内守卫类＝`DialogWrapperInitOrderGuardTest`（第 1 处缺陷留下的，20261021 修好了表达式体漏检）。

**Why:** 这类缺陷编译期零告警、单测（不构造该对话框）也照绿，只有真机点开才炸；两次都花了用户一次走查才发现，第二次说明「修完点上没修到类上」——把它当成本仓的固定检查项比事后归因更省。
**How to apply:** 见栈里出现 `XxxDialog.<init> → refresh/attach → 渲染器或监听器 → NPE(T xxx)`，第一反应就是「字段声明在 init 之后」，把声明上移即可，勿在回调里加空判（那会把构造期依赖藏成静默降级）。相关：[[pitfall-dialog-wrapper-late-binding]]（同族构造期时序族）、[[project-agent-enhanced-capture-consent]]（第 1 处实证）。
