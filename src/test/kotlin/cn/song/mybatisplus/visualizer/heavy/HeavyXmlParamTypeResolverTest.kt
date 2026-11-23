package cn.song.mybatisplus.visualizer.heavy

import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel
// T-M53-II D2-5：`showResult`/`clear` 已搬为 ui 包内的同包扩展函数（原 SqlPreviewPanel 成员），
// 跨包调用点需显式导入。
import cn.song.mybatisplus.visualizer.ui.clear
import cn.song.mybatisplus.visualizer.ui.showResult
import cn.song.mybatisplus.visualizer.ui.XmlParamTypeResolver
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService
import cn.song.mybatisplus.visualizer.xml.ParamKind
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.HeavyPlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * XML 参数类型解析测试（T-M29.5，Heavy 沙盒）。
 *
 * LightPlatform 沙盒限制（探针实证）：addFileToProject 的文件不进 FileBasedIndex、
 * 跨文件类型引用 resolve() 恒 null——mapper 接口（namespace → findClass）必须在 Heavy
 * 沙盒（磁盘文件 + 真实索引）解析，与 HeavySqlCompletionContextResolverTest 同款。
 */
class HeavyXmlParamTypeResolverTest : HeavyPlatformTestCase() {

    /** 在项目 src 根下创建源文件（磁盘 + 索引），返回 PsiFile（Heavy 测试同款）。 */
    private fun addSourceFile(name: String, content: String): PsiFile {
        return WriteCommandAction.runWriteCommandAction(project, Computable {
            val root = createTestProjectStructure()
            val src = root.findChild("src") ?: root.createChildDirectory(this, "src")
            val vf = src.createChildData(this, name)
            VfsUtil.saveText(vf, content.trimIndent())
            PsiManager.getInstance(project).findFile(vf)!!
        })
    }

    private val dtoMapper = """
        package com.example;

        import java.time.LocalDate;

        public class SettlementQueryDTO {
            private LocalDate settlementStartDate;
            private Integer dicCreditStatus;
            private String merchantName;
        }

        public interface SettlementMapper {
            java.util.Map<String, Object> statistics(
                @org.apache.ibatis.annotations.Param("p") SettlementQueryDTO p);
        }
    """.trimIndent()

    fun testDtoParamFieldTypesResolved() {
        // DTO 参数：字段类型 → ParamQuote（LocalDate/String → 引号；Integer → 裸渲染）
        addSourceFile("SettlementMapper.java", dtoMapper)
        val quotes = XmlParamTypeResolver.resolveQuotes(project, "com.example.SettlementMapper", "statistics")
        assertEquals(true, quotes["p.settlementStartDate"])
        assertEquals(false, quotes["p.dicCreditStatus"])
        assertEquals(true, quotes["p.merchantName"])
    }

    fun testMapParamPrefixFallback() {
        // Map 入参：键 = @Param 值，任意属性前缀命中（面板兜底引号）
        // T-GoldenDefense：@Param("map") 命名形态与同 FQN 双实体场景已锁定为金样 X8（下方两条）
        addSourceFile(
            "MapMapper.java",
            """
            package com.example;

            public interface MapMapper {
                java.util.Map<String, Object> statistics(
                    @org.apache.ibatis.annotations.Param("p") java.util.Map<String, Object> params);
            }
            """.trimIndent(),
        )
        val quotes = XmlParamTypeResolver.resolveQuotes(project, "com.example.MapMapper", "statistics")
        assertEquals(true, quotes["p"])
    }

    /** X8-1（T-GoldenDefense）：`@Param("map")` Map 参数 → 键 = 参数名（@Param 值）且值为引号。
     *  T-PC2-03 起单参数全显式亦补 `param1`（MyBatis `ParamMap` 位置别名）——金样随之收进 `param1`。 */
    fun testMapParamKeyEqualsParamNameWithQuote() {
        addSourceFile(
            "MapParamMapper.java",
            """
            package com.example;

            public interface MapParamMapper {
                java.util.Map<String, Object> queryByMap(
                    @org.apache.ibatis.annotations.Param("map") java.util.Map<String, Object> map);
            }
            """.trimIndent(),
        )
        val quotes = XmlParamTypeResolver.resolveQuotes(project, "com.example.MapParamMapper", "queryByMap")
        assertEquals("X8-1: Map 参数键 = 参数名且值为引号", mapOf("map" to true, "param1" to true), quotes)
    }

