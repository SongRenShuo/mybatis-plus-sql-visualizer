package cn.song.mybatisplus.visualizer.execute;

import java.util.List;

/**
 * 空 Runner「方言不匹配」态的提示数据（公开原因同 {@link RegisteredJdbcSource}：公共回调签名）。
 *
 * <p>UI-049（2026-11-04 用户真机）：把「空 Runner · 方言不匹配」从一句长红字升级为可操作的提示。
 * 归因五态（无源 / 判不出方言 / 平台调用失败 / 已绑定仍不可用 / 方言不匹配）仍由调用方
 * {@code emptyRunnersReason} 判定；本类只在最后一态构造。</p>
 */
public record DialectMismatchHint(
        /** 文案里的渲染方言名（如 MySQL / PostgreSQL / DM（达梦））。 */
        String dialectDisplay,
        /** 项目在册数据源总数。 */
        int totalRegistered,
        /** 由 URL 推断出来「像目标方言」的源（可能为空、可能多个）。 */
        List<RegisteredJdbcSource> likely
) {

    /** likely 中恰一个时的便捷访问（Kotlin {@code singleOrNull()} 语义）。 */
    public RegisteredJdbcSource uniqueLikely() {
        return likely.size() == 1 ? likely.get(0) : null;
    }

    /** 未点名的其余源数量（用于「另有 N 个源已折叠」）。 */
    public int otherCount() {
        return Math.max(totalRegistered - likely.size(), 0);
    }
}
