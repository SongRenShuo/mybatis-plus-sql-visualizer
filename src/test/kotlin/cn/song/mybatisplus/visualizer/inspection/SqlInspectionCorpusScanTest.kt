package cn.song.mybatisplus.visualizer.inspection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-4 **语料误报扫描**（NEW-26 立项册 §二 待验证前提）。
 *
 * 语料来源：`docs/需求文档/MPJ用法收集与兼容性测试清单.md` 的 X01~X05 节——该清单逐条枚举了
 * 本仓真实工程（20 个 mapper XML + 4 个注解接口）的写法，并标注了每类写法的出现次数。
 * 下面的夹具是**按清单原样转录**的代表性形态（表名列名已按清单占位），覆盖：
 *
 *  - X01 `statistics` 风格：`<bind>` 常量 + `${}` 列别名 + `<choose>` 单选/多选 + `&gt;` 转义；
 *  - X02 `${}` 的全部 22 处形态：**100% 是 bind 常量列别名**，无 order by/表名/列名拼接；
 *  - X03 resultMap：`extends` 继承、`<id>`+`<result>`、无 association/collection/constructor；
 *  - X04 自定义 SQL：多表 JOIN、`<if>` 嵌套 `<choose>`、窗口函数、`UNION ALL`、手写 LIMIT/OFFSET、
 *    `<=` / `<->` 在 CDATA 内、相关子查询 UPDATE、EXISTS 内嵌 foreach；
 *  - X05 注解 SQL：文本块多行、`ON CONFLICT` upsert、`#{record.xxx}` 实体属性路径、多参原子加减。
 *
 * **判据**：五条规则在上述语料上命中数必须为 **0**；任何非零命中先定位是规则误报还是语料真问题，
 * 误报则规则修到 0 才允许 `enabledByDefault=true`（立项册 §九 红线）。
 *
 * 这是「读真实语料形状」的扫描，不是合成样本自证——合成样本只能证明规则看得见我造的形态。
 */
class SqlInspectionCorpusScanTest {

    /** 语料条目：名称 + SQL 体 + 该条目在真实工程里对应的写法说明。 */
    private data class Corpus(val name: String, val sql: String, val note: String)

