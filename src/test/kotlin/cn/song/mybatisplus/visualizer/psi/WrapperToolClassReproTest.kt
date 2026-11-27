package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.converter.QueryWrapperParser
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * T-WrapperToolClass（用户反馈复现）：Wrapper 交给工具类/辅助方法处理后，最终 SQL 少了那一部分条件。
 *
 * 用户原话（2026-11-27 评论）：
 * 「我写了一个工具类，通过工具类对 MP 的 QueryWrapper 处理之后，最终的 SQL 里少了这一部分。
 *   貌似这个插件只处理简单的 if 判断，对于跳转到其他方法、稍微复杂一点的逻辑，就被舍弃掉了。」
 *
 * 本类只做**非 GUI 复现**：LightPlatform 真实 Java PSI → WrapperLocator → QueryWrapperParser → SqlGenerator。
 * 每个用例断言「链上实际收集到的方法名序列」，缺项即为用户看到的现象（条件丢失）。
 *
 * 用例分两档：
 *  - BASELINE（应绿）：当前已支持的形态，作为对照组，证明测试装置本身有效。
 *  - REPRO（预期红）：用户现场形态，红即缺陷成立；修好后转绿作为回归护栏。
 */
class WrapperToolClassReproTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // 对照组：当前应已支持的形态
    // ------------------------------------------------------------------

    /**
     * BASELINE-1：单层工具类接收 wrapper 实参并就地追加条件（M19.6 已支持）。
     * 这条绿了才说明测试装置与「工具类」语料形态是对的。
     */
    fun testBaselineSingleLayerHelperArgument() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<>();
                    w.eq(DeviceInfoEntity::getId, 1L);
                    FilterUtil.applyStatus(w, 1);
                    mapper.selectList(w);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Integer status;
                    private Long tenantId;
                }

                static class FilterUtil {
                    static void applyStatus(LambdaQueryWrapper<DeviceInfoEntity> w, Integer status) {
                        w.ne(DeviceInfoEntity::getStatus, status);
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(
            "单层工具类实参应被展开（M19.6 基线）",
            listOf("eq", "ne"),
            callsAtConsumer(),
        )
    }

    // ------------------------------------------------------------------
    // REPRO：用户现场形态
    // ------------------------------------------------------------------

    /**
     * REPRO-1：工具类内部**再往下传一层**（helper → helper）。
     * expandHelperCalls 注释明写「只展开一层（helper 内不再递归传参）」——第二层的条件必然丢失。
     */
    fun testReproNestedHelperTwoLevels() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<>();
                    w.eq(DeviceInfoEntity::getId, 1L);
                    FilterUtil.applyStatus(w, 1);
                    mapper.selectList(w);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Integer status;
                    private Long tenantId;
                }

                static class FilterUtil {
                    static void applyStatus(LambdaQueryWrapper<DeviceInfoEntity> w, Integer status) {
                        applyTenantScope(w);
                        w.ne(DeviceInfoEntity::getStatus, status);
                    }

                    static void applyTenantScope(LambdaQueryWrapper<DeviceInfoEntity> w) {
                        w.isNotNull(DeviceInfoEntity::getTenantId);
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(
            "两层工具类：tenant 条件应随第一层一起展开",
            listOf("eq", "isNotNull", "ne"),
            callsAtConsumer(),
        )
    }

    /**
     * REPRO-2：工具类**返回**处理后的 wrapper，调用方重新赋值。
     * `w = FilterUtil.decorate(w, dto)` 不是变量 initializer，expandFactoryCalls 够不着。
     */
    fun testReproHelperReturnsWrapperAndReassigned() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<>();
                    w.eq(DeviceInfoEntity::getId, 1L);
                    w = FilterUtil.decorate(w, 1);
                    mapper.selectList(w);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Integer status;
                }

                static class FilterUtil {
                    static LambdaQueryWrapper<DeviceInfoEntity> decorate(
                            LambdaQueryWrapper<DeviceInfoEntity> w, Integer status) {
                        return w.ne(DeviceInfoEntity::getStatus, status);
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(
            "返回 wrapper 再赋值：decorate 内部追加的 ne 应保留",
            listOf("eq", "ne"),
            callsAtConsumer(),
        )
    }

    /**
     * REPRO-3：工具类**构造并返回** wrapper，调用方以变量初始化接收。
     * `LambdaQueryWrapper<X> w = FilterUtil.buildQuery(dto)`——initializer 是普通方法调用，
     * findFactoryReturnChain 只认 return 表达式里的**链**，`return q`（裸引用）收不到。
     */
    fun testReproHelperBuildsAndReturnsWrapper() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = FilterUtil.buildQuery(1);
                    mapper.selectList(w);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Integer status;
                }

                static class FilterUtil {
                    static LambdaQueryWrapper<DeviceInfoEntity> buildQuery(Integer status) {
                        LambdaQueryWrapper<DeviceInfoEntity> q = new LambdaQueryWrapper<>();
                        q.eq(DeviceInfoEntity::getStatus, status);
                        q.isNotNull(DeviceInfoEntity::getId);
                        return q;
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(
            "工具类构造并返回 wrapper：q.eq / q.isNotNull 应被收集",
            listOf("eq", "isNotNull"),
            callsAtConsumer(),
        )
    }

    /**
     * REPRO-4：工具类内部先用**局部别名**接住形参，再在别名上追加条件。
     * expandHelperCalls 只认 `headQualifier.resolve() === param`，别名 resolve 到本地变量 → 全丢。
     */
    fun testReproHelperLocalAlias() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<>();
                    w.eq(DeviceInfoEntity::getId, 1L);
                    FilterUtil.applyStatus(w, 1);
                    mapper.selectList(w);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Integer status;
                }

                static class FilterUtil {
                    static void applyStatus(LambdaQueryWrapper<DeviceInfoEntity> w, Integer status) {
                        LambdaQueryWrapper<DeviceInfoEntity> target = w;
                        target.ne(DeviceInfoEntity::getStatus, status);
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(
            "工具类内局部别名：target.ne 应归到同一条链",
            listOf("eq", "ne"),
            callsAtConsumer(),
        )
    }

    /**
     * REPRO-5：工具类形参声明为抽象基类型 `Wrapper<T>`（不是具体 LambdaQueryWrapper）。
     * 形参 resolve 比较不依赖类型，理论上应绿；用这条探明「类型」是不是第二道门槛。
     */
    fun testReproHelperAbstractWrapperParameter() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
            import com.baomidou.mybatisplus.core.conditions.Wrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<>();
                    w.eq(DeviceInfoEntity::getId, 1L);
                    FilterUtil.applyStatus(w, 1);
                    mapper.selectList(w);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Integer status;
                }

                static class FilterUtil {
                    static void applyStatus(Wrapper<DeviceInfoEntity> w, Integer status) {
                        w.ne("status", status);
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(
            "形参为 Wrapper<T> 基类型：内部 w.ne 应被收集",
            listOf("eq", "ne"),
            callsAtConsumer(),
        )
    }

    /**
     * REPRO-6：工具类分步**调用两次**（同一 helper 在不同分支/不同轮次各调一次）。
     * expandHelperCalls 每次从方法体收全部调用，两次调用会收到同一批调用表达式，
     * 靠 `distinctBy(endOffset)` 挤掉——但若两次调用之间条件不同（重载/不同实参），会合并成一份。
     */
    fun testReproHelperCalledTwiceWithDifferentArgs() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<>();
                    FilterUtil.applyStatus(w, 1);
                    FilterUtil.applyName(w, "gateway");
                    mapper.selectList(w);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Integer status;
                    private String deviceName;
                }

                static class FilterUtil {
                    static void applyStatus(LambdaQueryWrapper<DeviceInfoEntity> w, Integer status) {
                        w.ne(DeviceInfoEntity::getStatus, status);
                    }

                    static void applyName(LambdaQueryWrapper<DeviceInfoEntity> w, String name) {
                        w.like(DeviceInfoEntity::getDeviceName, name);
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(
            "两个不同的工具类各调一次：两条条件都应收集",
            listOf("ne", "like"),
            callsAtConsumer(),
        )
    }

    /**
     * REPRO-7：工具类内部用 **builder 风格** 分步追加（`w.eq(...).ne(...)` 单句链式）。
     * 这条验证 helper 内链式延续是否完整（collectExtendingChain 的边界）。
     */
    fun testReproHelperInternalChainedCall() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<>();
                    w.eq(DeviceInfoEntity::getId, 1L);
                    FilterUtil.applyAll(w);
                    mapper.selectList(w);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Integer status;
                    private String deviceName;
                }

                static class FilterUtil {
                    static void applyAll(LambdaQueryWrapper<DeviceInfoEntity> w) {
                        w.ne(DeviceInfoEntity::getStatus, 1)
                         .like(DeviceInfoEntity::getDeviceName, "a");
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(
            "工具类内单句链式：ne 与 like 都应收集",
            listOf("eq", "ne", "like"),
            callsAtConsumer(),
        )
    }

    /**
     * REPRO-8：工具类内部**没有**对形参的调用，但返回了一个调用它自己的结果
     * （`return helper(w)` 中转形态）。
     * 这条钉住「展开只认直接调用、不认中转返回」。
     */
    fun testReproHelperDelegatesToAnotherAndReturns() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<>();
                    w.eq(DeviceInfoEntity::getId, 1L);
                    FilterUtil.applyStatus(w, 1);
                    mapper.selectList(w);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Integer status;
                }

                static class FilterUtil {
                    static void applyStatus(LambdaQueryWrapper<DeviceInfoEntity> w, Integer status) {
                        inner(w, status);
                    }

                    static void inner(LambdaQueryWrapper<DeviceInfoEntity> w, Integer status) {
                        w.ne(DeviceInfoEntity::getStatus, status);
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(
            "工具类透传到另一个方法：inner 内的 ne 应被收集",
            listOf("eq", "ne"),
            callsAtConsumer(),
        )
    }

    /**
     * REPRO-9：**静态导入**调用工具类（`import static ...;` 后裸调 `applyStatus(w, 1)`）。
     * 用户现场常见写法，钉住「qualifier 为 null 时不阻断展开」。
     *
     * 工具类放在**同文件的嵌套类**里、以 `import static Demo.FilterUtil.applyStatus;` 导入——
     * 避免跨包 `addClass` 在 LightPlatform 下的 resolve 不确定性（首版用 `package util` + addClass
     * 时静态导入解析不到，红是装置限制而非插件缺陷，已按 §待验证前提 V3 排除）。
     */
    fun testReproStaticImportHelper() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
            import static Demo.FilterUtil.applyStatus;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<>();
                    w.eq(DeviceInfoEntity::getId, 1L);
                    applyStatus(w, 1);
                    mapper.selectList(w);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Integer status;
                }

                static class FilterUtil {
                    static void applyStatus(LambdaQueryWrapper<DeviceInfoEntity> w, Integer status) {
                        w.ne(DeviceInfoEntity::getStatus, status);
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(
            "静态导入裸调工具类：ne 应被收集",
            listOf("eq", "ne"),
            callsAtConsumer(),
        )
    }

    /**
     * REPRO-10：工具类返回 wrapper 并被**直接消费**（不落变量）：
     * `mapper.selectList(FilterUtil.buildQuery(1))`——嵌套工厂形态。
     */
    fun testReproHelperResultConsumedInline() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    mapper.selectList(FilterUtil.buildQuery(1));
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Integer status;
                }

                static class FilterUtil {
                    static LambdaQueryWrapper<DeviceInfoEntity> buildQuery(Integer status) {
                        LambdaQueryWrapper<DeviceInfoEntity> q = new LambdaQueryWrapper<>();
                        q.eq(DeviceInfoEntity::getStatus, status);
                        return q;
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(
            "工具类结果内联消费：q.eq 应被收集",
            listOf("eq"),
            callsAtConsumer(),
        )
    }

    /**
     * REPRO-11：验证「逻辑位置锚定」与「源码位置分段」两套机制冲突的推断。
     *
     * 与 REPRO-4（返回 wrapper 再赋值）语料**完全相同**，唯一差别是工具类写在 Demo 类**之前**。
     * 若 REPRO-4 的实测 `[ne]` 是因为 `expandHelperCalls` 把展开调用锚在 helper 调用点、
     * 而调用点偏移又恰好被 `filterCallsToAnchorSegment` 当成段首切掉，那么调整类顺序后
     * 结果应当变化（甚至变绿）——这就是「位置机制互相打架」的直接证据。
     *
     * 这条不预设绿红，只用来取证；实测结果回填计划 §二。
     */
    fun testReproOrderDependenceProbe() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                static class DeviceInfoEntity {
                    private Long id;
                    private Integer status;
                }

                static class FilterUtil {
                    static LambdaQueryWrapper<DeviceInfoEntity> decorate(
                            LambdaQueryWrapper<DeviceInfoEntity> w, Integer status) {
                        return w.ne(DeviceInfoEntity::getStatus, status);
                    }
                }

                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<>();
                    w.eq(DeviceInfoEntity::getId, 1L);
                    w = FilterUtil.decorate(w, 1);
                    mapper.selectList(w);
                }
            }
            """.trimIndent(),
        )
        assertEquals(
            "类顺序变化后 eq 不应消失（与 REPRO-4 对照）",
            listOf("eq", "ne"),
            callsAtConsumer(),
        )
    }

    /**
     * REPRO-12：嵌套 lambda **代码块体**里调用辅助方法。
     *
     * `resolveNestedLambda` 的代码块分支把块内**所有** `PsiMethodCallExpression` 都收进链
     * （`ArgResolver.kt:181-184`，无 Wrapper 方法判定），因此 `helper(foo)` 这种非条件调用
     * 会被当成条件方法——与 REPRO-5 的 `[buildQuery]` 同源的伪条件面，但这里在嵌套 lambda 里。
     */
    fun testReproNestedLambdaBlockWithHelperCall() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<>();
                    w.eq(DeviceInfoEntity::getId, 1L);
                    w.and(x -> {
                        x.ne(DeviceInfoEntity::getStatus, 1);
                        trim();
                    });
                    mapper.selectList(w);
                }

                private static void trim() { }

                static class DeviceInfoEntity {
                    private Long id;
                    private Integer status;
                }
            }
            """.trimIndent(),
        )
        val calls = callsAtConsumer()
        assertTrue("嵌套链至少应收集外层 eq 与内层 ne：$calls", calls.containsAll(listOf("eq", "ne")))
        assertTrue("块内非条件调用 trim 不得进链：$calls", "trim" !in calls)
    }

    /**
     * REPRO-13：自研 Wrapper 子类**新增的链方法**（`w.statusEq(...)`）。
     *
     * 侦察结论（`QueryWrapperParser.kt:252`）：解析器 `when` 只枚举约 60 个官方方法名、
     * **无 else 分支也无日志**，未知方法被静默丢弃。这与 R1~R6 的「跨方法丢条件」是**两条独立路径**：
     *  - R1~R6：条件调用根本没被收集进链（WrapperLocator 层）
     *  - R13：调用被收集了，但下游解析器不认这个方法名（QueryWrapperParser 层）
     *
     * 用户原话「稍微复杂一点的逻辑就被舍弃」同时命中这两条——自研封装通常会提供
     * `statusEq`/`byKeyword` 这类语义化链方法，它们在收集层能过、在解析层全丢。
     */
    fun testReproCustomChainMethodDroppedByParser() {
        // LightPlatform 下真实 MP 不在 classpath，自研子类要能 extends 到 MP 基类必须先造桩
        // （与 WrapperTypeSuperChainTest.addFakeMpLambdaWrapper 同源做法）
        addFakeMpLambdaWrapper()
        myFixture.addFileToProject(
            "com/song/repro/MyWrapper.java",
            """
            package com.song.repro;

            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class MyWrapper<T> extends LambdaQueryWrapper<T> {
                public MyWrapper<T> statusEq(Integer status) {
                    eq("status", status);
                    return this;
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/song/repro/DeviceInfoEntity.java",
            """
            package com.song.repro;

            public class DeviceInfoEntity {
                private Long id;
                private Integer status;
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Demo.java",
            """
            import com.song.repro.MyWrapper;
            import com.song.repro.DeviceInfoEntity;

            public class Demo {
                public void query() {
                    MyWrapper<DeviceInfoEntity> w = new MyWrapper<>();
                    w.eq(DeviceInfoEntity::getId, 1L);
                    w.statusEq(1);
                    mapper.selectList(w);
                }
            }
            """.trimIndent(),
        )
        val calls = callsAtConsumer()
        // 条件方法名能进链（收集层 OK）
        assertTrue("statusEq 应被收集进链：$calls", "statusEq" in calls)
        // 但 SQL 产物里必须有 status 条件（解析层是否认得）
        val sql = sqlAtConsumer()
        assertTrue("statusEq 语义应落成 status 条件，实际 SQL：\n$sql", sql.contains("status"))
    }

    /** 与 `WrapperTypeSuperChainTest.addFakeMpLambdaWrapper` 同源：造 MP 基类桩。 */
    private fun addFakeMpLambdaWrapper() {
        val path = "com/baomidou/mybatisplus/core/conditions/query/LambdaQueryWrapper.java"
        if (myFixture.findFileInTempDir(path) != null) return
        myFixture.addFileToProject(
            path,
            """
            package com.baomidou.mybatisplus.core.conditions.query;

            public abstract class LambdaQueryWrapper<T> {
                public LambdaQueryWrapper<T> eq(String column, Object value) { return this; }
            }
            """.trimIndent(),
        )
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /**
     * 锚定到消费调用（`mapper.selectList(w)`），与用户「在业务行触发预览」的路径一致；
     * 返回该锚点定位到的链上方法名序列（按链内逻辑顺序）。
     *
     * 找不到锚点或定位不到链时直接 fail，避免「没定位到」被误读成「条件为空」。
     */
    private fun callsAtConsumer(): List<String> {
        val anchor = consumerAnchor()
        val located = WrapperLocator.locateWithTable(anchor)
            ?: fail("在消费行定位不到 Wrapper 链（locateWithTable 返回 null）").let { return emptyList() }
        return located.chain.calls.map { it.methodName }
    }

    /** 文件里唯一一个 `selectList` 调用（消费行）。 */
    private fun consumerAnchor(): PsiElement {
        val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .firstOrNull { it.methodExpression.referenceName == "selectList" }
        assertNotNull("测试文件缺少 selectList 消费调用", call)
        return call!!
    }

    /** 打印完整 SQL（排障时看产物，比只看方法名更直观）。 */
    @Suppress("unused")
    private fun sqlAtConsumer(): String {
        val located = WrapperLocator.locateWithTable(consumerAnchor()) ?: return "<no chain>"
        val table = located.table ?: return "<no table>"
        return SqlGenerator.generate(QueryWrapperParser(table).parse(located.chain))
    }
}