    /** X8-2（T-GoldenDefense）：同 FQN 双实体共存（多模块同名实体竞品翻车形态）——
     *  resolveQuotes 不抛错，`map` 键存在且无实体字段混入（Map 参数不展开实体，命中顺序不锁定）。 */
    fun testDuplicateFqnEntitiesLeaveMapQuotesIntact() {
        addSourceFile("User.java", "package com.example;\n\npublic class User {\n    private Long id;\n}")
        // 同 FQN 复制一份（不同文件同包同类名，字段不同）
        addSourceFile("UserDup.java", "package com.example;\n\npublic class User {\n    private String name;\n}")
        addSourceFile(
            "DupFqnMapMapper.java",
            """
            package com.example;

            public interface DupFqnMapMapper {
                java.util.Map<String, Object> queryByMap(
                    @org.apache.ibatis.annotations.Param("map") java.util.Map<String, Object> map);
            }
            """.trimIndent(),
        )
        val quotes = XmlParamTypeResolver.resolveQuotes(project, "com.example.DupFqnMapMapper", "queryByMap")
        assertEquals("X8-2: map 键存在且值为引号", true, quotes["map"])
        // T-PC2-03 起单参数全显式亦补 param1；断言锁「无实体字段混入」（Map 不展开实体）
        assertEquals("X8-2: 同名实体不影响 Map 键（无字段混入）", mapOf("map" to true, "param1" to true), quotes)
    }

    fun testNestedDtoAndNoParamAndPrimitive() {
        addSourceFile(
            "NestedMapper.java",
            """
            package com.example;

            public class SettlementQueryDTO {
                private java.time.LocalDate settlementStartDate;
                private Integer dicCreditStatus;
                private String merchantName;
            }

            public class InnerDTO {
                private java.time.LocalDate date;
            }

            public class OuterDTO {
                private InnerDTO dto;
            }

            public interface NestedMapper {
                void q(@org.apache.ibatis.annotations.Param("p") OuterDTO p);
                void r(SettlementQueryDTO dto);
                void page(long pageNum, long pageSize);
            }
            """.trimIndent(),
        )
        // 嵌套 DTO 递归：p.dto.date
        val nested = XmlParamTypeResolver.resolveQuotes(project, "com.example.NestedMapper", "q")
        assertEquals(true, nested["p.dto.date"])
        // 无 @Param：参数名（源码名）展开字段
        val noParam = XmlParamTypeResolver.resolveQuotes(project, "com.example.NestedMapper", "r")
        assertEquals(false, noParam["dto.dicCreditStatus"])
        // 基本类型参数：数值 → 裸渲染
        val primitive = XmlParamTypeResolver.resolveQuotes(project, "com.example.NestedMapper", "page")
        assertEquals(false, primitive["pageNum"])
        assertEquals(false, primitive["pageSize"])
    }

    fun testUnknownNamespaceOrStatementReturnsEmpty() {
        addSourceFile("SettlementMapper.java", dtoMapper)
        assertTrue(XmlParamTypeResolver.resolveQuotes(project, "com.example.Unknown", "statistics").isEmpty())
        assertTrue(XmlParamTypeResolver.resolveQuotes(project, "com.example.SettlementMapper", "unknown").isEmpty())
        assertTrue(XmlParamTypeResolver.resolveQuotes(null, "com.example.SettlementMapper", "statistics").isEmpty())
    }

    /**
     * D254（T-EntityFieldStaticHidden）：DTO 继承 + 静态字段的参数键集。
     *  - 同名属性取**子类**类型（父类 Integer 不得盖掉子类 String）；
     *  - `serialVersionUID` 真机 `Reflector.isValidPropertyName` 排除 → 不产键；
     *  - 其它 static 常量真机可读（static final 仍注册 getter）→ 保留键（防过度收紧）。
     */
    fun testInheritedDtoFieldsAndStaticPropertyRules() {
        addSourceFile(
            "InheritedDtoMapper.java",
            """
            package com.example;

            public class BaseQuery {
                private Integer status;
                private static final long serialVersionUID = 1L;
            }

            public class QueryDto extends BaseQuery {
                private String status;
                private String name;
                public static final String TABLE = "query_dto";
            }

            public interface InheritedDtoMapper {
                void q(@org.apache.ibatis.annotations.Param("p") QueryDto p);
            }
            """.trimIndent(),
        )
        val quotes = XmlParamTypeResolver.resolveQuotes(project, "com.example.InheritedDtoMapper", "q")
        assertEquals("同名属性应取子类 String（引号）而非父类 Integer", true, quotes["p.status"])
        assertEquals(true, quotes["p.name"])
        assertFalse("serialVersionUID 真机取不到，不应产键", quotes.containsKey("p.serialVersionUID"))
        assertTrue("static 常量真机可读，应保留键", quotes.containsKey("p.TABLE"))
    }