    private val corpus: List<Corpus> = listOf(
        Corpus(
            "X01/X02 statistics 风格（bind 常量 + ${'$'}{} 列别名 + choose + foreach）",
            """
            <bind name="orderCount" value="@com.xxx.common.constant.DicConstants@DIC_XXX_ORDER_COUNT"/>
            <bind name="chargeDegreeSum" value="@com.xxx.common.constant.DicConstants@DIC_CHARGE_DEGREE_SUM"/>
            select count(t.id) "${'$'}{orderCount}",
                   TRIM_SCALE(COALESCE(sum(t.charge_degree),0)) "${'$'}{chargeDegreeSum}"
            from xxx_order_record t
            <where>
                t.deleted = 0
                <if test="p.startTime != null"> and t.start_time &gt;= #{p.startTime} </if>
                <choose>
                    <when test="p.stationId != null"> and t.station_id = #{p.stationId} </when>
                    <when test="p.stationIds != null and p.stationIds.size() > 0">
                        and t.station_id in
                        <foreach collection="p.stationIds" item="sid" open="(" separator="," close=")">#{sid}</foreach>
                    </when>
                </choose>
            </where>
            """.trimIndent(),
            "20 个 XML 中 5 个 statistics 方法共 22 处 ${'$'}{}，全部为 bind 常量列别名。" +
                "清单 X01 的示例片段是节选（只贴了 orderCount 一条 bind），此处按正文" +
                "「22 处 100% 是 bind 常量列别名」补回 chargeDegreeSum 的 bind——不补的话" +
                "${'$'}{chargeDegreeSum} 就是无来源裸替换，MyBatis 运行期同样会报 unknown property。",
        ),
        Corpus(
            "X02 单 bind 列别名（SysMerchantShareRecordMapper 8 个别名）",
            """
            <bind name="colA" value="@com.xxx.Dic@A"/>
            select t.id "${'$'}{colA}", t.name "${'$'}{colA}"
            from xxx_share_record t where t.deleted = 0
            """.trimIndent(),
            "最多 8 个 ${'$'}{} 别名",
        ),
        Corpus(
            "X03 resultMap 无 association/collection",
            """
            <resultMap id="BaseResultMap" type="com.xxx.entity.MerchantWalletSettlementDetailEntity">
                <id column="id" property="id" jdbcType="BIGINT"/>
                <result column="merchant_id" property="merchantId" jdbcType="BIGINT"/>
                <result column="amount" property="amount" jdbcType="DECIMAL"/>
            </resultMap>
            """.trimIndent(),
            "8 个 resultMap 仅 id+result（column/property/jdbcType）",
        ),
        Corpus(
            "X04 多表 JOIN + 嵌套 choose 动态 JOIN 分支",
            """
            <bind name="kw" value="'x'"/>
            select d.*, m.name from detail d
            left join merchant m on m.id = d.merchant_id
            <where>
                <if test="keyword != null">
                    <choose>
                        <when test="keyword.length() > 0">and m.name like concat('%', #{keyword}, '%')</when>
                        <otherwise>and 1=1</otherwise>
                    </choose>
                </if>
            </where>
            """.trimIndent(),
            "MerchantWalletSettlementDetailMapper 动态 JOIN 三表/两表分支",
        ),
        Corpus(
            "X04 窗口函数 + ROW_NUMBER",
            """
            select * from (
                select t.*, row_number() over (partition by t.port_id order by t.id desc) rn
                from xxx_order_monitor t where t.deleted = 0
            ) x where x.rn = 1
            """.trimIndent(),
            "getLatestChargingUserByPortIds 派生表",
        ),
        Corpus(
            "X04 UNION ALL 合并分页（手写 LIMIT/OFFSET）",
            """
            select * from (
                select id, fee from a_table where deleted = 0
                union all
                select id, fee from b_table where deleted = 0
            ) u order by id limit #{limit} offset #{offset}
            """.trimIndent(),
            "OrderManagementMapper 手写分页，未用 MP 分页",
        ),
        Corpus(
            "X04 相关子查询 UPDATE（计数器校正）",
            """
            update coupon c
            set c.use_total = (select count(*) from coupon_user cu where cu.coupon_id = c.id)
            where c.id in
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            """.trimIndent(),
            "StoreCouponMapper 3 个计数器校正 UPDATE",
        ),
        Corpus(
            "X04 聚合 + PG 方言函数",
            """
            select count(*) filter (where t.status = 1) as ok,
                   date_trunc('month', t.create_time)::date as mon,
                   trim_scale(coalesce(sum(t.fee), 0)) as total
            from xxx_order t where t.deleted = 0 group by 2
            """.trimIndent(),
            "PG 方言聚合写法",
        ),
        Corpus(
            "X04 EXISTS 子查询内嵌 foreach",
            """
            select s.* from station s
            where exists (
                select 1 from bound_port p
                where p.station_id = s.id and p.port_id in
                <foreach collection="portIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
            )
            """.trimIndent(),
            "CarStationInfoMapper#nearBaseWhere",
        ),
        Corpus(
            "X04 CDATA 内 <= 与距离运算符",
            """
            <select id="near" resultType="map">
            <![CDATA[
            select * from station
            where deleted = 0 and create_time <= #{end}
            order by location <-> point(#{lng}, #{lat})
            ]]>
            </select>
            """.trimIndent(),
            "CDATA 内 <= / <->（X04 测试要点）",
        ),
        Corpus(
            "X05 注解文本块 upsert（ON CONFLICT）",
            """
            <script>insert into biz_open_info (col1, col2)
            values (#{record.col1}, #{record.col2})
            on conflict (biz_col) do update set
            col1 = coalesce(excluded.col1, biz_open_info.col1)</script>
            """.trimIndent(),
            "CarHuifuInvoiceBusinessOpenInfoMapper @Insert 文本块",
        ),
        Corpus(
            "X05 注解多参原子加减",
            """
            update merchant_wallet set available_amount = available_amount + #{amount}
            where merchant_id = #{merchantId}
            """.trimIndent(),
            "MerchantWalletMapper @Update 多参",
        ),
        Corpus(
            "X05 注解定时清理（物理删除）",
            """
            delete from xxx_log where create_time &lt; #{expireTime}
            """.trimIndent(),
            "LogCleanupMapper @Delete",
        ),
    )

