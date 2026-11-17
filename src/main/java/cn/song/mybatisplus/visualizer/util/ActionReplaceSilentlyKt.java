package cn.song.mybatisplus.visualizer.util;

import org.jetbrains.annotations.NotNull;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.CustomShortcutSet;

/**
 * T-CommentShortcutKeymap：<b>换入/换回全局动作前，先把目标动作的快捷键集合置为
 * {@link CustomShortcutSet#EMPTY}</b>，再交给 {@link ActionManager#replaceAction}。
 *
 * <p>为什么必须这一步（IDEA 2024.3 AnAction / ActionManagerImpl 字节码实证）：</p>
 * <ul>
 * <li>{@code ActionManagerImplKt.registerAction} 对<b>每次注册</b>都执行
 *     {@code action.registerCustomShortcutSet(new ProxyShortcutSet(actionId), null)}；
 *     replaceAction = unregister + register ⇒ 换入与换回各触发一次、每次都是新的 proxy 实例；</li>
 * <li>{@code AnAction.setShortcutSet} 在「新集合 ≠ 当前集合 <b>且</b> 当前集合 ≠ EMPTY
 *     且 PROJECT_OPENED 已发生 且 {@code ActionManager.getId(this) != null}」时打
 *     {@code WARN + PluginException("ShortcutSet of global AnActions should not be changed outside of
 *     KeymapManager")}——沙盒真机日志实测每关一次预览面板刷一条；</li>
 * <li>唯一豁免条件就是「当前集合恰为 {@link CustomShortcutSet#EMPTY}」（字节码
 *     {@code if_acmpeq EMPTY → 跳过}）。</li>
 * </ul>
 *
 * <p>本预置自身不告警：调用点都在「目标动作此刻不在注册表」时——换入的是刚 new 出来、从未登记过的
 * 实例；换回的原动作已被上一次 unregisterAction 从 actionToId 移除（getId 返回 null）。
 * {@code getId(action) != null} 时跳过预置：说明该动作被别人登记着（第三方换过），此时预置本身会告警，
 * 交给平台回填即可（那种场景平台侧本来也会告警一次，不叠加）。</p>
 *
 * <p>换入后平台回填的仍是 {@code ProxyShortcutSet(actionId)}（按 keymap <b>惰性</b>解析），
 * 键位绑定与换入前等价。</p>
 *
 * <p><b>单一入口约束</b>：src/main 里不允许在别处直接调 {@code ActionManager.replaceAction}
 * （EditorCommentShortcutsTest 有全树源码守卫）；新增接管点必须走本函数。</p>
 *
 * <p><b>只能用公共 API</b>：置 EMPTY 必须经 {@code registerCustomShortcutSet(CustomShortcutSet.EMPTY, null)}，
 * 不能直接用 {@code AnAction.setShortcutSet}——后者在平台是 {@code @ApiStatus.Internal}，
 * verifyPlugin 会判 INTERNAL_API_USAGES 并直接把发行轮打成红（2026.11.13 首次发内测版实测）。
 * 守卫同时禁止 src/main 出现 {@code setShortcutSet(}。</p>
 *
 * <p>master 侧为 ActionManager 扩展函数，Java 侧按 k2j 惯例落 {@code ActionReplaceSilentlyKt} 门面类。</p>
 */
public final class ActionReplaceSilentlyKt {

    private ActionReplaceSilentlyKt() {
    }

    /** 换入/换回全局动作的静默通道（见类 KDoc 的豁免判据与公共 API 红线）。 */
    public static void replaceActionSilently(@NotNull ActionManager actionManager,
                                             @NotNull String actionId,
                                             @NotNull AnAction action) {
        if (actionManager.getId(action) == null) {
            // T-CommentShortcutKeymap：这里**不能**用 AnAction.setShortcutSet——它在平台里是
            // @ApiStatus.Internal，verifyPlugin 会判 INTERNAL_API_USAGES 并拦下发行轮
            //（2026.11.13 首发实测）。改为调**公共**的 registerCustomShortcutSet(set, null)：
            // 其方法体就是 setShortcutSet(set)（243 字节码偏移 9-13）+ null 组件直接返回，
            // 效果等价、且只走公开 API。传的必须是 CustomShortcutSet.EMPTY **这个单例**
            //（平台按引用比较豁免），自建空 CustomShortcutSet 不算。
            action.registerCustomShortcutSet(CustomShortcutSet.EMPTY, null);
        }
        actionManager.replaceAction(actionId, action);
    }
}
