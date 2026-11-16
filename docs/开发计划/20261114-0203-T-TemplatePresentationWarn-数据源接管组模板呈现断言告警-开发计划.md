# T-TemplatePresentationWarn：数据源接管组「Template presentations must not be used directly」告警

- 立项：2026-11-14 02:03
- 任务源：用户验收（runIde 沙盒 01:47 会话）后要求查日志；日志分析发现真机 243 每次接管组 update 打 2 条 WARN 带全栈，全 log 累计 40 条
- 修复提交：`be43c4bf6`

## 一、证据

用户验收会话（IU-2024.3.7.1，01:49:04 控制台挂载后）：

```
WARN - #c.i.o.a.Presentation - Template presentations must not be used directly
java.lang.Throwable: Template presentations must not be used directly
	at com.intellij.openapi.actionSystem.Presentation.assertNotTemplatePresentation(Presentation.java:581)
	at com.intellij.openapi.actionSystem.Presentation.setEnabled(Presentation.java:545)
	at cn.song.mybatisplus.visualizer.execute.ChooseDataSourceTakeoverGroup.update(ChooseDataSourceTakeover.kt:265)
	…
```

同一断言还从 `setVisible`（266 行）触发；全 log `grep -A6` 计数：`setEnabled` ×10 + `setVisible` ×10 = 每轮 update 两条。功能未坏（断言只 WARN 不拦写），但这是平台点名的反模式，263 有升级为 error 的风险。

## 二、243 字节码定位（app-client.jar，javap 实证）

- `assertNotTemplatePresentation()`：查 `myFlags & 4096`（TEMPLATE 位）→ `LOG.warnInProduction(Throwable)`——**只告警、不抛、不拦写**；
- 断言只长在 `setVisible(boolean)` 与 `setEnabled(boolean)` 两个 setter 上；`setText`/`setIcon`/`setPerformGroup`/`setPopupGroup` 均不查；
- 平台自家的 **`copyFrom(Presentation)` 是合规镜像通道**：私有实现 `copyFrom(Presentation, Component, boolean, boolean)` 在非模板复制分支直接 `BitUtil.set(myFlags, …)` 写 enabled/visible 位——**不经过断言**，且补发 `enabled`/`visible` 属性变化事件（工具栏按钮据事件重绘，比手写镜像多一层正规通知）；文本/描述/图标/客户端属性一并镜像，performGroup 位不在复制面里。

## 三、修法

`ChooseDataSourceTakeoverGroup.update()` 的模板镜像四行（setText/icon/setEnabled/setVisible）收敛为 `templatePresentation.copyFrom(e.presentation)`；`setPerformGroup` 仍单独设（copyFrom 不复制该位）；空文案回填逻辑不变（在镜像之前执行，copyFrom 把回填后的文本一并镜像进去）。

## 四、待验证前提（实施即证）

- **P-1**：`copyFrom` 在工具栏按钮上的观感与旧镜像一致（数据源名仍显示、可用性同步）——由功能用例 `testupdate把原生呈现镜像进模板` 钉住（文本 + isEnabled 双断言）。
- **P-2**：runIde 真机不再出现该 WARN——待下次沙盒会话确认（本仓无平台日志断言手段）。

## 五、横向审计

全仓 `templatePresentation.` 写点仅两处：

| 位置 | 场景 | 是否受影响 |
|---|---|---|
| `ChooseDataSourceTakeover.kt` update() | update 期写模板 | **是（本刀修复）** |
| `ChooseDataSourceTakeover.kt` init 块 | 构造期 setPerformGroup/setText/icon | 否（这些 setter 不带断言） |
| `SqlTemplateGroup.kt` init 块 | 构造期 text/description | 否（同上，且不涉及 enabled/visible） |

## 六、验收与实施记录

- 守卫：`TakeoverUpdateThreadGuardTest` 禁令牌 +2（`templatePresentation.setEnabled` / `.setVisible`，自证夹具钉住正反两面，合规形态夹具补 `copyFrom`）；`ChooseDataSourceTakeoverTest` 新增功能用例钉住镜像真实生效。
- 定向读数：`TakeoverUpdateThreadGuardTest` 2/2、`ChooseDataSourceTakeoverTest` 4/4、`GuardSelfTestCoverageMetaGuardTest` 8/8 全绿。
- **反向验证通过**：把生产代码临时改回 `setEnabled/setVisible` → 守卫红，点名两处禁令牌；改回 `copyFrom` → 绿（见下）。
- **not run**：runIde 真机确认 WARN 消失（P-2）——沙盒 GUI 验收留待用户下次走查。
