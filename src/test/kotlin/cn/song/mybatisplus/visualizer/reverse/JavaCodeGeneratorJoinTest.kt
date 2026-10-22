package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.reverse.ReverseJoin
import cn.song.mybatisplus.visualizer.reverse.ReverseSet
import cn.song.mybatisplus.visualizer.reverse.ReverseStatementKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JavaCodeGenerator 的多表 JOIN / SELECT 列别名用例（自 JavaCodeGeneratorTest 拆出）
 * （T-M53-II 2026-10-10：逐字符原样搬运，零逻辑改动。）
 * （T-SyncK2JMaster482 C9b-1：Kotlin 调 Java 生产类，具名实参改位置实参；record 位置形参序照生产类。）
 */
class JavaCodeGeneratorJoinTest {

    // ------------------------------------------------------------------
    // 调用类特殊值（T-M42：this=service 内、baseMapper=ServiceImpl 字段）
    // ------------------------------------------------------------------

    /** SELECT 单表样本（条件 + 实体映射）。 */
    private fun specialValueSelectResult() = SqlReverseResult(
        "user",
        null,
        listOf(Condition.Simple(ColumnRef("name"), "=", SqlValue.Literal("'x'", "x"), null)),
        emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
    )

    @Test
    fun `this调用_SELECT生成list`() {
        val code = JavaCodeGenerator.generate(
            specialValueSelectResult(), null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("this"),
        )
        assertTrue(code, code.contains("List<User> list = this.list(wrapper);"))
    }