    // ------------------------------------------------------------------
    // 规则 1（${}）在语料上的命中数必须为 0
    // ------------------------------------------------------------------

    @Test
    fun `P4_规则1_语料上dollar注入误报为0`() {
        val offenders = corpus.flatMap { item ->
            // 语料里 bind 名字从 <bind name=...> 提取（与生产路径 staticBindNames 同源逻辑）
            val bindNames = Regex("""<bind\s+name="([^"]+)"\s+value="([^"]+)"""")
                .findAll(item.sql)
                .filter { SqlInspectionText.isStaticBindValue(it.groupValues[2]) }
                .map { it.groupValues[1] }
                .toSet()
            SqlInspectionText.dollarFindings(item.sql, bindNames).map { "${item.name} → ${it.token}" }
        }
        assertEquals("语料上 ${'$'}{} 规则必须 0 命中（立项册 §九：误报为 0 才允许默认开）：\n${offenders.joinToString("\n")}", 0, offenders.size)
    }

    // ------------------------------------------------------------------
    // 规则 5（OGNL）在语料上的命中数必须为 0
    // ------------------------------------------------------------------

    @Test
    fun `P4_规则5_语料上常量条件误报为0`() {
        val testExpressions = Regex("""test="([^"]*)"""").findAll(corpus.joinToString("\n") { it.sql })
            .map { it.groupValues[1] }
            .toList()
        assertTrue("语料里应能提取出 test 表达式（空面会让本扫描空转）", testExpressions.isNotEmpty())
        val offenders = testExpressions.filter { SqlInspectionText.constantTestTruth(it) != null }
        assertTrue("语料上的 test 表达式均引用参数，不得判为常量条件：$offenders", offenders.isEmpty())
    }

    // ------------------------------------------------------------------
    // 规则 3（resultMap）在语料上的命中数必须为 0（语料里的 property 都是合法驼峰名）
    // ------------------------------------------------------------------

    @Test
    fun `P4_规则3_语料上属性误报为0`() {
        val properties = Regex("""property="([^"]*)"""").findAll(corpus.joinToString("\n") { it.sql })
            .map { it.groupValues[1] }
            .toList()
        assertTrue("语料里应能提取出 property（空面会让本扫描空转）", properties.isNotEmpty())
        // 语料 X03 的 type 是占位类名，运行时解析不出 → 生产路径返回 null（沉默）。这里模拟「类解析成功且
        // 成员名齐全」的最严情形：所有语料属性都必须在成员集合里，否则说明属性名提取本身有偏差。
        val members = properties.toSet()
        val offenders = properties.filter { SqlInspectionText.unknownResultMapProperty(it, members) != null }
        assertTrue("语料属性在两两互见时必须全部自洽：$offenders", offenders.isEmpty())
    }

    // ------------------------------------------------------------------
    // 规则 2（#{}）在语料上的命中数必须为 0（按语句真实参数名喂入）
    // ------------------------------------------------------------------

    @Test
    fun `P4_规则2_语料上参数误报为0`() {
        // 逐条给出该语句真实可用的根名（对齐 ParamNameResolver：@Param 值 / 多参 paramN / foreach item）
        val cases = listOf(
            corpus.first { it.name.startsWith("X01") } to setOf("p", "param1", "sid"),
            corpus.first { it.name.startsWith("X04 多表") } to setOf("keyword", "param1"),
            corpus.first { it.name.startsWith("X04 UNION") } to setOf("limit", "offset", "param1", "param2"),
            corpus.first { it.name.startsWith("X04 相关") } to setOf("ids", "param1", "id"),
            corpus.first { it.name.startsWith("X04 EXISTS") } to setOf("portIds", "param1", "pid"),
            corpus.first { it.name.startsWith("X04 CDATA") } to setOf("end", "lng", "lat", "param1", "param2", "param3"),
        )
        val offenders = cases.flatMap { (item, roots) ->
            val declared = Regex("""<(?:bind|foreach)\b[^>]*?(?:name|item|index)="([^"]+)"""")
                .findAll(item.sql).map { it.groupValues[1] }.toSet()
            SqlInspectionText.missingParameterFindings(item.sql, roots, declared)
                .map { "${item.name} → ${it.messageArgs[0]}" }
        }
        assertTrue("语料上参数规则必须 0 命中：\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    // ------------------------------------------------------------------
    // 规则 4（方言引号）：语料（MySQL 家族）无反引号/方括号 → MySQL 方言下 0 命中
    // ------------------------------------------------------------------

    @Test
    fun `P4_规则4_语料在MySQL方言下引号误报为0`() {
        val mysql = cn.song.mybatisplus.visualizer.sql.DialectRegistry.byName("MySQL")
        val offenders = corpus.flatMap { item ->
            SqlInspectionText.dialectQuoteFindings(item.sql, mysql.identifierQuotes, mysql.name)
                .map { "${item.name} → ${it.start}" }
        }
        assertTrue("语料在 MySQL 方言下必须 0 命中：\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    // ------------------------------------------------------------------
    // 反向验证：把规则判定摘掉，语料扫描必须变红
    // ------------------------------------------------------------------

    /**
     * 反向验证（判据要求「摘掉判定必红」）：语料里**植入**一个真问题，扫描必须命中。
     *
     * 与上面的 0 命中用例成对：只证明「不误报」而不证明「能报」的扫描是空绿，
     * 这个用例保证扫描面本身是活的。
     */
    @Test
    fun `反向验证_语料植入真问题后必须命中`() {
        val poisoned = corpus.first().sql + "\n and t.status = \${status}"
        val findings = SqlInspectionText.dollarFindings(
            poisoned,
            staticBindNames = setOf("orderCount", "chargeDegreeSum"),
        )
        assertEquals("植入的 ${'$'}{status} 必须被报出（否则扫描面是空的）", 1, findings.size)
        assertEquals("\${status}", findings.single().token)
    }

    @Test
    fun `反向验证_OGNL植入恒真条件后必须命中`() {
        val expression = "1==1"
        assertEquals("植入的恒真条件必须被报出", true, SqlInspectionText.constantTestTruth(expression))
    }

    /**
     * 语料转录时发现的**第一个非零命中**，如实登记（不是规则误报）。
     *
     * 清单 X01 的示例片段是节选：它贴了 `"${orderCount}"` 与 `"${chargeDegreeSum}"` 两处别名，
     * 但只贴了 `orderCount` 一条 `<bind>`。按该片段原文跑规则 → `chargeDegreeSum` 被报。
     * **这个报是对的**：没有对应 `<bind>` 的 `${name}` 在 MyBatis 运行期会抛
     * `There is no getter for property named 'chargeDegreeSum'`——它是「无来源裸替换」，
     * 比「列别名被误报」更严重。清单正文（「22 处 100% 是 bind 常量列别名」）也证实真实代码里
     * 每个 `${}` 都有对应 bind，节选只是省略了。
     *
     * 上一条 0 命中用例因此用**补齐 bind 的等价形态**做语料（见该条 note）。本用例把
     * 「节选原文会命中」这件事钉住，防止有人日后把补齐当成「为了让测试变绿」而随手改掉。
     */
    @Test
    fun `语料转录发现_节选缺失bind时规则命中且这是真问题`() {
        val abridged = """
            select count(t.id) "${'$'}{orderCount}",
                   TRIM_SCALE(COALESCE(sum(t.charge_degree),0)) "${'$'}{chargeDegreeSum}"
        """.trimIndent()
        val findings = SqlInspectionText.dollarFindings(abridged, staticBindNames = setOf("orderCount"))
        assertEquals("缺 bind 的 chargeDegreeSum 必须被报（运行期 unknown property）", 1, findings.size)
        assertEquals("\${chargeDegreeSum}", findings.single().token)
    }
}