package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

/**
 * T-JumpCapture / O-66 T-GuideEnhancedFirst：[SourceCaptureGuide] 引导编排的纯函数与文案落地测试。
 *
 * 弹框本体（Messages.showDialog 模态）headless 不可测，家规「UI 只做编排 + 纯函数单测」：
 *  - [SourceCaptureGuide.decideActions] 全局态 → 引导分支（未开启 → EnhanceFirst，已开启 → Legacy）；
 *  - [SourceCaptureGuide.resolveChoice] 返回码 → 选择的映射**带分支序表**（两条序表 0 号语义不同），
 *    关闭/负值兜底取消；
 *  - [SourceCaptureGuide.legacyMessage] Legacy 分支附增强捕获实时状态一行（D1 定案）；
 *  - 引导弹框与注入结果全部键在 bundle 有真实文案（缺键回退键名原样显示 = UI 事故）；
 *  - 实时面板 MissingSource（引导）与 NoSource（MP COUNT 纯提示）语义边界由
 *    SqlLogPanelLiveJumpWithSourceTest 锁定，本测试补键面（noSource 文案已换 MP COUNT 语义）。
 */
class SourceCaptureGuideChoiceTest : BasePlatformTestCase() {

    fun test分支判定_未开启走EnhanceFirst() {
        assertEquals(SourceCaptureGuide.GuideBranch.EnhanceFirst, SourceCaptureGuide.decideActions(false))
    }

    fun test分支判定_已开启走Legacy() {
        assertEquals(SourceCaptureGuide.GuideBranch.Legacy, SourceCaptureGuide.decideActions(true))
    }

    fun test返回码映射随分支序表() {
        // EnhanceFirst：0=开启增强捕获（默认按钮让位），1=立即注入
        assertEquals(SourceCaptureGuide.GuideChoice.EnableEnhanced, SourceCaptureGuide.resolveChoice(0, SourceCaptureGuide.GuideBranch.EnhanceFirst))
        assertEquals(SourceCaptureGuide.GuideChoice.InjectNow, SourceCaptureGuide.resolveChoice(1, SourceCaptureGuide.GuideBranch.EnhanceFirst))
        // Legacy：维持 T-JumpCapture 原序表，0=打开设置
        assertEquals(SourceCaptureGuide.GuideChoice.OpenSettings, SourceCaptureGuide.resolveChoice(0, SourceCaptureGuide.GuideBranch.Legacy))
        assertEquals(SourceCaptureGuide.GuideChoice.InjectNow, SourceCaptureGuide.resolveChoice(1, SourceCaptureGuide.GuideBranch.Legacy))
    }

    fun test取消关闭与负值一律Cancel() {
        for (branch in listOf(SourceCaptureGuide.GuideBranch.EnhanceFirst, SourceCaptureGuide.GuideBranch.Legacy)) {
            assertEquals(SourceCaptureGuide.GuideChoice.Cancel, SourceCaptureGuide.resolveChoice(2, branch))
            // 关闭对话框（-1）与任何异常返回码必须兜底取消，不得误触增强/设置/注入
            assertEquals(SourceCaptureGuide.GuideChoice.Cancel, SourceCaptureGuide.resolveChoice(-1, branch))
            assertEquals(SourceCaptureGuide.GuideChoice.Cancel, SourceCaptureGuide.resolveChoice(99, branch))
        }
    }

    fun testLegacy文案无状态读数时原样() {
        assertEquals("基础文案", SourceCaptureGuide.legacyMessage("基础文案", null))
        assertEquals("基础文案", SourceCaptureGuide.legacyMessage("基础文案", ""))
        assertEquals("基础文案", SourceCaptureGuide.legacyMessage("基础文案", "  "))
    }

    fun testLegacy文案有状态读数时附增强捕获状态行() {
        val withStatus = SourceCaptureGuide.legacyMessage("基础文案", "已挂载 pid=42")
        assertTrue("应以基础文案开头：$withStatus", withStatus.startsWith("基础文案"))
        assertTrue("应复用 log.diag.enhanced 键带「增强捕获」前缀：$withStatus", withStatus.contains("Enhanced capture") || withStatus.contains("增强捕获"))
        assertTrue("应带出状态读数：$withStatus", withStatus.contains("pid=42"))
    }

    fun test引导弹框键有真实文案() {
        for (key in listOf(
            "log.jump.guide.title",
            "log.jump.guide.message",
            "log.jump.guide.openSettings",
            "log.jump.guide.injectNow",
            "log.jump.guide.cancel",
        )) {
            val text = MpSqlVisualizerBundle.message(key)
            assertNotEquals("键 $key 缺 bundle 文案（会原样显示键名）", key, text)
            assertTrue("键 $key 文案为空", text.isNotBlank())
        }
    }