    // ------------------------------------------------------------------
    // T-PT-01：类型类别映射（Heavy 真实 PSI resolve）
    // ------------------------------------------------------------------

    /** 覆盖六类别 + 边界形态：数值族 / 时间 / 文本 / 集合 / Map / 枚举 / byte[] / 嵌套 DTO。 */
    private val kindMapper = """
        package com.example;

        import java.math.BigDecimal;
        import java.time.LocalDate;
        import java.util.Date;
        import java.util.List;
        import java.util.Map;

        public enum Status { A, B }

        public class InnerKindDTO {
            private LocalDate innerDate;
        }

        public class KindDTO {
            private Integer id;
            private int pageNum;
            private String name;
            private LocalDate startDate;
            private Date createdAt;
            private BigDecimal amount;
            private List<String> tags;
            private Status status;
            private byte[] blob;
            private InnerKindDTO inner;
        }

        public interface KindMapper {
            void byKinds(@org.apache.ibatis.annotations.Param("p") KindDTO p,
                         @org.apache.ibatis.annotations.Param("ids") List<Long> ids,
                         @org.apache.ibatis.annotations.Param("params") Map<String, Object> params,
                         @org.apache.ibatis.annotations.Param("keyword") String keyword,
                         @org.apache.ibatis.annotations.Param("bytes") byte[] bytes,
                         @org.apache.ibatis.annotations.Param("flag") boolean flag);
        }
    """.trimIndent()

    fun testParamKindsMappedBySignature() {
        addSourceFile("KindMapper.java", kindMapper)
        val types = XmlParamTypeResolver.resolveTypes(project, "com.example.KindMapper", "byKinds")
        // DTO 参数键 = 字段路径（不产出顶层键——与升级前引号映射键形态一致）
        assertNull("DTO 参数不产出顶层键", types["p"])
        assertEquals(ParamKind.NUMBER, types["p.id"]?.kind)
        assertEquals(ParamKind.NUMBER, types["p.pageNum"]?.kind)
        assertEquals(ParamKind.TEXT, types["p.name"]?.kind)
        assertEquals(ParamKind.TEMPORAL, types["p.startDate"]?.kind)
        assertEquals(ParamKind.TEMPORAL, types["p.createdAt"]?.kind)
        assertEquals(ParamKind.NUMBER, types["p.amount"]?.kind)
        assertEquals(ParamKind.COLLECTION, types["p.tags"]?.kind)
        assertEquals("枚举映射不到 → UNKNOWN（宁缺勿错）", ParamKind.UNKNOWN, types["p.status"]?.kind)
        assertEquals("byte[] 按 BLOB 语义落 UNKNOWN", ParamKind.UNKNOWN, types["p.blob"]?.kind)
        assertEquals("嵌套 DTO 继续递归（键为字段路径）", ParamKind.TEMPORAL, types["p.inner.innerDate"]?.kind)
        // 顶层参数
        assertEquals(ParamKind.COLLECTION, types["ids"]?.kind)
        assertEquals(ParamKind.OBJECT, types["params"]?.kind)
        assertEquals(ParamKind.TEXT, types["keyword"]?.kind)
        assertEquals(ParamKind.UNKNOWN, types["bytes"]?.kind)
        assertEquals(ParamKind.UNKNOWN, types["flag"]?.kind)
        // nullable 标记：原语类型非空
        assertEquals(false, types["p.pageNum"]?.nullable)
        assertEquals(true, types["p.id"]?.nullable)
        assertEquals(false, types["flag"]?.nullable)
    }

