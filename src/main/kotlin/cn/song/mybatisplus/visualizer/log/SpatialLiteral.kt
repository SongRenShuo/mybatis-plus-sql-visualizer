package cn.song.mybatisplus.visualizer.log

import java.nio.ByteBuffer

/**
 * F-15（DBX #319）：空间二进制反解内核——把十六进制形态的 WKB/EWKB 几何值反解为
 * SQL Server 方言的构造函数字面量 `geometry::STGeomFromText('POINT (1 2)', SRID)`。
 *
 * 输入口径：二进制参数在日志/导出链里的可见形态是十六进制文本（`0x` 前缀，控制台驱动
 * 打印与 SQL Server 客户端复制粘贴的通用形态）；裸 `[B@1a2b3c`/`<byte[]>` 形态不含
 * 真实字节，本内核无从反解，返回 null 由调用方走原渲染路径。
 *
 * 支持形态（EWKB，带 SRID 字段）：
 * - 字节序：`00` 大端 / `01` 小端（每字段独立跟随首字节声明）；
 * - 类型：POINT(1) / LINESTRING(2) / POLYGON(3)，`0x20000000` 位置= SRID 字段在位；
 * - Z/M 位（`0x80000000`/`0x40000000`）：坐标维度读满保持字节对齐，WKT 只出二维投影
 *   （SRID 字面量语境下三维文本各版本语义分裂，二维投影是可执行的保守形态）；
 * - SRID：EWKB 类型字后的 4 字节无符号整数。
 *
 * 双门槛防误判（F-15 判据「非空间二进制仍走原路径」）：值必须带 `0x` 前缀**且**按 EWKB
 * 结构解析成功（字节序合法、类型在支持表内、坐标数与声明的几何规模吻合、长度零余量）。
 * 任一不满足返回 null——普通字符串（哪怕形如十六进制）与坏字节都不进本链。
 *
 * 方言边界（登记）：捕获链无方言上下文（[LogSqlRestorer] 按 ? 位点顺序还原，方言是
 * agent 事件后到的融合字段），本内核按判据口径产出 SQL Server 语法；EWKB 在
 * PostgreSQL/PostGIS 语境同样可见，目标方言外的使用登记为后续决策点。
 * geography 与 geometry 同构处理，仅构造前缀按 [Kind] 取分（SQL Server 两者语法对称）。
 */
object SpatialLiteral {

    /** 空间列族：前缀按列类型取分（geometry 默认 SRID 0，geography 常见 4326，SRID 以 EWKB 字段为准）。 */
    enum class Kind(val prefix: String) {
        GEOMETRY("geometry"),
        GEOGRAPHY("geography"),
    }

    /** 反解结果：WKT 文本（不含 SRID）+ EWKB 声明的 SRID。 */
    data class Spatial(val wkt: String, val srid: Int, val kind: Kind)