    fun test增强捕获优先分支键有真实文案() {
        for (key in listOf(
            "log.jump.guide.enableEnhanced",
            "log.jump.guide.message.enhanceFirst",
            "log.jump.guide.enhanceDone",
        )) {
            val text = MpSqlVisualizerBundle.message(key)
            assertNotEquals("键 $key 缺 bundle 文案（会原样显示键名）", key, text)
            assertTrue("键 $key 文案为空", text.isNotBlank())
        }
        // enhanceFirst 文案应把增强捕获排为首选项并交代「重启应用后生效」，不得再指引「打开设置」
        // （该分支没有打开设置按钮，文案与按钮必须一致）
        val text = MpSqlVisualizerBundle.message("log.jump.guide.message.enhanceFirst")
        assertTrue("enhanceFirst 文案应推荐增强捕获：$text", text.contains("增强捕获") || text.contains("Enhanced capture"))
        assertTrue("enhanceFirst 文案应交代重启生效：$text", text.contains("重启") || text.contains("restart"))
    }

    fun test注入结果键有真实文案() {
        for (key in listOf(
            "log.inject.title",
            "log.inject.alreadyExists",
            "log.inject.unsupported",
            "log.inject.unsupported.enhanceFirst",
            "log.inject.cannotDetect",
            "log.inject.noProject",
        )) {
            val text = MpSqlVisualizerBundle.message(key)
            assertNotEquals("键 $key 缺 bundle 文案（会原样显示键名）", key, text)
            assertTrue("键 $key 文案为空", text.isNotBlank())
        }
        // success 带三参格式化：占位符全部替换、无残留
        val formatted = MpSqlVisualizerBundle.message("log.inject.success", "A.java", "B.java", "com.example")
        assertNotEquals("log.inject.success", formatted)
        assertTrue("success 文案参数未全部就位：$formatted", !formatted.contains("{0}") && !formatted.contains("{1}") && !formatted.contains("{2}"))
    }

    fun test注入不支持文案已去手动配置指引并转向增强捕获() {
        // 20260924 定案：识别不了项目类型不再让用户参考手动配置，转向引导开启增强捕获
        val base = MpSqlVisualizerBundle.message("log.inject.unsupported")
        assertFalse("基础文案不应再含手动配置指引：$base", base.contains("手动配置") || base.contains("Manual setup"))
        assertFalse("基础文案不应再含 docs 示例路径：$base", base.contains("docs/"))
        val enhanceFirst = MpSqlVisualizerBundle.message("log.inject.unsupported.enhanceFirst")
        assertTrue("enhanceFirst 文案应推荐增强捕获：$enhanceFirst", enhanceFirst.contains("增强捕获") || enhanceFirst.contains("Enhanced capture"))
        assertTrue("enhanceFirst 文案应交代重启生效：$enhanceFirst", enhanceFirst.contains("重启") || enhanceFirst.contains("restart"))
        assertTrue("enhanceFirst 文案不应再含手动配置指引：$enhanceFirst", !enhanceFirst.contains("手动配置") && !enhanceFirst.contains("Manual setup"))
    }

    fun test多模块聚合成功键有真实文案() {
        // T-JumpPath-04：successMulti 四参（模块数、模块清单、两个文件名），双语 parity 由守卫测试兜底
        val formatted = MpSqlVisualizerBundle.message(
            "log.inject.successMulti", "2", "mod-a、mod-b", "SqlSourceInterceptor.java", "SqlVisualizerConfig.java",
        )
        assertNotEquals("log.inject.successMulti", formatted)
        assertTrue("successMulti 文案参数未全部就位：$formatted", (0..3).none { formatted.contains("{$it}") })
    }

    fun test拦截器升级键有真实文案() {
        // T-IntrUpg-03：upgraded 两参（模块数、模块清单）；「旧版」「更新」语义是升级链路的用户承诺
        val formatted = MpSqlVisualizerBundle.message("log.inject.upgraded", "1", "mybatis-plus-join-demo")
        assertNotEquals("log.inject.upgraded 缺 bundle 文案", "log.inject.upgraded", formatted)
        assertTrue("upgraded 文案参数未全部就位：$formatted", formatted.contains("1") && formatted.contains("mybatis-plus-join-demo"))
        assertTrue("upgraded 文案应说明旧版文件已更新：$formatted", formatted.contains("旧版") || formatted.contains("legacy"))
    }

    fun testMP计数纯提示文案不含拦截器引导语义() {
        // 防回归：NoSource（MP COUNT）文案若被改回「未记录源码位置」类表述，会与 MissingSource 引导混淆
        val text = MpSqlVisualizerBundle.message("log.panel.jumpToSource.noSource")
        assertTrue("COUNT 提示应说明分页插件自动发出：$text", text.contains("COUNT") || text.contains("分页"))
    }
}