    fun testResolveQuotesDerivesFromKindsAndKeepsLegacyValues() {
        addSourceFile("KindMapper.java", kindMapper)
        val types = XmlParamTypeResolver.resolveTypes(project, "com.example.KindMapper", "byKinds")
        val quotes = XmlParamTypeResolver.resolveQuotes(project, "com.example.KindMapper", "byKinds")
        // 派生视图：NUMBER→false、其余 true（逐键一致，防回归断言）
        assertEquals(types.mapValues { (_, info) -> info.kind.quoteRequired }, quotes)
        assertEquals(false, quotes["p.id"])
        assertEquals(false, quotes["p.amount"])
        assertEquals(true, quotes["p.name"])
        assertEquals(true, quotes["p.startDate"])
        assertEquals(true, quotes["p.tags"])
        assertEquals(true, quotes["p.status"])
        assertEquals(true, quotes["params"])
    }

    // ------------------------------------------------------------------
    // T-PC2-01 / T-PC2-02：BigInteger 补入数值族、UUID 归文本（真实 PSI 解析链）
    // ------------------------------------------------------------------

    /** 补缺夹具：`java.math.BigInteger`（漏收）与 `java.util.UUID`（全仓无映射）两种来源（顶层参数 + DTO 字段）。 */
    private val mathMapper = """
        package com.example;

        import java.math.BigInteger;
        import java.util.UUID;

        public class MathDTO {
            private BigInteger serial;
            private UUID traceId;
        }

        public interface MathMapper {
            void byMath(@org.apache.ibatis.annotations.Param("p") MathDTO p,
                        @org.apache.ibatis.annotations.Param("big") BigInteger big,
                        @org.apache.ibatis.annotations.Param("uuid") UUID uuid);
        }
    """.trimIndent()

    fun testBigIntegerAndUuidMappedBySignature() {
        addSourceFile("MathMapper.java", mathMapper)
        val types = XmlParamTypeResolver.resolveTypes(project, "com.example.MathMapper", "byMath")
        assertEquals("BigInteger 顶层参数应落 NUMBER（漏收修复）", ParamKind.NUMBER, types["big"]?.kind)
        assertEquals("UUID 顶层参数应落 TEXT", ParamKind.TEXT, types["uuid"]?.kind)
        assertEquals("DTO 的 BigInteger 字段应落 NUMBER", ParamKind.NUMBER, types["p.serial"]?.kind)
        assertEquals("DTO 的 UUID 字段应落 TEXT", ParamKind.TEXT, types["p.traceId"]?.kind)
        // 引号语义：BigInteger 裸渲染（原 UNKNOWN 带引号 → 修正）、UUID 带引号
        val quotes = XmlParamTypeResolver.resolveQuotes(project, "com.example.MathMapper", "byMath")
        assertEquals("BigInteger 应裸渲染", false, quotes["big"])
        assertEquals("UUID 应带引号", true, quotes["uuid"])
        assertEquals(false, quotes["p.serial"])
        assertEquals(true, quotes["p.traceId"])
    }

    // ------------------------------------------------------------------
    // T-PC2-03：XML 路径 paramN 位置别名（Z-17 全或无门槛）
    // ------------------------------------------------------------------

    /** 门槛夹具：全显式 / 含未命名 / 全未命名 / 单参数全显式 四态。 */
    private val aliasMapper = """
        package com.example;

        public interface AliasMapper {
            void allNamed(@org.apache.ibatis.annotations.Param("a") long a,
                          @org.apache.ibatis.annotations.Param("b") String b);
            void mixed(@org.apache.ibatis.annotations.Param("a") long a, String b);
            void unnamed(long a, String b);
            void singleNamed(@org.apache.ibatis.annotations.Param("only") String only);
        }
    """.trimIndent()

    fun testParamNAliasOnlyWhenAllParamsAnnotated() {
        addSourceFile("AliasMapper.java", aliasMapper)
        // 全显式 → 补 param1..N（类型跟随位置参数）
        val all = XmlParamTypeResolver.resolveTypes(project, "com.example.AliasMapper", "allNamed")
        assertEquals(ParamKind.NUMBER, all["param1"]?.kind)
        assertEquals(ParamKind.TEXT, all["param2"]?.kind)
        assertEquals("原语参数经别名继承非空标记", false, all["param1"]?.nullable)
        assertEquals(true, all["param2"]?.nullable)
        // 单参数全显式：MyBatis ParamMap 同样补 param1
        val single = XmlParamTypeResolver.resolveTypes(project, "com.example.AliasMapper", "singleNamed")
        assertEquals(ParamKind.TEXT, single["param1"]?.kind)
        // 含未命名参数 → 整体不推导（宁缺勿错），命名键照常保留
        val mixed = XmlParamTypeResolver.resolveTypes(project, "com.example.AliasMapper", "mixed")
        assertNull("含未命名参数不推导 paramN", mixed["param1"])
        assertNull(mixed["param2"])
        assertTrue("命名键仍在", mixed.containsKey("a") && mixed.containsKey("b"))
        // 全未命名 → 不推导
        val unnamed = XmlParamTypeResolver.resolveTypes(project, "com.example.AliasMapper", "unnamed")
        assertNull(unnamed["param1"])
        assertNull(unnamed["param2"])
        assertTrue(unnamed.containsKey("a") && unnamed.containsKey("b"))
    }

