package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-15（DBX #319）空间二进制反解内核判别测试。
 *
 * 夹具全部手造合法 EWKB 字节（PostGIS EWKB 标准（二进制布局），非真库采样——
 * 本环境无 SQL Server/PostGIS 实例，真库证据边界登记在计划 §实施记录）：
 * - 小端 POINT(1 2) SRID 4326：`01 01000020 E6100000 <1.0 LE> <2.0 LE>`
 * - 大端 POINT(1 2) 无 SRID：`00 00000001 <1.0 BE> <2.0 BE>`
 * - LINESTRING / POLYGON / Z 维对齐 / 反例族（非十六进制、短串、坏类型、尾余量）。
 *
 * 判别红线：反解分支摘除（LogSqlRestorer.renderParam 的 SpatialLiteral.render 调用）
 * → 本类空间接线用例恰红、反例用例不受影响。
 */
class SpatialLiteralTest {

    // ---------- 内核：hex 解析 ----------

    @Test
    fun `hex解析_0x前缀大小写与奇偶长度`() {
        assertArrayEquals(byteArrayOf(0x01.toByte(), 0xAB.toByte()), SpatialLiteral.parseHexBytes("0x01AB"))
        assertArrayEquals(byteArrayOf(0x01.toByte(), 0xAB.toByte()), SpatialLiteral.parseHexBytes("0X01ab"))
        assertNull("裸十六进制无 0x 前缀不收（字符串列误判防线）", SpatialLiteral.parseHexBytes("deadbeef"))
        assertNull("空体不收", SpatialLiteral.parseHexBytes("0x"))
        assertNull("奇数长度不收", SpatialLiteral.parseHexBytes("0x123"))
        assertNull("非十六进制字符不收", SpatialLiteral.parseHexBytes("0xZZ"))
    }

    // ---------- 内核：EWKB 反解 ----------

    /** 手造小端 EWKB POINT(1 2) SRID 4326（PostGIS EWKB 标准布局；0x 前缀=内核准入门槛）。 */
    private val point4326Le = "0x0101000020E6100000" +
        "000000000000F03F" + // x = 1.0 (little endian IEEE754)
        "0000000000000040" // y = 2.0

    @Test
    fun `POINT小端带SRID_反解为STGeomFromText`() {
        assertEquals(
            "geometry::STGeomFromText('POINT (1.0 2.0)', 4326)",
            SpatialLiteral.render(point4326Le),
        )
    }

    @Test
    fun `POINT大端无SRID_反解SRID默认0`() {
        val bigEndian = "0x00" + "00000001" +
            "3FF0000000000000" + // 1.0 BE
            "4000000000000000" // 2.0 BE
        assertEquals(
            "geometry::STGeomFromText('POINT (1.0 2.0)', 0)",
            SpatialLiteral.render(bigEndian),
        )
    }

    @Test
    fun `LINESTRING两点_反解保序`() {
        val hex = "0x01" + "02000020" + "00000000" + // LE 类型 LINESTRING|SRID位 + SRID 0
            "02000000" + // 点数 2
            "000000000000F03F" + "0000000000000040" + // (1.0, 2.0)
            "0000000000000840" + "0000000000001040" // (3.0, 4.0)
        assertEquals(
            "geometry::STGeomFromText('LINESTRING (1.0 2.0, 3.0 4.0)', 0)",
            SpatialLiteral.render(hex),
        )
    }

    @Test
    fun `POLYGON单环四点_反解保环`() {
        val hex = "0x01" + "03000000" + // LE 类型 POLYGON，无 SRID
            "01000000" + // 环数 1
            "04000000" + // 点数 4
            "0000000000000000" + "0000000000000000" + // (0,0)
            "0000000000000040" + "0000000000000000" + // (2,0)
            "0000000000000040" + "0000000000000040" + // (2,2)
            "0000000000000000" + "0000000000000000" // (0,0) 闭合
        assertEquals(
            "geometry::STGeomFromText('POLYGON ((0.0 0.0, 2.0 0.0, 2.0 2.0, 0.0 0.0))', 0)",
            SpatialLiteral.render(hex),
        )
    }

