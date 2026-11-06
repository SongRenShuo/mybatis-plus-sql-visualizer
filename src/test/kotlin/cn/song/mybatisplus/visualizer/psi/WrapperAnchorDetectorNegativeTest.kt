package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiKeyword
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Wrapper 锚点**负例**回归集（M38.1 / P-立项-06 A1）。
 *
 * 来源：Mybatis-Flex-Helper 的真实差评——import 行也弹 gutter 图标（锚点误报）。
 * 本矩阵用**完整真实源码文件**锁定「不该有锚点的位置必须零锚点」，防止判定器演进时回归。
 *
 * harness 与 WrapperAnchorDetectorTest 的 collectedAnchors 同款：全文件叶子扫描
 * （daemon 快速路径传入的元素类型 = PsiIdentifier + PsiKeyword），整体不变量断言。
 */
class WrapperAnchorDetectorNegativeTest : BasePlatformTestCase() {

    private fun collectedAnchors(): Set<String> {
        val anchors = mutableSetOf<String>()
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .forEach { anchors += (WrapperAnchorDetector.anchorElement(it)?.text ?: return@forEach) }
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiKeyword::class.java)
            .forEach { anchors += (WrapperAnchorDetector.anchorElement(it)?.text ?: return@forEach) }
        return anchors
    }

    // ========== Java：import / 注释 / 字符串 / 泛型实参 / 同名方法 ==========

    fun testJavaKeywordTextInImportCommentStringAndTypeArgsHasNoAnchor() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            /**
             * Javadoc 提及链式写法：Wrappers.lambdaQuery().eq(User::getId, 1L) 仅供参考。
             */
            public class Demo {
                // 行注释：new LambdaQueryWrapper<User>().eq(User::getId, 1L) 不得产生图标
                private static final String DOC_SQL = "new QueryWrapper<User>().eq(\"id\", 1L)";

                public void plain() {
                    /* 块注释：Wrappers.<User>lambdaQuery().like(User::getName, "n") */
                    String s = "Wrappers.lambdaQuery().eq(User::getId, 1L)";
                }
            }
            """,
        )
        val anchors = collectedAnchors()
        assertTrue("import/注释/字符串内的链文本不应产生任何锚点，实际: $anchors", anchors.isEmpty())
    }

    fun testJavaGenericTypeArgumentPositionHasNoAnchor() {
        myFixture.configureByText(
            "Demo.java",
            """
            import java.util.List;
            import java.util.ArrayList;

            public class Demo {
                public void store() {
                    List<LambdaQueryWrapper<User>> list = new ArrayList<>();
                    list.add(null);
                }

                static class User { private Long id; }
            }
            """,
        )
        val anchors = collectedAnchors()
        assertTrue("泛型实参位 LambdaQueryWrapper 不应产生锚点，实际: $anchors", anchors.isEmpty())
    }

    fun testJavaNonFactoryQualifierAndPlainMethodCallHaveNoAnchor() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void plain() {
                    // qualifier 不是 Wrappers/ChainWrappers 工厂类：即使方法名撞名也不标
                    new Helper().lambdaQuery();
                    // 与条件方法撞名的普通对象方法调用不标
                    new Helper().eq("a");
                    // 批量方法名空参调用不标
                    this.updateBatchById();
                }

                static class Helper {
                    public Object lambdaQuery() { return this; }
                    public Object eq(String v) { return v; }
                    public Object updateBatchById() { return this; }
                }
            }
            """,
        )
        val anchors = collectedAnchors()
        assertTrue("同名方法/空参批量调用不应产生锚点，实际: $anchors", anchors.isEmpty())
    }

    fun testJavaAbstractBaseTypeVariableWithoutWrapperInitHasNoAnchor() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.Wrapper;

            public class Demo {
                public void plain() {
                    // 抽象基类型声明 + 非 wrapper 初始化表达式：降级判定不得放行
                    Wrapper<User> w = build();
                }

                Wrapper<User> build() { return null; }

                static class User { private Long id; }
            }
            """,
        )
        val anchors = collectedAnchors()
        assertTrue("基类型变量 + 非 wrapper 初始化不应产生锚点，实际: $anchors", anchors.isEmpty())
    }

    // ========== T-WriteFamily（R4）：写族名字撞名的非 MP receiver 负例 ==========

    fun testNonMpReceiversWithDeleteRemoveNullHaveNoAnchor() {
        // R4.5 不变量：`delete` / `remove` 是通用方法名——非 MP CRUD receiver 上即使实参是 null
        // 也不得亮图标（宁可漏标不可误标）。`redisTemplate.delete(null)` / `cache.delete(null)` /
        // `list.remove(null)`（`List.remove(Object)` 合法且常见）/ `list.remove(0)`。
        myFixture.configureByText(
            "Demo.java",
            """
            import java.util.List;

            public class Demo {
                private RedisTemplate<String, String> redisTemplate;
                private LocalCache cache;

                public void ops(List<String> list) {
                    redisTemplate.delete(null);
                    cache.delete(null);
                    list.remove(null);
                    list.remove(0);
                }

                static class RedisTemplate<K, V> {}

                static class LocalCache {
                    public boolean delete(Object o) { return false; }
                }
            }
            """,
        )
        val anchors = collectedAnchors()
        assertTrue(
            "非 MP CRUD receiver 上的 delete/remove 不应产生任何锚点，实际: $anchors",
            anchors.isEmpty(),
        )
    }

    // ========== Kotlin：文本级行匹配分支（F-4 MVP 高危区） ==========

    fun testKotlinCommentStringImportAndNoOpLinesHaveNoAnchor() {
        myFixture.configureByText(
            "Demo.kt",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper

            // 注释行：val w = ktQuery().eq("a", 1) 不得产生图标
            class Demo {
                fun plain() {
                    val doc = "ktQuery().eq(\"a\", 1)"
                    // 无操作的单独工厂调用：不满足链确认条件
                    val factory = ktQuery()
                }
            }
            """,
        )
        val anchors = mutableSetOf<String>()
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .forEach { anchors += (WrapperAnchorDetector.anchorElement(it)?.text ?: return@forEach) }
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiKeyword::class.java)
            .forEach { anchors += (WrapperAnchorDetector.anchorElement(it)?.text ?: return@forEach) }
        // 注释/字符串叶子不是 identifier，文本匹配按元素全文判定，天然排除
        assertNull(
            "import 行的 LambdaQueryWrapper 标识符不应产生锚点",
            WrapperAnchorDetector.anchorElement(myFixture.file.findElementAt(myFixture.file.text.indexOf("LambdaQueryWrapper"))!!),
        )
        assertTrue("Kotlin 注释/字符串/无操作行不应产生锚点，实际: $anchors", anchors.isEmpty())
    }
}