    /** T-PC2-03 别名与类型类别联动：`#{param1.字段}` 经前缀兜底落 OBJECT（DTO 参数的位置别名）。 */
    fun testParamNAliasForDtoParamResolvesObject() {
        addSourceFile("AliasDtoMapper.java", """
            package com.example;

            public class AliasDto {
                private Integer id;
            }

            public interface AliasDtoMapper {
                void byDto(@org.apache.ibatis.annotations.Param("p") AliasDto p,
                           @org.apache.ibatis.annotations.Param("k") String k);
            }
        """.trimIndent())
        val model = XmlPreviewModel.from(
            XmlMapperParser().parse(
                """
                <mapper namespace="com.example.AliasDtoMapper">
                  <select id="byDto">SELECT * FROM t WHERE id = #{param1.id} AND k = #{param2}</select>
                </mapper>
                """.trimIndent(),
            ).statements.single(),
            emptyList(),
            paramTypes = XmlParamTypeResolver.resolveTypes(project, "com.example.AliasDtoMapper", "byDto"),
        )
        assertEquals(ParamKind.OBJECT, model.kindFor("param1"))
        assertEquals("param1.字段 前缀兜底命中别名条目", ParamKind.OBJECT, model.kindFor("param1.id"))
        assertEquals(ParamKind.TEXT, model.kindFor("param2"))
    }

    fun testEndToEndPanelBackfillByParamType() {
        // 用户场景闭环：DTO 参数——Integer 字段裸渲染、LocalDate 字段引号（贴近真实绑定效果）
        addSourceFile("SettlementMapper.java", dtoMapper)
        val xml = """
            <mapper namespace="com.example.SettlementMapper">
              <select id="statistics" resultType="java.util.Map">
                SELECT * FROM sys_merchant_wallet_settlement t
                <where>
                    t.deleted = 0
                    <if test="p.settlementStartDate != null">
                        AND t.settlement_period &gt;= #{p.settlementStartDate}
                    </if>
                    <if test="p.dicCreditStatus != null">
                        AND t.dic_credit_status = #{p.dicCreditStatus}
                    </if>
                </where>
              </select>
            </mapper>
        """.trimIndent()
        val result = XmlPreviewService.preview(xml, "statistics", project)
        assertNotNull("应产出预览结果", result)
        val panel = SqlPreviewPanel(project)
        panel.showResult(result)
        val fields = (0 until 30).mapNotNull { panel.paramFieldForTest(it) }
        val dateField = fields.firstOrNull { it.toolTipText == "#p.settlementStartDate" }
        val statusField = fields.firstOrNull { it.toolTipText == "#p.dicCreditStatus" }
        assertNotNull("应有日期参数输入行", dateField)
        assertNotNull("应有状态参数输入行", statusField)
        dateField!!.text = "2025"
        statusField!!.text = "2"
        val sql = panel.editorForTest()!!.document.text.replace(Regex("\\s+"), " ").trim()
        assertTrue("LocalDate 字段应引号：$sql", sql.contains("t.settlement_period >= '2025'"))
        assertTrue("Integer 字段应裸渲染：$sql", sql.contains("t.dic_credit_status = 2"))
        assertFalse("Integer 字段不应带引号：$sql", sql.contains("t.dic_credit_status = '2'"))
        panel.clear()
    }

    // ------------------------------------------------------------------
    // T-FCA-01（N-32）：foreach 集合快捷别名（ParamNameResolver.wrapToMapIfCollection）
    //
    // 验收 1（「真引擎对照下三种快捷名渲染逐字节相等」）的**承重判据在本节**：三条用例钉我方注入
    // （`XmlParamTypeResolver.collectionAliasTypes` 经 `resolveTypes` → paramTypes），摘掉注入即红
    // （反向验证两次读数见实施记录）。`xml/fidelity/RealEngineFidelityTest` 的 P31~P33 只钉
    // **真引擎键集 + 自研 foreach 语义**（自研侧手工喂别名键、绕开面板 mock 层），对我方注入不构成判据
    // ——那边的注记同源。
    // ------------------------------------------------------------------

