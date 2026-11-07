package cn.song.mybatisplus.visualizer.xml;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * maintainPage 语句（id + 元数据 + SQL + 列信息）。
 *
 * @param id         语句 id
 * @param title      标题
 * @param autoQuery  是否自动查询
 * @param entityClass 实体类名
 * @param sql        SQL
 * @param columns    列信息
 * @param declaredType T-PagesHql：{@code <datasource type="...">} 显式声明（hql / jpql / sql）；
 *                    缺省 null。部分框架版本用它标注源语言，是最权威的判定证据
 */
public record PagesStatement(String id, String title, boolean autoQuery, String entityClass, String sql,
                             List<PagesColumn> columns, @Nullable String declaredType) {

    /** Kotlin 默认参数（declaredType=null）等价重载。 */
    public PagesStatement(String id, String title, boolean autoQuery, String entityClass, String sql,
                          List<PagesColumn> columns) {
        this(id, title, autoQuery, entityClass, sql, columns, null);
    }
}
