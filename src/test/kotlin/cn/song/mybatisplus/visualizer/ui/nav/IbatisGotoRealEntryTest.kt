package cn.song.mybatisplus.visualizer.ui.nav

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * T-IbatisFix3 缺陷二：iBatis DAO 调用的 **Ctrl+Click / Ctrl+B 真实分派链路**回归守卫。
 *
 * ## 为什么要这么写（本轮最核心的教训）
 *
 * 用户 2026.3 真机报「Ctrl+鼠标左键无法跳转」，但既有的 15 例 handler 单测全绿——因为那些用例
 * **直调** `handler.getGotoDeclarationTargets(...)`，而真实路径是平台 GTDU 管线：
 * `GotoDeclarationOrUsageHandler2.gotoDeclarationOrUsages` → `GTDProviderData`（收集各 provider，
 * 含 `GotoDeclarationHandler` 的 legacy 适配）→ `GtdKt.toGTDUActionData` → `Navigatable.navigationRequest`。
 * **直调内部入口绿 ≠ 真实分派路径绿**。
 *
 * ## 两个必须踩对的形态
 *
 * 1. **必须在真正的后台线程上跑**：`Navigatable.navigationRequest` 有 `assertBackgroundThread`；
 *    在 EDT 上跑会抛 `Access from Event Dispatch Thread (EDT) is not allowed`，且该异常被平台的
 *    `recordErrorsLoggedInTheCurrentThreadAndReportThemAsFailures` 记成**测试失败**（不是被我们 catch 掉就行）。
 * 2. **`ReadAction.nonBlocking{}.executeSynchronously()` 在 EDT 上会被内联执行**（平台防死锁优化），
 *    仍在 EDT —— 必须显式 `executeOnPooledThread` + 闩锁等待。
 *
 * ## 断言口径
 *
 * - `GotoDeclarationAction.findAllTargetElements` 能拿到**恰好 1 个**目标，且是 sqlMap 里的
 *   `XmlAttributeValue`（语句 `id` 属性值元素）；
 * - GTDU 判定为 `GTD`（跳声明，不是 USAGES/无候选）；
 * - `getCtrlMouseData` 非空——这是「Ctrl+Click 有反应」的平台级判据。
 *
 * 本用例**不 mask EP**：直接用 plugin.xml 真实注册的 handler（生产形态）。
 */
class IbatisGotoRealEntryTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        IbatisXmlStatementTargets.clearCachesForTest()
    }

    override fun tearDown() {
        try {
            IbatisXmlStatementTargets.clearCachesForTest()
        } finally {
            super.tearDown()
        }
    }

    private fun addSqlMap(): PsiFile = myFixture.addFileToProject(
        "sqlmap/UserSqlMap.xml",
        "<sqlMap namespace=\"user\"><select id=\"selectById\">SELECT 1</select></sqlMap>",
    )

    private fun openDao(): PsiFile = myFixture.configureByText(
        JavaFileType.INSTANCE,
        """
        package com.demo;
        public class UserDao {
            private Object client;
            public Object byDotted(String p) { return client.queryForObject("user.selectById", p); }
        }
        """.trimIndent(),
    )

    private fun callOf(file: PsiFile): PsiMethodCallExpression =
        PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java).first()

    /** 真实入口三连（BGT 上跑，见类 KDoc 第 1、2 条）。 */
    private fun realEntryTriple(offset: Int): Triple<Array<PsiElement>?, Any?, Any?> {
        val ref = AtomicReference<Triple<Array<PsiElement>?, Any?, Any?>>(Triple(null, "NOT_RUN", null))
        val latch = CountDownLatch(1)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ref.set(
                    ReadAction.compute<Triple<Array<PsiElement>?, Any?, Any?>, Throwable> {
                        val all = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, offset)
                        val outcome = GotoDeclarationOrUsageHandler2.testGTDUOutcome(
                            myFixture.editor,
                            myFixture.file,
                            offset,
                        )
                        val ctrlMouse = GotoDeclarationAction().getCtrlMouseData(
                            myFixture.editor,
                            myFixture.file,
                            offset,
                        )
                        Triple(all, outcome, ctrlMouse)
                    },
                )
            } finally {
                latch.countDown()
            }
        }
        latch.await(60, TimeUnit.SECONDS)
        return ref.get()
    }

    fun testDAO调用行_Ctrl点击走平台真实链路能拿到声明目标() {
        addSqlMap()
        val java = openDao()
        val literal = callOf(java).argumentList.expressions.first() as PsiLiteralExpression
        val nameElement = callOf(java).methodExpression.referenceNameElement!!

        for ((label, element) in listOf("方法名" to nameElement as PsiElement, "语句id字面量" to literal as PsiElement)) {
            myFixture.editor.caretModel.moveToOffset(element.textOffset + 1)
            val offset = myFixture.editor.caretModel.offset
            val (all, outcome, ctrlMouse) = realEntryTriple(offset)

            assertNotNull("[$label] findAllTargetElements 不得为空——Ctrl+B/Ctrl+Click 靠它取目标", all)
            assertEquals("[$label] 应恰 1 个目标（多目标会让平台弹候选列表，用户观感是「没跳」）", 1, all!!.size)
            assertTrue(
                "[$label] 目标应是 sqlMap 里语句 id 的属性值元素，实际=${all[0].javaClass.simpleName}",
                all[0] is XmlAttributeValue,
            )
            assertEquals("[$label] sqlMap 文件命中", "UserSqlMap.xml", all[0].containingFile?.name)
            assertEquals("[$label] GTDU 应判 GTD（跳声明）", "GTD", outcome.toString())
            assertNotNull("[$label] Ctrl+Click 必须有数据（null = 平台认为该位置不可点）", ctrlMouse)
        }
    }
}