    /**
     * 别名门槛夹具（覆盖真引擎的逐形态分支 + 三类负向）：单 List / 单数组 / 单 Set /
     * 单 Iterable（非 Collection）/ 单参数带 @Param / 多参数 / 非集合标量 /
     * 泛型数组两种（`List<Long>[]`、`Map<String,Object>[]`，见 [testCollectionAliasForGenericArrayKeysArrayOnly]）。
     */
    private val collectionAliasMapper = """
        package com.example;

        public interface CollectionAliasMapper {
            void byList(java.util.List<Long> ids);
            void byArray(Long[] ids);
            void bySet(java.util.Set<Long> ids);
            void byIterable(Iterable<Long> ids);
            void byNamed(@org.apache.ibatis.annotations.Param("ids") java.util.List<Long> ids);
            void byMulti(java.util.List<Long> ids, String keyword);
            void byScalar(Long id);
            void byGenericListArray(java.util.List<Long>[] ids);
            void byGenericSetArray(java.util.Set<Long>[] ids);
            void byGenericMapArray(java.util.Map<String, Object>[] ids);
        }
    """.trimIndent()

    /**
     * 正向：单集合参数且无 `@Param` → 别名键存在，键集按真引擎实测形态
     * （`List` → `collection` + `list`；`T[]` → 仅 `array`；`Set` → 仅 `collection`）。
     * 参数名键照常保留（别名是**追加**不是替换）。
     *
     * **承重**：本用例（与 [testCollectionAliasAbsentWhenGateFails]）是「我方别名注入」的直接判据——
     * 摘掉 `resolveTypes` 里的注入即红；`RealEngineFidelityTest` 的 P31~P33 对本注入不可见（见节头注记）。
     */
    fun testCollectionAliasKeysByParamShape() {
        addSourceFile("CollectionAliasMapper.java", collectionAliasMapper)
        val list = XmlParamTypeResolver.resolveTypes(project, "com.example.CollectionAliasMapper", "byList")
        assertEquals("List 参数应双写 collection + list", ParamKind.COLLECTION, list["collection"]?.kind)
        assertEquals(ParamKind.COLLECTION, list["list"]?.kind)
        assertNull("List 参数不产 array 键", list["array"])
        assertEquals("参数名键照常保留", ParamKind.COLLECTION, list["ids"]?.kind)

        val array = XmlParamTypeResolver.resolveTypes(project, "com.example.CollectionAliasMapper", "byArray")
        assertEquals("数组参数只产 array 键", ParamKind.COLLECTION, array["array"]?.kind)
        assertNull("数组参数不产 collection 键", array["collection"])
        assertNull("数组参数不产 list 键", array["list"])
        assertEquals(ParamKind.COLLECTION, array["ids"]?.kind)

        val set = XmlParamTypeResolver.resolveTypes(project, "com.example.CollectionAliasMapper", "bySet")
        assertEquals("Set（Collection 非 List）只产 collection", ParamKind.COLLECTION, set["collection"]?.kind)
        assertNull("Set 参数不产 list 键（真引擎 instanceof List 不成立）", set["list"])
        assertNull(set["array"])
    }

    /**
     * 负向（全或无门槛的反面）：多参数、单参数带显式 `@Param`、非集合标量、`Iterable`
     * （集合语义但非 `java.util.Collection`，真引擎不包装）→ **均不产别名键**。
     */
    fun testCollectionAliasAbsentWhenGateFails() {
        addSourceFile("CollectionAliasMapper.java", collectionAliasMapper)
        val named = XmlParamTypeResolver.resolveTypes(project, "com.example.CollectionAliasMapper", "byNamed")
        assertNull("带 @Param 不产 list 别名", named["list"])
        assertNull(named["collection"])
        assertNull(named["array"])
        assertEquals("命名键仍在", ParamKind.COLLECTION, named["ids"]?.kind)

        val multi = XmlParamTypeResolver.resolveTypes(project, "com.example.CollectionAliasMapper", "byMulti")
        assertNull("多参数不产 list 别名", multi["list"])
        assertNull(multi["collection"])
        assertNull(multi["array"])
        assertTrue("命名键仍在", multi.containsKey("ids") && multi.containsKey("keyword"))

        val scalar = XmlParamTypeResolver.resolveTypes(project, "com.example.CollectionAliasMapper", "byScalar")
        assertNull("非集合标量不产别名", scalar["collection"])
        assertNull(scalar["list"])
        assertNull(scalar["array"])

        val iterable = XmlParamTypeResolver.resolveTypes(project, "com.example.CollectionAliasMapper", "byIterable")
        assertNull("Iterable 非 java.util.Collection：真引擎不包装 → 不产别名", iterable["collection"])
        assertNull(iterable["list"])
    }