    @Test
    fun `Z维坐标读满对齐_WKT只出二维投影`() {
        val hex = "0x01" + "01000080" + // LE 类型 POINT|Z 位，无 SRID
            "000000000000F03F" + // x
            "0000000000000040" + // y
            "0000000000000840" // z（读掉保对齐，不进 WKT）
        assertEquals(
            "geometry::STGeomFromText('POINT (1.0 2.0)', 0)",
            SpatialLiteral.render(hex),
        )
    }

    @Test
    fun `geography前缀按列类型取分`() {
        assertEquals(
            "geography::STGeomFromText('POINT (1.0 2.0)', 4326)",
            SpatialLiteral.render(point4326Le, SpatialLiteral.Kind.GEOGRAPHY),
        )
    }

    // ---------- 内核：反例族（双门槛不满足 → null 回落原路径） ----------

    @Test
    fun `反例族_非空间二进制与坏字节全部拒绝`() {
        val cases = listOf(
            "deadbeef", // 裸十六进制无 0x
            "[B@1a2b3c", // MyBatis 控制台 byte[] 默认 toString（无真实字节）
            "<byte[]>", // agent 降级形态
            "0x01", // 短于 POINT 最小结构
            "0x01020304050607", // 类型字 0x07060302 不在支持表
            "0x0101000020E6100000", // POINT 结构缺坐标
            point4326Le + "FF", // 合法 POINT 尾部余量
            "0x0101000020E6100000000000000000", // 坐标截断
            "", // 空串
        )
        for (v in cases) {
            assertNull("不得反解 $v", SpatialLiteral.render(v))
        }
    }

    @Test
    fun `不支持类型MULTIPOINT结构合法也拒`() {
        val hex = "0x01" + "05000000" + // LE 类型 5（MULTIPOINT，不在支持表）
            "02000000" +
            "0100000020E6100000000000000000F03F0000000000000040" +
            "0100000020E6100000000000000000F03F0000000000000040"
        assertNull("类型不在支持表即拒（宁可回落不猜）", SpatialLiteral.render(hex))
    }

    // ---------- 接线：LogSqlRestorer 空间分支（摘除即红） ----------

    @Test
    fun `还原链_空间二进制出构造字面量而非引号包裹`() {
        val restorer = LogSqlRestorer()
        assertEquals(
            "geometry::STGeomFromText('POINT (1.0 2.0)', 4326)",
            restorer.renderParam(LogSqlParam(point4326Le, "byte[]")),
        )
        // 还原主链贯通：? 占位符按序替换
        assertEquals(
            "INSERT INTO geo (p) VALUES (geometry::STGeomFromText('POINT (1.0 2.0)', 4326))",
            restorer.restore("INSERT INTO geo (p) VALUES (?)", listOf(LogSqlParam(point4326Le, "String"))),
        )
    }

    @Test
    fun `还原链_非空间二进制仍走原渲染路径`() {
        val restorer = LogSqlRestorer()
        // byte[] 控制台形态无真实字节 → 反解不成立 → 原引号路径（F-15「不可解析仍走原路径」）
        assertEquals("'[B@1a2b3c'", restorer.renderParam(LogSqlParam("[B@1a2b3c", "byte[]")))
        // 普通字符串形如十六进制但无 0x → 不受空间分支影响
        assertEquals("'deadbeef'", restorer.renderParam(LogSqlParam("deadbeef", "String")))
        // 数值参数零变化（快照对照：空间分支不改变既有类型渲染）
        assertEquals("42", restorer.renderParam(LogSqlParam("42", "Integer")))
        assertEquals("'abc'", restorer.renderParam(LogSqlParam("abc", "String")))
    }

    @Test
    fun `还原链_导出格式空间字面量不被脱敏与终止符破坏`() {
        // 导出链值渲染处（restoredSql）落 SQL 格式：分号终止 + 转义不受函数字面量影响
        val sql = LogSqlRestorer().restore(
            "UPDATE geo SET p = ? WHERE id = 1",
            listOf(LogSqlParam(point4326Le, "byte[]")),
        )
        assertTrue(sql.startsWith("UPDATE geo SET p = geometry::STGeomFromText('POINT (1.0 2.0)', 4326)"))
        assertTrue(!sql.contains("<binary>"))
    }
}
