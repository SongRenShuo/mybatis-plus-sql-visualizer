package cn.song.mybatisplus.visualizer.xml;

/**
 * 判定证据（稳定 id：i18n、日志与测试共用，<b>已发布后不要改名</b>）。
 *
 * <p>Kotlin {@code enum class PagesSourceEvidence(val id: String)} 的 Java 承载；
 * 访问器按 k2j 惯例保留无 get 前缀形态 {@link #id()}（A2 调用点已按 {@code evidence().id()} 写死）。</p>
 */
public enum PagesSourceEvidence {
    /** {@code <datasource type="hql|jpql">} 显式声明。 */
    EXPLICIT_TYPE("explicit-type"),

    /** {@code <datasource type="sql">} 显式声明原生 SQL。 */
    EXPLICIT_SQL("explicit-sql"),

    /** 根标识符 == entityClass 的实体名（简单名或 {@code @Entity(name)}）。 */
    ROOT_IS_ENTITY_CLASS("root-is-entity-class"),

    /** 根标识符 == entityClass 对应的表名（反向证据：这是原生 SQL）。 */
    ROOT_IS_TABLE("root-is-table"),

    /** 根标识符能解析为持久化实体，且其表名 ≠ 根标识符（或命中 HQL 专属记号）。 */
    ROOT_RESOLVES_ENTITY("root-resolves-entity"),

    /** 无足够证据，保持原生 SQL（零行为变化）。 */
    DEFAULT_SQL("default-sql");

    private final String id;

    PagesSourceEvidence(String id) {
        this.id = id;
    }

    /** 稳定 id（i18n、日志与测试共用）。 */
    public String id() {
        return id;
    }
}