    /**
     * 泛型数组键集（T-FCA-01 修正）：数组形态判定**必须先于泛型剥壳**。
     *
     * 实测缺陷（独立复核员读数 `expected:<[array, ids]> but was:<[collection, ids, list]>`）：
     * 旧实现先 `substringBefore('<')` 再判 `endsWith("[]")`，`java.util.List<java.lang.Long>[]`
     * 被截成 `List` → 误判为 List 族并产出 `list` 键；`Set<Long>[]` 同源（得 `collection`）。
     * 而数组在真引擎里**只有 `array` 键**（`RealEngineFidelityTest` 的「别名键集钉」用例 `T[]` 支：
     * `instanceof Collection` 对数组不成立，走 `getClass().isArray()` 支）。
     *
     * 断言用**整键集**（而非单键存在性）：误产出的 `list`/`collection` 键只有整集比对才看得见。
     * `Map<String,Object>[]` 是同一根因在类别层的显形（旧顺序剥壳后按 `Map` 落 OBJECT，
     * 连别名门槛都进不去）——数组类别与元素类型无关。
     */
    fun testCollectionAliasForGenericArrayKeysArrayOnly() {
        addSourceFile("CollectionAliasMapper.java", collectionAliasMapper)
        fun keysOf(method: String): Set<String> =
            XmlParamTypeResolver.resolveTypes(project, "com.example.CollectionAliasMapper", method).keys
        // 对照：普通数组本就正确（修正泛型数组后不得回退）
        assertEquals("普通数组 T[] → 仅 array + 参数名键", setOf("array", "ids"), keysOf("byArray"))
        assertEquals(
            "泛型数组 List<Long>[] → 仅 array（不得出现 list/collection）",
            setOf("array", "ids"),
            keysOf("byGenericListArray"),
        )
        assertEquals(
            "泛型数组 Set<Long>[] → 仅 array",
            setOf("array", "ids"),
            keysOf("byGenericSetArray"),
        )
        assertEquals(
            "泛型数组 Map<String,Object>[] → 仅 array（数组类别不随元素类型变）",
            setOf("array", "ids"),
            keysOf("byGenericMapArray"),
        )
        assertEquals(
            "数组类别恒为 COLLECTION（元素是 Map 也不例外）",
            ParamKind.COLLECTION,
            XmlParamTypeResolver.resolveTypes(project, "com.example.CollectionAliasMapper", "byGenericMapArray")["ids"]?.kind,
        )
    }

    /**
     * 别名与预览绑定构造联动：`collection="list"` 的 foreach 在预览里按别名键展开（引号档随类别）。
     *
     * **承重**：注入的**唯一可观测面**（面板类型视图 `kindFor`/`quoteFor`）在此钉住——渲染成功本身
     * 不是判据（`mockBindings` 直接按 `collectionExpr` 建键，无注入也渲染得出来，实测）。
     */
    fun testCollectionAliasFeedsPreviewBindings() {
        addSourceFile("CollectionAliasMapper.java", collectionAliasMapper)
        val model = XmlPreviewModel.from(
            XmlMapperParser().parse(
                """
                <mapper namespace="com.example.CollectionAliasMapper">
                  <select id="byList">SELECT * FROM t WHERE id IN<foreach collection="list" item="id" open="(" separator="," close=")">#{id}</foreach></select>
                </mapper>
                """.trimIndent(),
            ).statements.single(),
            emptyList(),
            paramTypes = XmlParamTypeResolver.resolveTypes(project, "com.example.CollectionAliasMapper", "byList"),
        )
        assertEquals("别名键进 paramTypes（预览类型视图可命中）", ParamKind.COLLECTION, model.kindFor("list"))
        assertEquals("别名键引号档随类别（COLLECTION → 引号）", true, model.quoteFor("list"))
        assertEquals("foreach 视图照常收集", "list", model.forEachViews.single().collectionExpr)
    }
}
