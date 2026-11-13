package cn.song.mybatisplus.visualizer.xml.ibatis.fidelity

import com.ibatis.common.resources.Resources
import com.ibatis.sqlmap.client.SqlMapClient
import com.ibatis.sqlmap.client.SqlMapClientBuilder
import com.ibatis.sqlmap.engine.impl.SqlMapClientImpl
import com.ibatis.sqlmap.engine.mapping.parameter.ParameterMap
import com.ibatis.sqlmap.engine.mapping.parameter.ParameterMapping
import com.ibatis.sqlmap.engine.mapping.sql.Sql
import com.ibatis.sqlmap.engine.mapping.statement.InsertStatement
import com.ibatis.sqlmap.engine.mapping.statement.SelectKeyStatement
import com.ibatis.sqlmap.engine.scope.SessionScope
import com.ibatis.sqlmap.engine.scope.StatementScope
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringReader
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.sql.PreparedStatement

/**
 * iBatis 2.3.4.726 **真引擎**保真对照驱动（T-Ibatis2Support 券 B/C）。
 *
 * 取真值配方（jar 内源码 + 运行时探针实测，**不连库**）：
 * ```
 * SessionScope ss = new SessionScope(); ss.setSqlMapClient(client);
 * StatementScope scope = new StatementScope(ss);
 * ms.initRequest(scope);                                  // 必须，否则静态语句取不到 parameterMap
 * Sql sql = ms.getSql();
 * ParameterMap pm = sql.getParameterMap(scope, params);    // DynamicSql 在此完成一遍 tag 树求值
 * String sqlText = sql.getSql(scope, params);
 * Object[] values = pm.getParameterObjectValues(scope, params);
 * sql.cleanup(scope);
 * ```
 *
 * **为什么直接 import `com.ibatis.*` 而不用隔离 classloader**：iBatis 2 的包名是 `com.ibatis.*`，
 * 与仓内既有的 MyBatis 3（`org.apache.ibatis.*`）**零命名冲突**，且依赖是显式声明的
 * `testImplementation("org.apache.ibatis:ibatis-sqlmap:2.3.4.726")`（只进测试 classpath）。
 * 对照集要吃的就是**这个 jar**，绕反射只会让断言的可读性变差。
 *
 * 建 client 不连库：`SqlMapConfigParser` 的 `<sqlMap resource="..."/>` 走
 * `Resources.getResourceAsStream`，故用一个**只重写单个资源名**的 ClassLoader 把 sqlMap 文本喂进去，
 * 其余资源（两份 DTD）交给父加载器（`SqlMapClasspathEntityResolver` 只查 classpath，不联网）。
 */
internal object IbatisOracle {

    private const val RESOURCE_NAME = "mpv-ibatis-oracle.sqlmap.xml"

    /** 真引擎一条语句的渲染读数。 */
    data class OracleRender(
        val sql: String,
        val paramProperties: List<String>,
        val paramJdbcTypes: List<String?>,
        val paramNullValues: List<String?>,
        /** `Sql` 实现类简单名（`StaticSql` / `SimpleDynamicSql` / `DynamicSql`）。 */
        val sqlImpl: String,
        /** 用录制型 `PreparedStatement` 跑一遍 `pm.setParameters` 得到的绑定调用序列。 */
        val bindCalls: List<String>,
        /** `selectKey` 形态才有：引擎 `SelectKeyStatement.isRunAfterSQL()`。 */
        val runAfterSql: Boolean? = null,
    )

    /** 渲染真引擎语句（`statementId` 为**完整 id**，含 namespace 前缀）。 */
    fun render(sqlMapXml: String, statementId: String, params: Any?, captureBinds: Boolean = false): OracleRender {
        val client = buildClient(sqlMapXml)
        val delegate = (client as SqlMapClientImpl).delegate
        val statement = delegate.getMappedStatement(statementId)
        return renderStatement(client, statement, params, captureBinds)
    }

    /** 渲染 `<insert>` 的 selectKey 语句（引擎里它**不注册**进 delegate，只能从宿主 insert 取）。 */
    fun renderSelectKey(sqlMapXml: String, insertId: String, params: Any?): OracleRender {
        val client = buildClient(sqlMapXml)
        val delegate = (client as SqlMapClientImpl).delegate
        val insert = delegate.getMappedStatement(insertId) as InsertStatement
        val selectKey = insert.selectKeyStatement
            ?: throw IllegalStateException("insert [$insertId] 没有 selectKey 语句")
        val rendered = renderStatement(client, selectKey, params, captureBinds = false)
        return rendered.copy(
            runAfterSql = selectKey.isRunAfterSQL,
            sqlImpl = "SelectKeyStatement",
        )
    }

    private fun renderStatement(
        client: SqlMapClient,
        statement: com.ibatis.sqlmap.engine.mapping.statement.MappedStatement,
        params: Any?,
        captureBinds: Boolean,
    ): OracleRender {
        val sessionScope = SessionScope()
        sessionScope.sqlMapClient = client
        val scope = StatementScope(sessionScope)
        statement.initRequest(scope)
        val sql: Sql = statement.sql
        val parameterMap: ParameterMap = sql.getParameterMap(scope, params)
        val sqlText: String = sql.getSql(scope, params)
        val values = parameterMap.getParameterObjectValues(scope, params)
        val mappings = parameterMap.parameterMappings
        val binds = if (captureBinds) recordBinds(parameterMap, scope, values) else emptyList()
        sql.cleanup(scope)
        return OracleRender(
            sql = sqlText,
            paramProperties = mappings.map(ParameterMapping::getPropertyName),
            paramJdbcTypes = mappings.map(ParameterMapping::getJdbcTypeName),
            paramNullValues = mappings.map(ParameterMapping::getNullValue),
            sqlImpl = sql.javaClass.simpleName,
            bindCalls = binds,
        )
    }

    /** 用 `java.sql.PreparedStatement` 动态代理录制 `pm.setParameters` 的绑定调用（`nullValue` 哨兵的真值来源）。 */
    private class BindRecorder : InvocationHandler {
        val calls = ArrayList<String>()

        override fun invoke(proxy: Any?, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? {
            if (method.name.startsWith("set") && args != null) {
                calls.add("${method.name}(${args.joinToString(", ") { it?.toString() ?: "null" }})")
            }
            return null
        }
    }

    private fun recordBinds(parameterMap: ParameterMap, scope: StatementScope, values: Array<Any?>): List<String> {
        val recorder = BindRecorder()
        val statement = Proxy.newProxyInstance(
            IbatisOracle::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
            recorder,
        ) as PreparedStatement
        parameterMap.setParameters(scope, statement, values)
        return recorder.calls.toList()
    }

    private fun buildClient(sqlMapXml: String): SqlMapClient {
        val loader = object : ClassLoader(IbatisOracle::class.java.classLoader) {
            override fun getResourceAsStream(name: String): InputStream? =
                if (name == RESOURCE_NAME) {
                    ByteArrayInputStream(sqlMapXml.toByteArray(StandardCharsets.UTF_8))
                } else {
                    super.getResourceAsStream(name)
                }
        }
        Resources.setDefaultClassLoader(loader)
        val config = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE sqlMapConfig PUBLIC "-//iBATIS.com//DTD SQL Map Config 2.0//EN" "http://ibatis.apache.org/dtd/sql-map-config-2.dtd">
            <sqlMapConfig>
              <sqlMap resource="$RESOURCE_NAME"/>
            </sqlMapConfig>
        """.trimIndent()
        return SqlMapClientBuilder.buildSqlMapClient(StringReader(config))
    }
}