    @Test
    fun `this调用_分页生成page`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), LimitClause(SqlValue.Placeholder("?"), SqlValue.Placeholder("?")),
            emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("this"),
        )
        assertTrue(code, code.contains("Page<User> page = this.page(new Page<>(1, 10), wrapper);"))
    }

    @Test
    fun `this调用_UPDATE生成update`() {
        val result = SqlReverseResult(
            "user", null,
            listOf(Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"), null)),
            emptyList(), null, emptyList(), ReverseStatementKind.UPDATE,
            listOf(ReverseSet("name", SqlValue.Literal("'x'", "x"), null)),
            emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("this"),
        )
        assertTrue(code, code.contains("this.update(wrapper);"))
    }

    @Test
    fun `this调用_DELETE生成remove`() {
        val result = SqlReverseResult(
            "user", null,
            listOf(Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"), null)),
            emptyList(), null, emptyList(), ReverseStatementKind.DELETE,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("this"),
        )
        assertTrue(code, code.contains("this.remove(wrapper);"))
    }

    @Test
    fun `this调用_INSERT单行生成save`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), null, emptyList(), ReverseStatementKind.INSERT,
            emptyList(), listOf("name"), listOf(listOf(SqlValue.Literal("'张三'", "张三"))),
            emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("this"),
        )
        assertTrue(code, code.contains("User user = new User().setName(\"张三\");"))
        assertTrue(code, code.contains("this.save(user);"))
    }

    @Test
    fun `this调用_INSERT多行生成saveBatch`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), null, emptyList(), ReverseStatementKind.INSERT,
            emptyList(), listOf("name"),
            listOf(
                listOf(SqlValue.Literal("'a'", "a")),
                listOf(SqlValue.Literal("'b'", "b")),
            ),
            emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("this"),
        )
        assertTrue(code, code.contains("this.saveBatch(Arrays.asList(user1, user2));"))
    }

    @Test
    fun `baseMapper调用_SELECT生成selectList`() {
        val code = JavaCodeGenerator.generate(
            specialValueSelectResult(), null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("baseMapper"),
        )
        assertTrue(code, code.contains("List<User> list = baseMapper.selectList(wrapper);"))
    }

    @Test
    fun `baseMapper调用_分页生成selectPage`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), LimitClause(null, SqlValue.Literal("10")),
            emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("baseMapper"),
        )
        assertTrue(code, code.contains("Page<User> page = baseMapper.selectPage(new Page<>(1, 10), wrapper);"))
    }

    @Test
    fun `baseMapper调用_UPDATE生成update`() {
        val result = SqlReverseResult(
            "user", null,
            listOf(Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"), null)),
            emptyList(), null, emptyList(), ReverseStatementKind.UPDATE,
            listOf(ReverseSet("name", SqlValue.Literal("'x'", "x"), null)),
            emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("baseMapper"),
        )
        assertTrue(code, code.contains("baseMapper.update(null, wrapper);"))
    }

    @Test
    fun `baseMapper调用_DELETE生成delete`() {
        val result = SqlReverseResult(
            "user", null,
            listOf(Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"), null)),
            emptyList(), null, emptyList(), ReverseStatementKind.DELETE,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("baseMapper"),
        )
        assertTrue(code, code.contains("baseMapper.delete(wrapper);"))
    }

    @Test
    fun `baseMapper调用_INSERT单行生成insert`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), null, emptyList(), ReverseStatementKind.INSERT,
            emptyList(), listOf("name"), listOf(listOf(SqlValue.Literal("'张三'", "张三"))),
            emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("baseMapper"),
        )
        assertTrue(code, code.contains("baseMapper.insert(user);"))
    }

    @Test
    fun `特殊调用值_多表JOIN不报错生成普通调用`() {
        // mpj=true 放开（invocationHasMpj 对特殊值直出 true）——JOIN 场景不再报「无 MPJ 能力」，
        // 单表形态方法（list/selectList）正常生成
        val result = SqlReverseResult(
            "user", null,
            listOf(
                Condition.Simple(ColumnRef("t", "status"), "=", SqlValue.Literal("1"), null),
            ),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(
                ReverseJoin(
                    cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    "address",
                    "t1",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("t1", "user_id"),
                            ColumnRef("t", "id"),
                            null,
                        ),
                    ),
                    null, null,
                ),
            ),
            emptyList(),
        )
        val main = JavaCodeGenerator.EntityTarget(
            "User",
            mapOf("id" to "id", "status" to "status"),
            mapOf(
                "t1" to JavaCodeGenerator.EntityTarget(
                    "Address",
                    mapOf("user_id" to "userId"),
                ),
            ),
        )
        val thisCode = JavaCodeGenerator.generate(
            result, main, GeneratedWrapper.MPJ_LAMBDA,
            JavaCodeGenerator.InvocationTarget("this", null, true),
        )
        assertTrue(thisCode, thisCode.contains("List<User> list = this.list(wrapper);"))
        val mapperCode = JavaCodeGenerator.generate(
            result, main, GeneratedWrapper.MPJ_LAMBDA,
            JavaCodeGenerator.InvocationTarget("baseMapper", null, true),
        )
        assertTrue(mapperCode, mapperCode.contains("List<User> list = baseMapper.selectList(wrapper);"))
    }

    @Test
    fun `特殊调用值_VO查询生成selectJoinList`() {
        // VO 查询（mpj=true）：this/baseMapper 均生成 MPJ 的 selectJoinList(Vo.class, wrapper)
        val result = specialValueSelectResult()
        val thisCode = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("this", "UserVo", true),
        )
        assertTrue(thisCode, thisCode.contains("List<UserVo> list = this.selectJoinList(UserVo.class, wrapper);"))
        val mapperCode = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("baseMapper", "UserVo", true),
        )
        assertTrue(mapperCode, mapperCode.contains("List<UserVo> list = baseMapper.selectJoinList(UserVo.class, wrapper);"))
    }

    // ------------------------------------------------------------------
    // 多表 JOIN（T-M37）
    // ------------------------------------------------------------------

    @Test
    fun `多表JOIN_生成MPJLambdaWrapper与三参leftJoin`() {
        val result = SqlReverseResult(
            "user", null,
            listOf(
                Condition.Simple(ColumnRef("t", "status"), "=", SqlValue.Literal("1"), null),
                Condition.Simple(ColumnRef("t1", "city"), "=", SqlValue.Literal("'北京'", "北京"), null),
            ),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(
                ReverseJoin(
                    cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    "address",
                    "t1",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("t1", "user_id"),
                            ColumnRef("t", "id"),
                            null,
                        ),
                    ),
                    null, null,
                ),
            ),
            emptyList(),
        )
        val main = JavaCodeGenerator.EntityTarget(
            "User",
            mapOf("id" to "id", "status" to "status"),
            mapOf(
                "t1" to JavaCodeGenerator.EntityTarget(
                    "Address",
                    mapOf("user_id" to "userId", "city" to "city"),
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, null)
        assertTrue(code, code.contains("new MPJLambdaWrapper<User>()"))
        assertTrue(code, code.contains(".selectAll(User.class)"))
        assertTrue(code, code.contains(".leftJoin(Address.class, Address::getUserId, User::getId)"))
        assertTrue(code, code.contains(".eq(User::getStatus, 1)"))
        assertTrue(code, code.contains(".eq(Address::getCity, \"北京\")"))
    }

    @Test
    fun `MPJ括号ON与WHERE括号_全链还原lambda形态平铺条件`() {
        // T-ReverseParenWira 用户实录：MPJ 渲染 ON/WHERE 自带括号
        // `ON (st.station_id = t.id)` / `WHERE (t.id = 1)`，反向必须还原
        // MPJLambdaWrapper + 三参 lambda leftJoin + 平铺 eq（单条件括号已在解析器解包）
        val result = SqlReverseParser.INSTANCE.parse(
            "SELECT t.id,t.station_id,t.share_scheme_id,t.device_name,st.station_name " +
                "FROM car_device_info t LEFT JOIN station_info st ON (st.station_id = t.id) WHERE (t.id = 1)",
        )
        val main = JavaCodeGenerator.EntityTarget(
            "DeviceInfoEntity",
            mapOf(
                "id" to "id",
                "station_id" to "stationId",
                "share_scheme_id" to "shareSchemeId",
                "device_name" to "deviceName",
            ),
            mapOf(
                "st" to JavaCodeGenerator.EntityTarget(
                    "StationInfoEntity",
                    mapOf("station_id" to "stationId", "station_name" to "stationName"),
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, null)
        assertTrue(code, code.contains("new MPJLambdaWrapper<DeviceInfoEntity>()"))
        assertTrue(
            code,
            code.contains(".leftJoin(StationInfoEntity.class, StationInfoEntity::getStationId, DeviceInfoEntity::getId)"),
        )
        assertTrue(code, code.contains(".eq(DeviceInfoEntity::getId, 1)"))
        assertTrue(code, code.contains(".select(DeviceInfoEntity::getId)"))
        assertTrue(code, code.contains(".select(StationInfoEntity::getStationName)"))
        assertTrue(code, !code.contains("nested("))
        assertTrue(code, !code.contains("MPJQueryWrapper"))
    }

    @Test
    fun `多表JOIN_ON多条件_回落raw join字符串`() {
        val result = SqlReverseResult(
            "a", null, emptyList(),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(
                ReverseJoin(
                    cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    "b",
                    "t1",
                    listOf(
                        Condition.ColumnComparison(ColumnRef("t1", "a_id"), ColumnRef("t", "id"), null),
                        Condition.Simple(ColumnRef("t1", "type"), "=", SqlValue.Literal("1"), null),
                    ),
                    "t1.a_id = t.id AND t1.type = 1",
                    null,
                ),
            ),
            emptyList(),
        )
        val main = JavaCodeGenerator.EntityTarget(
            "A",
            mapOf("id" to "id"),
            mapOf("t1" to JavaCodeGenerator.EntityTarget("B", mapOf("a_id" to "aId", "type" to "type"))),
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, null)
        // ON 多条件（join key + ext 条件）→ 4 参 lambda leftJoin(Class, left, right, ext -> ext.eq(...))
        assertTrue(code, code.contains("new MPJLambdaWrapper<A>()"))
        assertTrue(code, code.contains(".leftJoin(B.class, B::getAId, A::getId,"))
        assertTrue(code, code.contains("ext -> ext.eq(B::getType, 1)"))
    }

    @Test
    fun `多表JOIN_主表无实体生成MPJQueryWrapper`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(
                ReverseJoin(
                    cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    "address",
                    "t1",
                    listOf(
                        Condition.ColumnComparison(ColumnRef("t1", "user_id"), ColumnRef("t", "id"), null),
                    ),
                    "t1.user_id = t.id",
                    null,
                ),
            ),
            emptyList(),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.MPJ_QUERY, null)
        assertTrue(code, code.contains("new MPJQueryWrapper<>(User.class)"))
        assertTrue(code, code.contains(".selectAll(User.class)"))
        assertTrue(code, code.contains(".leftJoin(\"address t1 ON t1.user_id = t.id\")"))
    }

    @Test
    fun `多表JOIN_未映射副表列回落带前缀字符串`() {
        val result = SqlReverseResult(
            "user", null,
            listOf(
                Condition.Simple(ColumnRef("t1", "extra_col"), "=", SqlValue.Literal("1"), null),
            ),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(
                ReverseJoin(
                    cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    "address",
                    "t1",
                    listOf(
                        Condition.ColumnComparison(ColumnRef("t1", "user_id"), ColumnRef("t", "id"), null),
                    ),
                    null, null,
                ),
            ),
            emptyList(),
        )
        val main = JavaCodeGenerator.EntityTarget(
            "User",
            mapOf("id" to "id"),
            mapOf("t1" to JavaCodeGenerator.EntityTarget("Address", mapOf("user_id" to "userId"))),
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, null)
        assertTrue(code, code.contains(".eq(\"t1.extra_col\", 1)"))
    }

    @Test
    fun `多表JOIN_ON含副表逻辑删除_生成三参leftJoin`() {
        // 用户场景：ON 里带 MPJ 自动追加的副表 deleted = 0，仍应三参 lambda 化（MPJ 自动补，不当多条件拦截）
        val result = SqlReverseResult(
            "biz_bike_device_info", null, emptyList(),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(
                ReverseJoin(
                    cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    "biz_bike_device_product_info",
                    "bikeDeviceProductInfoEntity",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("bikeDeviceProductInfoEntity", "id"),
                            ColumnRef("t", "product_id"),
                            null,
                        ),
                        Condition.Simple(
                            ColumnRef("bikeDeviceProductInfoEntity", "deleted"),
                            "=", SqlValue.Literal("0", "0"), null,
                        ),
                    ),
                    null, null,
                ),
            ),
            emptyList(),
        )
        val main = JavaCodeGenerator.EntityTarget(
            "BikeDeviceInfoEntity",
            mapOf("id" to "id", "product_id" to "productId"),
            mapOf(
                "bikeDeviceProductInfoEntity" to JavaCodeGenerator.EntityTarget(
                    "BikeDeviceProductInfoEntity",
                    mapOf("id" to "id", "deleted" to "deleted"),
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, null)
        assertTrue(code, code.contains("new MPJLambdaWrapper<BikeDeviceInfoEntity>()"))
        assertTrue(
            code,
            code.contains(
                ".leftJoin(BikeDeviceProductInfoEntity.class, BikeDeviceProductInfoEntity::getId, BikeDeviceInfoEntity::getProductId)",
            ),
        )
    }

    @Test
    fun `多表JOIN_字符串形态_多行ON换行折叠转义`() {
        val result = SqlReverseResult(
            "a", null, emptyList(),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(
                ReverseJoin(
                    cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    "b",
                    "t1",
                    listOf(
                        Condition.ColumnComparison(ColumnRef("t1", "a_id"), ColumnRef("t", "id"), null),
                        Condition.Simple(ColumnRef("t1", "type"), "=", SqlValue.Literal("1", "1"), null),
                    ),
                    "t1.a_id = t.id\n    AND t1.type = 1",
                    null,
                ),
            ),
            emptyList(),
        )
        val main = JavaCodeGenerator.EntityTarget(
            "A",
            mapOf("id" to "id"),
            mapOf("t1" to JavaCodeGenerator.EntityTarget("B", mapOf("a_id" to "aId", "type" to "type"))),
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, null)
        // 真过滤条件（type=1 非逻辑删除）→ 4 参 lambda leftJoin(Class, left, right, ext -> ext.eq(...))
        assertTrue(code, code.contains("new MPJLambdaWrapper<A>()"))
        assertTrue(code, code.contains(".leftJoin(B.class, B::getAId, A::getId,"))
        assertTrue(code, code.contains("ext -> ext.eq(B::getType, 1)"))
    }

    @Test
    fun `SELECT列带别名_生成selectAs`() {
        val result = SqlReverseResult(
            "user",
            listOf(SelectColumn("t.name", "productName"), SelectColumn("id", null)),
            emptyList(), emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result, JavaCodeGenerator.EntityTarget("User", mapOf("id" to "id", "name" to "name")), GeneratedWrapper.LAMBDA, null)
        // MP core 无 selectAs 重载（javap 3.5.14/3.5.17 实证），带别名投影整链回落字符串 select
        assertTrue(code, code.contains(".select(\"t.name AS productName\", \"id\")"))
        assertFalse("MP core 无 selectAs 重载，生成物不得引用", code.contains("selectAs"))
    }

    @Test
    fun `SELECT列_主表别名下副表列_归属副实体`() {
        // 用户场景：t.model 主实体无该列，仅产品副实体有 → 归属副实体；主表有 name → 仍归属主实体（SQL 字面正确）
        val result = SqlReverseResult(
            "biz_bike_device_info",
            listOf(SelectColumn("t.name", "productName"), SelectColumn("t.model", "productModel")),
            emptyList(), emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(
                ReverseJoin(
                    cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    "biz_bike_device_product_info",
                    "bikeDeviceProductInfoEntity",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("bikeDeviceProductInfoEntity", "id"),
                            ColumnRef("t", "product_id"),
                            null,
                        ),
                    ),
                    null, null,
                ),
            ),
            emptyList(),
        )
        val main = JavaCodeGenerator.EntityTarget(
            "BikeDeviceInfoEntity",
            mapOf("id" to "id", "product_id" to "productId", "name" to "name"),
            mapOf(
                "bikeDeviceProductInfoEntity" to JavaCodeGenerator.EntityTarget(
                    "BikeDeviceProductInfoEntity",
                    mapOf("id" to "id", "model" to "model"),
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, null)
        assertTrue(code, code.contains(".selectAs(BikeDeviceInfoEntity::getName, \"productName\")"))
        assertTrue(code, code.contains(".selectAs(BikeDeviceProductInfoEntity::getModel, \"productModel\")"))
        // t.model 已归属副实体 → 不再误报「未在实体中找到对应字段」
        assertTrue(code, !code.contains("已回落字符串列名"))
        assertTrue(code, !code.contains("t.model"))
    }

    @Test
    fun `SELECT列_选中VO_selectAs引用VO方法`() {
        // 用户场景：选了调用类 + VO 后，selectAs 第二参应为 VO 属性方法引用（对齐正向 selectAs(列, VO::getXxx)）
        val result = SqlReverseResult(
            "biz_bike_device_info",
            listOf(SelectColumn("t.name", "productName"), SelectColumn("t.model", "productModel")),
            emptyList(), emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(
                ReverseJoin(
                    cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    "biz_bike_device_product_info",
                    "bikeDeviceProductInfoEntity",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("bikeDeviceProductInfoEntity", "id"),
                            ColumnRef("t", "product_id"),
                            null,
                        ),
                    ),
                    null, null,
                ),
            ),
            emptyList(),
        )
        val main = JavaCodeGenerator.EntityTarget(
            "BikeDeviceInfoEntity",
            mapOf("id" to "id", "product_id" to "productId", "name" to "name"),
            mapOf(
                "bikeDeviceProductInfoEntity" to JavaCodeGenerator.EntityTarget(
                    "BikeDeviceProductInfoEntity",
                    mapOf("id" to "id", "model" to "model"),
                ),
            ),
        )
        val invocation = JavaCodeGenerator.InvocationTarget(
            "BikeDeviceInfoService",
            "BikeDeviceInfoVO",
            true,
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, invocation)
        assertTrue(code, code.contains(".selectAs(BikeDeviceInfoEntity::getName, BikeDeviceInfoVO::getProductName)"))
        assertTrue(code, code.contains(".selectAs(BikeDeviceProductInfoEntity::getModel, BikeDeviceInfoVO::getProductModel)"))
        // VO 查询调用形态（selectJoinList 族）
        assertTrue(code, code.contains("bikeDeviceInfoService.selectJoinList(BikeDeviceInfoVO.class, wrapper)"))
        // 选 VO 后不再用字符串别名（无 VO 时字符串别名行为由 SELECT列带别名_生成selectAs 覆盖）
        assertTrue(code, !code.contains("BikeDeviceInfoVO::getProductName\"") && !code.contains("\"productName\""))
    }

    @Test
    fun `多表JOIN_调用类有MPJ能力_不报错生成selectJoinList`() {
        // 端到端：调用类具备 MPJ 能力（mpj=true）时，多表 JOIN 不应抛「无 MPJ 能力」，应生成 selectJoinList 族
        val result = SqlReverseResult(
            "biz_bike_device_info", null, emptyList(),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(
                ReverseJoin(
                    cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    "biz_bike_device_product_info",
                    "bikeDeviceProductInfoEntity",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("bikeDeviceProductInfoEntity", "id"),
                            ColumnRef("t", "product_id"),
                            null,
                        ),
                    ),
                    null, null,
                ),
            ),
            emptyList(),
        )
        val main = JavaCodeGenerator.EntityTarget(
            "BikeDeviceInfoEntity",
            mapOf("id" to "id", "product_id" to "productId"),
            mapOf(
                "bikeDeviceProductInfoEntity" to JavaCodeGenerator.EntityTarget(
                    "BikeDeviceProductInfoEntity",
                    mapOf("id" to "id"),
                ),
            ),
        )
        val invocation = JavaCodeGenerator.InvocationTarget(
            "BikeDeviceInfoService",
            "BikeDeviceInfoVO",
            true,
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, invocation)
        assertTrue(code, code.contains("new MPJLambdaWrapper<BikeDeviceInfoEntity>()"))
        assertTrue(code, code.contains(".leftJoin(BikeDeviceProductInfoEntity.class, BikeDeviceProductInfoEntity::getId, BikeDeviceInfoEntity::getProductId)"))
        assertTrue(code, code.contains("bikeDeviceInfoService.selectJoinList(BikeDeviceInfoVO.class, wrapper)"))
    }

    @Test
    fun `多表JOIN_仅选VO_生成selectAs引用VO方法且不生成调用行`() {
        // T-M50：只选 VO 不选调用类 -> selectAs 第二参引用 VO 属性方法，不生成调用行
        val result = SqlReverseResult(
            "biz_bike_device_info",
            listOf(SelectColumn("t.name", "productName"), SelectColumn("t.model", "productModel")),
            emptyList(), emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(
                ReverseJoin(
                    cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    "biz_bike_device_product_info",
                    "bikeDeviceProductInfoEntity",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("bikeDeviceProductInfoEntity", "id"),
                            ColumnRef("t", "product_id"),
                            null,
                        ),
                    ),
                    null, null,
                ),
            ),
            emptyList(),
        )
        val main = JavaCodeGenerator.EntityTarget(
            "BikeDeviceInfoEntity",
            mapOf("id" to "id", "product_id" to "productId", "name" to "name"),
            mapOf(
                "bikeDeviceProductInfoEntity" to JavaCodeGenerator.EntityTarget(
                    "BikeDeviceProductInfoEntity",
                    mapOf("id" to "id", "model" to "model"),
                ),
            ),
        )
        // 仅选 VO，无调用类（callerClass 为空）
        val voOnly = JavaCodeGenerator.InvocationTarget("", "BikeDeviceInfoVO", true)
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, voOnly)
        // lambda 形态 + VO 方法引用
        assertTrue(code, code.contains("new MPJLambdaWrapper<BikeDeviceInfoEntity>()"))
        assertTrue(code, code.contains(".selectAs(BikeDeviceInfoEntity::getName, BikeDeviceInfoVO::getProductName)"))
        assertTrue(code, code.contains(".selectAs(BikeDeviceProductInfoEntity::getModel, BikeDeviceInfoVO::getProductModel)"))
        // 无调用行（无调用类）
        assertTrue(code, !code.contains("bikeDeviceInfoService."))
        assertTrue(code, !code.contains("selectJoinList"))
    }

    @Test
    fun `多表JOIN_ON含逻辑删除_生成lambda形态selectAs引用VO`() {
        // isSubLogicalDelete 过滤 ON 里副表 deleted = 0 → 可 lambda 化 → MPJLambdaWrapper。
        // selectAs 第二参引用 VO 属性方法（对齐正向 selectAs(Sub::getX, VO::getY)）
        val result = SqlReverseResult(
            "biz_bike_device_info",
            listOf(SelectColumn("t.name", "productName"), SelectColumn("t.model", "productModel")),
            emptyList(), emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(
                ReverseJoin(
                    cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    "biz_bike_device_product_info",
                    "bikeDeviceProductInfoEntity",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("bikeDeviceProductInfoEntity", "id"),
                            ColumnRef("t", "product_id"),
                            null,
                        ),
                        // ON 额外条件 deleted = 0 被 isSubLogicalDelete 过滤 → 可 lambda 化
                        Condition.Simple(ColumnRef("bikeDeviceProductInfoEntity", "deleted"), "=", SqlValue.Literal("0", "0"), null),
                    ),
                    null, null,
                ),
            ),
            emptyList(),
        )
        val main = JavaCodeGenerator.EntityTarget(
            "BikeDeviceInfoEntity",
            mapOf("id" to "id", "product_id" to "productId", "name" to "name"),
            mapOf(
                "bikeDeviceProductInfoEntity" to JavaCodeGenerator.EntityTarget(
                    "BikeDeviceProductInfoEntity",
                    mapOf("id" to "id", "model" to "model", "deleted" to "deleted"),
                ),
            ),
        )
        val invocation = JavaCodeGenerator.InvocationTarget(
            "BikeDeviceInfoService",
            "BikeDeviceInfoVO",
            true,
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, invocation)
        // lambda 联接形态（deleted = 0 被过滤 → 可 lambda 化）
        assertTrue(code, code.contains("new MPJLambdaWrapper<BikeDeviceInfoEntity>()"))
        assertTrue(code, code.contains(".selectAs(BikeDeviceInfoEntity::getName, BikeDeviceInfoVO::getProductName)"))
        assertTrue(code, code.contains(".selectAs(BikeDeviceProductInfoEntity::getModel, BikeDeviceInfoVO::getProductModel)"))
        assertTrue(code, code.contains(".leftJoin(BikeDeviceProductInfoEntity.class, BikeDeviceProductInfoEntity::getId, BikeDeviceInfoEntity::getProductId)"))
        assertTrue(code, code.contains("bikeDeviceInfoService.selectJoinList(BikeDeviceInfoVO.class, wrapper)"))
    }

    @Test
    fun `多表JOIN_字符串形态_非逻辑删除条件_别名列渲染为selectAS别名`() {
        // T-M48：ON 含非逻辑删除额外条件（type = 1）→ 无法 lambda 化 → MPJQueryWrapper（字符串联接）。
        // MPJQueryWrapper 无 selectAs(String, SFunction) 重载，别名列应渲染为 .select("col AS alias") 字符串形式
        val result = SqlReverseResult(
            "biz_bike_device_info",
            listOf(SelectColumn("t.name", "productName"), SelectColumn("t.model", "productModel")),
            emptyList(), emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(
                ReverseJoin(
                    cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    "biz_bike_device_product_info",
                    "bikeDeviceProductInfoEntity",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("bikeDeviceProductInfoEntity", "id"),
                            ColumnRef("t", "product_id"),
                            null,
                        ),
                        // ON 额外条件 type = 1（非逻辑删除）→ 挡住 lambda 化 → 回落 MPJQueryWrapper
                        Condition.Simple(ColumnRef("bikeDeviceProductInfoEntity", "type"), "=", SqlValue.Literal("1", "1"), null),
                    ),
                    null, null,
                ),
            ),
            emptyList(),
        )
        val main = JavaCodeGenerator.EntityTarget(
            "BikeDeviceInfoEntity",
            mapOf("id" to "id", "product_id" to "productId", "name" to "name"),
            mapOf(
                "bikeDeviceProductInfoEntity" to JavaCodeGenerator.EntityTarget(
                    "BikeDeviceProductInfoEntity",
                    mapOf("id" to "id", "model" to "model", "type" to "type"),
                ),
            ),
        )
        val invocation = JavaCodeGenerator.InvocationTarget(
            "BikeDeviceInfoService",
            "BikeDeviceInfoVO",
            true,
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, invocation)
        // lambda 形态：VO 查询 + 4 参 lambda join
        assertTrue(code, code.contains("new MPJLambdaWrapper<BikeDeviceInfoEntity>()"))
        assertTrue(code, code.contains(".selectAs(BikeDeviceInfoEntity::getName, BikeDeviceInfoVO::getProductName)"))
        assertTrue(code, code.contains(".selectAs(BikeDeviceProductInfoEntity::getModel, BikeDeviceInfoVO::getProductModel)"))
        // 4 参 lambda leftJoin
        assertTrue(code, code.contains(".leftJoin(BikeDeviceProductInfoEntity.class, BikeDeviceProductInfoEntity::getId, BikeDeviceInfoEntity::getProductId,"))
        assertTrue(code, code.contains("ext -> ext.eq(BikeDeviceProductInfoEntity::getType, 1)"))
        // 不可出现非法的 selectAs(String, SFunction)
        assertTrue(code, !code.contains("selectAs(\"t.name\""))
        assertTrue(code, !code.contains("selectAs(\"t.model\""))
    }

    @Test
    fun `SELECT列_多副实体同名列_不归属回落字符串`() {
        // name 在多个副实体都有 → singleOrNull 判歧义 → 回落字符串（不误判归属）
        val result = SqlReverseResult(
            "biz_bike_device_info",
            listOf(SelectColumn("t.model", null)),
            emptyList(), emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(
                ReverseJoin(
                    cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    "biz_bike_device_product_info",
                    "p",
                    listOf(
                        Condition.ColumnComparison(ColumnRef("p", "id"), ColumnRef("t", "product_id"), null),
                    ),
                    null, null,
                ),
            ),
            emptyList(),
        )
        val main = JavaCodeGenerator.EntityTarget(
            "BikeDeviceInfoEntity",
            mapOf("id" to "id", "product_id" to "productId"),
            mapOf(
                "p" to JavaCodeGenerator.EntityTarget("BikeDeviceProductInfoEntity", mapOf("id" to "id", "model" to "model")),
                "s" to JavaCodeGenerator.EntityTarget("BikeStationInfoEntity", mapOf("id" to "id", "model" to "model")),
            ),
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, null)
        assertTrue(code, code.contains(".select(\"t.model\")"))
    }

    // ------------------------------------------------------------------
    // ? 参数占位符（P0+P1：WHERE/SET/INSERT/LIMIT）
    // ------------------------------------------------------------------

    @Test
    fun `占位符条件_渲染eq占位与头注释`() {
        val result = SqlReverseResult(
            "sys_account_role_rel",
            listOf(SelectColumn("id", null), SelectColumn("account_id", null), SelectColumn("role_id", null)),
            listOf(
                Condition.Simple(ColumnRef("role_id"), "=", SqlValue.Placeholder("?"), null),
                Condition.Simple(ColumnRef("status"), ">", SqlValue.Placeholder("?"), null),
                Condition.In(ColumnRef("id"), false, listOf(SqlValue.Placeholder("?"), SqlValue.Placeholder("?")), null),
            ),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY, null)
        assertTrue(code, code.contains(".eq(\"role_id\", \"?\")"))
        assertTrue(code, code.contains(".gt(\"status\", \"?\")"))
        assertTrue(code, code.contains(".in(\"id\", \"?\", \"?\")"))
        assertTrue(code, code.contains("// 注意：SQL 含参数占位符 ?，无法恢复实际参数，已生成 \"?\" 占位，请替换为实际参数"))
    }

    @Test
    fun `占位符_LIKE渲染like与BETWEEN渲染between`() {
        val result = SqlReverseResult(
            "user",
            null,
            listOf(
                Condition.Like(ColumnRef("name"), false, SqlValue.Placeholder("?"), LikePosition.NONE, null),
                Condition.Between(ColumnRef("age"), false, SqlValue.Placeholder("?"), SqlValue.Placeholder("?"), null),
            ),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY, null)
        assertTrue(code, code.contains(".like(\"name\", \"?\")"))
        assertTrue(code, code.contains(".between(\"age\", \"?\", \"?\")"))
    }

    @Test
    fun `占位符_UPDATE_SET渲染set`() {
        val result = SqlReverseResult(
            "user", null,
            listOf(Condition.Simple(ColumnRef("id"), "=", SqlValue.Placeholder("?"), null)),
            emptyList(), null, emptyList(), ReverseStatementKind.UPDATE,
            listOf(ReverseSet("name", SqlValue.Placeholder("?"), null)),
            emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY, null)
        assertTrue(code, code.contains(".set(\"name\", \"?\")"))
        assertTrue(code, code.contains(".eq(\"id\", \"?\")"))
    }

    @Test
    fun `占位符_INSERT渲染实体setter`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), null, emptyList(), ReverseStatementKind.INSERT,
            emptyList(), listOf("name", "age"),
            listOf(listOf(SqlValue.Placeholder("?"), SqlValue.Placeholder("?"))),
            emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY, null)
        assertTrue(code, code.contains("new User().setName(\"?\")"))
        assertTrue(code, code.contains(".setAge(\"?\")"))
    }

    @Test
    fun `占位符_LIMIT渲染last保留偏移`() {
        val result = SqlReverseResult(
            "user", null,
            listOf(Condition.Simple(ColumnRef("role_id"), "=", SqlValue.Placeholder("?"), null)),
            emptyList(), LimitClause(SqlValue.Placeholder("?"), SqlValue.Placeholder("?")),
            emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY, null)
        assertTrue(code, code.contains(".last(\"LIMIT ?, ?\")"))
        assertTrue(code, code.contains(".eq(\"role_id\", \"?\")"))
    }

    @Test
    fun `无占位符_不产生占位注释`() {
        val result = SqlReverseResult(
            "user", null,
            listOf(Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1", "1"), null)),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY, null)
        assertTrue(code, !code.contains("参数占位符"))
    }

    // ------------------------------------------------------------------
    // SELECT 调用形态（分页 page / VO 同名回落）
    // ------------------------------------------------------------------

    @Test
    fun `SELECT分页带Service_生成page调用`() {
        val result = SqlReverseResult(
            "user",
            null,
            listOf(Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1", "1"), null)),
            emptyList(), LimitClause(SqlValue.Placeholder("?"), SqlValue.Placeholder("?")),
            emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService"),
        )
        assertTrue(code, code.contains("Page<User> page = userService.page(new Page<>(1, 10), wrapper);"))
        // T-M45：说明注释为独立行注释（不再行尾注释）——独立行位于调用行上方
        assertTrue(code, code.contains("// 分页 current/size 已占位，请替换为实际参数\nPage<User> page"))
        assertTrue(code, !code.contains("wrapper);  //"))
        assertTrue(code, !code.contains(".last(\"LIMIT"))
    }

    @Test
    fun `SELECT分页带Mapper_生成selectPage`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), LimitClause(null, SqlValue.Literal("10")),
            emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper"),
        )
        assertTrue(code, code.contains("Page<User> page = userMapper.selectPage(new Page<>(1, 10), wrapper);"))
    }

    @Test
    fun `SELECT分页字面量_推断current`() {
        val paged = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), LimitClause(SqlValue.Literal("20"), SqlValue.Literal("10")),
            emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            paged, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService"),
        )
        assertTrue(code, code.contains("new Page<>(3, 10)"))
        // 无调用类（纯 wrapper 表达式）→ 保持 last 形态不变
        val plain = JavaCodeGenerator.generate(paged, null, GeneratedWrapper.QUERY, null)
        assertTrue(plain, plain.contains(".last(\"LIMIT 20, 10\")"))
    }

    @Test
    fun `VO与实体同名_回落普通list调用`() {
        val result = SqlReverseResult(
            "user", null, emptyList(), emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService", "User", false),
        )
        assertTrue(code, code.contains("List<User> list = userService.list(wrapper);"))
        assertTrue(code, !code.contains("listVo"))
    }

    @Test
    fun `分页VO组合_生成selectJoinListPage`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), LimitClause(SqlValue.Placeholder("?"), SqlValue.Placeholder("?")),
            emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService", "UserVo", true),
        )
        // MPJ 1.5.6 实证：pageVo 不存在，真实方法 = selectJoinListPage(page, Vo.class, wrapper)
        assertTrue(code, code.contains("Page<UserVo> page = userService.selectJoinListPage(new Page<>(1, 10), UserVo.class, wrapper);"))
        assertTrue(code, code.contains("// VO 查询需 MyBatis-Plus-Join（MPJ）依赖"))
    }
}
