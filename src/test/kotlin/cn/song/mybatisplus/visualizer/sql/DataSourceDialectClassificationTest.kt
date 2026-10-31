package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-DialectInferFix 券 01：数据源分类（谁能参与方言投票）。
 *
 * 关键回归：Redis/Mongo 这类非 SQL 数据源**不得**产出方言候选——它们一旦以 `null` 方言进候选，
 * 就会把「PostgreSQL + Redis」判成 AMBIGUOUS，进而让整个项目回落 MySQL（真机假红根因）。
 *
 * k2j 移植注（生产为 Java）：classify 是三参静态方法，master 的具名实参形态改位置实参
 * （queryLanguageId, dbmsDisplayName, url）；Result 为 Java record，kind/dialectName 按组件访问。
 */
class DataSourceDialectClassificationTest {

    @Test
    fun `platform query language wins over url subprotocol`() {
        val result = DataSourceDialectClassification.classify(
            "PostgreSQL",
            "PostgreSQL",
            "jdbc:postgresql://121.40.45.91:5432/cyzc_db1",
        )
        assertEquals(DataSourceDialectClassification.Kind.SQL_KNOWN, result.kind)
        assertEquals("PostgreSQL", result.dialectName)
    }

    @Test
    fun `tsql language maps to sql server registry name`() {
        val result = DataSourceDialectClassification.classify("TSQL", "Microsoft SQL Server", "jdbc:sqlserver://h:1433")
        assertEquals(DataSourceDialectClassification.Kind.SQL_KNOWN, result.kind)
        assertEquals("SQL Server", result.dialectName)
    }

    @Test
    fun `redis data source is not a sql target`() {
        val byLanguage = DataSourceDialectClassification.classify(
            "Redis", "Redis", "jdbc:redis://121.40.45.91:6379/1",
        )
        assertEquals(DataSourceDialectClassification.Kind.NON_SQL, byLanguage.kind)
        assertEquals(null, byLanguage.dialectName)

        val byUrlOnly = DataSourceDialectClassification.classify(null, null, "jdbc:redis://h:6379/1")
        assertEquals(DataSourceDialectClassification.Kind.NON_SQL, byUrlOnly.kind)
    }

    @Test
    fun `document oriented dbms names are not sql targets`() {
        listOf("MongoDB", "Mongo", "Apache Cassandra", "Couchbase", "Amazon DynamoDB", "Elasticsearch")
            .forEach { name ->
                val result = DataSourceDialectClassification.classify(null, name, null)
                assertEquals("$name 应归 NON_SQL", DataSourceDialectClassification.Kind.NON_SQL, result.kind)
            }
    }

    @Test
    fun `unsupported sql dbms stays unknown candidate`() {
        // 插件没收录的 SQL 库：仍是候选（null 方言）——保持引擎「未知候选不得宣称共识」的 fail-closed 语义。
        val result = DataSourceDialectClassification.classify("ClickHouse", "ClickHouse", "jdbc:clickhouse://h:8123")
        assertEquals(DataSourceDialectClassification.Kind.SQL_UNKNOWN, result.kind)
        assertEquals(null, result.dialectName)
    }

    @Test
    fun `generic sql language without url is unknown not non-sql`() {
        val result = DataSourceDialectClassification.classify("GenericSQL", "PostgreSQL", null)
        // 显示名兜底仍能认出方言
        assertEquals(DataSourceDialectClassification.Kind.SQL_KNOWN, result.kind)
        assertEquals("PostgreSQL", result.dialectName)

        val blank = DataSourceDialectClassification.classify(null, null, null)
        assertEquals(DataSourceDialectClassification.Kind.SQL_UNKNOWN, blank.kind)
    }

    @Test
    fun `dbms aliases map to registry names`() {
        val mssql = DataSourceDialectClassification.classify(null, "Microsoft SQL Server", null)
        assertEquals("SQL Server", mssql.dialectName)
        val mysql = DataSourceDialectClassification.classify(null, "MySQL", null)
        assertEquals("MySQL", mysql.dialectName)
    }

    @Test
    fun `non sql keys do not prefix match`() {
        // 精确比对：「Redshift」不是 Redis（前缀匹配会把它误判成非 SQL 数据源）
        val result = DataSourceDialectClassification.classify(null, "Redshift", "jdbc:redshift://h:5439/db")
        assertEquals(DataSourceDialectClassification.Kind.SQL_UNKNOWN, result.kind)
    }
}
