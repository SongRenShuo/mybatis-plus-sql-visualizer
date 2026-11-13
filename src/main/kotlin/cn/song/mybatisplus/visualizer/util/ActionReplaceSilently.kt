package cn.song.mybatisplus.visualizer.util

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CustomShortcutSet

/**
 * T-CommentShortcutKeymap：**换入/换回全局动作前，先把目标动作的快捷键集合置为
 * [CustomShortcutSet.EMPTY]**，再交给 [ActionManager.replaceAction]。
 *
 * 为什么必须这一步（IDEA 2024.3 `AnAction` / `ActionManagerImpl` 字节码实证）：
 *  - `ActionManagerImplKt.registerAction` 对**每次注册**都执行
 *    `action.registerCustomShortcutSet(new ProxyShortcutSet(actionId), null)`；
 *    `replaceAction` = unregister + register ⇒ 换入与换回各触发一次、每次都是新的 proxy 实例；
 *  - `AnAction.setShortcutSet` 在「新集合 ≠ 当前集合 **且** 当前集合 ≠ `CustomShortcutSet.EMPTY`
 *    且 PROJECT_OPENED 已发生 且 `ActionManager.getId(this) != null`」时打
 *    `WARN + PluginException("ShortcutSet of global AnActions should not be changed outside of
 *    KeymapManager")`——沙盒真机日志实测每关一次预览面板刷一条；
 *  - 唯一豁免条件就是「当前集合恰为 `CustomShortcutSet.EMPTY`」（字节码 `if_acmpeq EMPTY → 跳过`）。
 *
 * 本预置自身不告警：调用点都在「目标动作此刻不在注册表」时——换入的是刚 new 出来、从未登记过的实例；
 * 换回的原动作已被上一次 `unregisterAction` 从 `actionToId` 移除（`ActionManager.getId` 返回 null）。
 * `getId(action) != null` 时跳过预置：说明该动作被别人登记着（第三方换过），此时预置本身会告警，
 * 交给平台回填即可（那种场景平台侧本来也会告警一次，不叠加）。
 *
 * 换入后平台回填的仍是 `ProxyShortcutSet(actionId)`（按 keymap **惰性**解析），键位绑定与换入前等价。
 *
 * **单一入口约束**：`src/main` 里不允许在别处直接调 `ActionManager.replaceAction`
 * （`EditorCommentShortcutsTest` 有全树源码守卫）；新增接管点必须走本函数。
 */
internal fun ActionManager.replaceActionSilently(actionId: String, action: AnAction) {
    if (getId(action) == null) {
        action.setShortcutSet(CustomShortcutSet.EMPTY)
    }
    replaceAction(actionId, action)
}
