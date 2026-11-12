// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlWriteRiskText.kt 移植（拆件：internal enum）
package cn.song.mybatisplus.visualizer.ui;

import org.jetbrains.annotations.NotNull;

/**
 * T-FullTableWriteRisk：无 WHERE 的写语句风险类型（横幅文案走 i18n 键，判据留纯文本层）。
 *
 * <p>与 Wrapper/JPA/Querydsl 各转换器里既有的「⚠ 无 WHERE 条件，将删除全表数据！」同语义——
 * 那些链在转换器里直接产出 {@code PreviewResult.warning}，XML 预览链此前<b>没有这一档</b>：
 * 产物随勾选实时变化（把 {@code _parameter != null} 这类 {@code <if>} 取消勾选，{@code delete from t where …}
 * 立刻变成 {@code delete from t}），而面板只挂「预览未完整还原」，看不出这是<b>全表写</b>
 * （真机 2026-11-12 截图）。</p>
 */
public enum FullTableWriteRisk {
    DELETE("panel.banner.writeRiskDelete"),
    UPDATE("panel.banner.writeRiskUpdate");

    private final String bundleKey;

    FullTableWriteRisk(@NotNull String bundleKey) {
        this.bundleKey = bundleKey;
    }

    @NotNull
    public String getBundleKey() {
        return bundleKey;
    }
}