    /** `0x` 前缀十六进制（大小写均可；空串/奇数长度/非十六进制字符一律拒绝）。 */
    fun parseHexBytes(value: String): ByteArray? {
        val body = if (value.startsWith("0x") || value.startsWith("0X")) value.substring(2) else return null
        if (body.isEmpty() || body.length % 2 != 0) return null
        val out = ByteArray(body.length / 2)
        for (i in out.indices) {
            val hi = HEX_VALUE[body[i * 2].code.coerceIn(0, 255)]
            val lo = HEX_VALUE[body[i * 2 + 1].code.coerceIn(0, 255)]
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * 反解入口：十六进制值 → SQL Server 空间构造字面量；不可解析返回 null（调用方回落原路径）。
     * [kind] 前缀按列类型传入（捕获链无列类型上下文，默认 geometry）。
     */
    fun render(value: String, kind: Kind = Kind.GEOMETRY): String? {
        val bytes = parseHexBytes(value) ?: return null
        val parsed = parseEwkb(bytes) ?: return null
        return parsed.copy(kind = kind).let { "${it.kind.prefix}::STGeomFromText('${it.wkt}', ${it.srid})" }
    }

    /** EWKB 结构解析（纯函数；规模超界即拒，防坏字节把读指针推爆）。 */
    private fun parseEwkb(bytes: ByteArray): Spatial? {
        if (bytes.size < MIN_EWKB_BYTES) return null
        val buf = ByteBuffer.wrap(bytes)
        val bigEndian = when (buf.get().toInt()) {
            0 -> true
            1 -> false
            else -> return null
        }
        buf.order(if (bigEndian) java.nio.ByteOrder.BIG_ENDIAN else java.nio.ByteOrder.LITTLE_ENDIAN)
        val typeWord = buf.int
        val hasSrid = typeWord and SRID_FLAG != 0
        val hasZ = typeWord and Z_FLAG != 0
        val hasM = typeWord and M_FLAG != 0
        val baseType = typeWord and TYPE_MASK
        if (baseType !in SUPPORTED_TYPES) return null
        val srid = if (hasSrid) {
            if (!buf.hasRemaining(4)) return null
            buf.int
        } else 0
        val dims = 2 + (if (hasZ) 1 else 0) + (if (hasM) 1 else 0)
        val wkt = when (baseType) {
            TYPE_POINT -> {
                val point = readPoint(buf, dims) ?: return null
                "POINT ($point)"
            }
            TYPE_LINESTRING -> {
                val count = readCount(buf) ?: return null
                if (count < 2 || count > MAX_POINTS) return null
                "LINESTRING (${readPoints(buf, count, dims) ?: return null})"
            }
            else -> {
                // POLYGON：环数 + 逐环（点数 + 点序列）；环闭合与否不校验（WKT 接受不闭合由引擎兜）
                val ringCount = readCount(buf) ?: return null
                if (ringCount < 1 || ringCount > MAX_RINGS) return null
                val rings = (0 until ringCount).map {
                    val n = readCount(buf) ?: return null
                    if (n < 1 || n > MAX_POINTS) return null
                    "(${readPoints(buf, n, dims) ?: return null})"
                }
                "POLYGON (${rings.joinToString(", ")})"
            }
        }
        if (buf.hasRemaining()) return null // 尾部零余量：多一字节即非预期形态
        return Spatial(wkt, srid, Kind.GEOMETRY)
    }

    /** 无符号 4 字节计数（几何规模声明；> 规模上限按坏数据拒）。 */
    private fun readCount(buf: ByteBuffer): Int? {
        if (!buf.hasRemaining(4)) return null
        val v = buf.int
        return if (v < 0 || v > MAX_POINTS) null else v
    }

    private fun readPoints(buf: ByteBuffer, count: Int, dims: Int): String? {
        val sb = StringBuilder(count * 24)
        for (i in 0 until count) {
            val point = readPoint(buf, dims) ?: return null
            if (i > 0) sb.append(", ")
            sb.append(point)
        }
        return sb.toString()
    }

    /** 单点读 dims 个 double；WKT 只取前两维（Z/M 读掉保对齐，见类头）。 */
    private fun readPoint(buf: ByteBuffer, dims: Int): String? {
        if (!buf.hasRemaining(dims * 8)) return null
        val x = buf.double
        val y = buf.double
        repeat(dims - 2) { buf.double }
        val xs = renderDouble(x) ?: return null
        val ys = renderDouble(y) ?: return null
        return "$xs $ys"
    }

    /** double → WKT 文本（SQL Server 接受 `1.0`/`-2.5`/`1.0E10`；NaN/Infinity 非法几何，拒）。 */
    private fun renderDouble(v: Double): String? {
        if (v.isNaN() || v.isInfinite()) return null
        return v.toString()
    }

    private fun ByteBuffer.hasRemaining(n: Int): Boolean = remaining() >= n

    private const val SRID_FLAG = 0x20000000

    /** 0x80000000 在 Kotlin 十六进制字面量里升 Long，用 Int 补码常量表达同一位。 */
    private const val Z_FLAG = Int.MIN_VALUE

    private const val M_FLAG = 0x40000000
    private const val TYPE_MASK = 0x0FFFFFFF

    private const val TYPE_POINT = 1
    private const val TYPE_LINESTRING = 2
    private const val TYPE_POLYGON = 3

    private val SUPPORTED_TYPES = setOf(TYPE_POINT, TYPE_LINESTRING, TYPE_POLYGON)

    /** POINT 最小 EWKB：字节序(1) + 类型(4) + 坐标(16) = 21 字节。 */
    private const val MIN_EWKB_BYTES = 21

    /** 规模上限（坏字节/异常数据防呆；超出按不可解析回落原路径，正常空间值远小于此）。 */
    private const val MAX_POINTS = 100_000
    private const val MAX_RINGS = 1_000

    /** 十六进制字符表：缺省 -1（非十六进制字符查表即拒，不静默当 0）。 */
    private val HEX_VALUE = IntArray(256) { -1 }.also { table ->
        for (c in '0'..'9') table[c.code] = c - '0'
        for (c in 'a'..'f') table[c.code] = c - 'a' + 10
        for (c in 'A'..'F') table[c.code] = c - 'A' + 10
    }
}
