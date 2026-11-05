package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator
import cn.song.mybatisplus.visualizer.reverse.SqlToJavaConverter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * SqlToJavaPanel caller/invocation, batch-convert, class-picker special values and result-type tests (split from SqlToJavaPanelTest).
 * （T-M53-II 2026-10-10：逐字符原样搬运，零逻辑改动。）
 */
class SqlToJavaPanelCallerAndResultTypeTest: BasePlatformTestCase() {

    fun testCallerAndVoGenerateInvocation() {
        val state = PluginSettings.getInstance().state
        val original = state.reverseWrapperFormat
        try {
            state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
            panel.refreshFormatFromSettings()

            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'")
            // 调用类输入（测试访问器直接写文本框）
            panel.callerFieldForTest().text = "UserService"
            panel.voFieldForTest().text = "UserVo"
            panel.convertForTest()
            val result = panel.resultTextForTest()
            // Light 环境无 MPJ 接口 → 调用类无 MPJ 能力 → VO 回落实体查询并注释（严禁生成不存在的 API）
            assertTrue(result, result.contains("QueryWrapper<User> wrapper = new QueryWrapper<User>()"))
            assertTrue(result, result.contains("List<User> list = userService.list(wrapper);"))
            assertTrue(result, result.contains("// 调用类无 MyBatis-Plus-Join（MPJ）能力，VO 查询不可用，已回落实体查询"))
            assertTrue(result, !result.contains("selectJoinList"))

            // 清空 VO → 普通 list 调用
            panel.voFieldForTest().text = ""
            panel.convertForTest()
            val plain = panel.resultTextForTest()
            assertTrue(plain, plain.contains("List<User> list = userService.list(wrapper);"))
        } finally {
            state.reverseWrapperFormat = original
        }
    }

    fun testCallerWithMpjInjectedGeneratesSelectJoinList() {
        // MPJ 能力真实解析依赖索引（Light 不可测，见 InvocationMpjHeavyTest）——此处注入验证面板接线：
        // callerMpj=true → buildInvocation 携带 mpj → VO 查询生成 selectJoinList + MPJ wrapper
        val state = PluginSettings.getInstance().state
        val original = state.reverseWrapperFormat
        try {
            state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
            panel.refreshFormatFromSettings()
            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'")
            panel.callerFieldForTest().text = "UserService"
            panel.voFieldForTest().text = "UserVo"
            panel.setCallerMpjForTest(true)
            panel.convertForTest()
            val result = awaitResult()
            assertTrue(result, result.contains("new MPJQueryWrapper<>(User.class)"))
            assertTrue(result, result.contains("List<UserVo> list = userService.selectJoinList(UserVo.class, wrapper);"))
        } finally {
            state.reverseWrapperFormat = original
            panel.callerFieldForTest().text = ""
            panel.voFieldForTest().text = ""
            panel.setCallerMpjForTest(false)
        }
    }


    fun testPagedQueryGeneratesPageCall() {
        // 分页 SQL（LIMIT ?, ?）+ 调用类 → 生成 page 调用（不再 last + list）
        val state = PluginSettings.getInstance().state
        val original = state.reverseWrapperFormat
        try {
            state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
            panel.refreshFormatFromSettings()
            panel.inputEditorForTest().setSql("SELECT * FROM user WHERE id = 1 LIMIT ?, ?")
            panel.callerFieldForTest().text = "UserService"
            panel.convertForTest()
            val result = awaitResult()
            assertTrue(result, result.contains("Page<User> page = userService.page(new Page<>(1, 10), wrapper);"))
            assertTrue(result, !result.contains(".last(\"LIMIT"))
        } finally {
            state.reverseWrapperFormat = original
            panel.callerFieldForTest().text = ""
        }
    }

    // ------------------------------------------------------------------
    // T-M45：复制剥离注释 + 格式化代码（结果区工具栏）
    // ------------------------------------------------------------------

    fun testCopyTextStripsComments() {
        // 转换含说明注释的代码 → 复制文本 = 纯 Java 代码（无 // 注释行）
        val state = PluginSettings.getInstance().state
        val original = state.reverseWrapperFormat
        try {
            state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
            panel.refreshFormatFromSettings()
            panel.inputEditorForTest().setSql("SELECT * FROM user WHERE id = 1 LIMIT ?, ?")
            panel.callerFieldForTest().text = "UserService"
            panel.convertForTest()
            val result = awaitContains("分页 current/size 已占位")
            // 结果区显示仍带注释（行注释形态——独立行）
            assertTrue(result, result.contains("// 分页 current/size 已占位，请替换为实际参数"))
            // 复制文本剥离全部注释，保留代码
            val copy = panel.copyTextForTest()
            assertFalse(copy, copy.contains("//"))
            assertTrue(copy, copy.contains("Page<User> page = userService.page(new Page<>(1, 10), wrapper);"))
        } finally {
            state.reverseWrapperFormat = original
            panel.callerFieldForTest().text = ""
        }
    }

    fun testFormatCodeFormatsResult() {
        // 结果区内容经「格式化代码」后代码完整保留；连续格式化结果稳定（幂等）
        val state = PluginSettings.getInstance().state
        val original = state.reverseWrapperFormat
        try {
            state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
            panel.refreshFormatFromSettings()
            val columns = (1..30).joinToString(", ") { "c$it" }
            panel.inputEditorForTest().setSql("SELECT $columns FROM user")
            panel.convertForTest()
            val result = awaitResult()
            assertTrue(result, result.contains(".select(\"c1\", \"c2\""))
            panel.formatCodeForTest()
            val formatted = panel.resultTextForTest()
            // 格式化不丢代码（wrapper 构造 + select 链完整）
            assertTrue(formatted, formatted.contains("new QueryWrapper<User>()"))
            assertTrue(formatted, formatted.contains(".select(\"c1\""))
            // 幂等：再格式化结果不变
            panel.formatCodeForTest()
            assertEquals(formatted, panel.resultTextForTest())
        } finally {
            state.reverseWrapperFormat = original
        }
    }

    // ------------------------------------------------------------------
    // T-BatchSql：批量 SQL → Java 转换（面板 fixture）
    // ------------------------------------------------------------------

    fun testBatchConvertTwoStatementsShowsTwoCodeBlocks() {
        // 2 条有效 SQL → 结果区显示 2 段代码（双换行分隔）
        val state = PluginSettings.getInstance().state
        val originalFormat = state.reverseWrapperFormat
        val originalManual = state.reverseManualEntitySelection
        state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
        state.reverseManualEntitySelection = false
        try {
            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'; SELECT id FROM user WHERE age > 18")
            panel.convertForTest()
            val result = awaitResult()
            assertTrue(result, result.contains("new QueryWrapper<User>()"))
            val wrapperCount = result.split("new QueryWrapper").size - 1
            assertEquals("应生成 2 个 QueryWrapper", 2, wrapperCount)
        } finally {
            state.reverseWrapperFormat = originalFormat
            state.reverseManualEntitySelection = originalManual
        }
    }

    fun testBatchConvertPartialFailureShowsStatus() {
        // 1 有效 + 1 无效 → 状态行显示「1/2 条成功」
        val state = PluginSettings.getInstance().state
        val originalFormat = state.reverseWrapperFormat
        val originalManual = state.reverseManualEntitySelection
        state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
        state.reverseManualEntitySelection = false
        try {
            panel.inputEditorForTest().setSql("SELECT id FROM user WHERE id = 1; INVALID SQL")
            panel.convertForTest()
            val result = awaitResult()
            assertTrue(result, result.contains("new QueryWrapper<User>()"))
            assertTrue(result, result.contains("// 第 2 条 SQL 转换失败"))
            assertTrue(panel.statusForTest(), panel.statusForTest().contains("1/2"))
        } finally {
            state.reverseWrapperFormat = originalFormat
            state.reverseManualEntitySelection = originalManual
        }
    }

    fun testSingleStatementDoesNotEnterBatchPath() {
        // 单条 SQL → 走现有单条路径（缓存生效，状态行显示「未找到对应实体」）
        val state = PluginSettings.getInstance().state
        val originalManual = state.reverseManualEntitySelection
        state.reverseManualEntitySelection = false
        try {
            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'")
            panel.convertForTest()
            val result = awaitResult()
            assertTrue(result, result.contains("new QueryWrapper<User>()"))
            assertTrue(panel.statusForTest(), panel.statusForTest().contains("未找到对应实体"))
        } finally {
            state.reverseManualEntitySelection = originalManual
        }
    }

    // ------------------------------------------------------------------
    // 调用类特殊值（T-M42：this / baseMapper）
    // ------------------------------------------------------------------

    fun testClassPickerSpecialValuesPickable() {
        val caller = panel.callerFieldForTest()
        // 特殊项「this」：选中 → 文本 = this（字段图标 + 用途说明 tooltip）
        caller.pickForTest("this", null, AllIcons.Nodes.Field)
        assertEquals("this", caller.text)
        assertNotNull("特殊项应显示字段图标", caller.iconForTest())
        caller.clearForTest()
        // 特殊项「baseMapper」：同上
        caller.pickForTest("baseMapper", null, AllIcons.Nodes.Field)
        assertEquals("baseMapper", caller.text)
        assertNotNull("特殊项应显示字段图标", caller.iconForTest())
    }

    fun testThisCallerRegeneratesServiceInvocation() {
        // 选中特殊项 this → 已有转换结果自动重生成 service 形态调用（this.list(wrapper)）
        val state = PluginSettings.getInstance().state
        val original = state.reverseWrapperFormat
        try {
            state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
            panel.refreshFormatFromSettings()
            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'")
            panel.convertForTest()
            assertTrue(panel.resultTextForTest().contains("new QueryWrapper"))
            panel.callerFieldForTest().pickForTest("this", null, AllIcons.Nodes.Field)
            val result = panel.resultTextForTest()
            assertTrue(result, result.contains("List<User> list = this.list(wrapper);"))
            // 清除 → 回到纯表达式
            panel.callerFieldForTest().clearForTest()
            val plain = panel.resultTextForTest()
            assertTrue(plain, !plain.contains("this.list"))
        } finally {
            state.reverseWrapperFormat = original
            panel.callerFieldForTest().text = ""
        }
    }

    fun testBaseMapperCallerRegeneratesMapperInvocation() {
        // 选中特殊项 baseMapper → 自动重生成 mapper 形态调用（baseMapper.selectList(wrapper)）
        val state = PluginSettings.getInstance().state
        val original = state.reverseWrapperFormat
        try {
            state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
            panel.refreshFormatFromSettings()
            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'")
            panel.convertForTest()
            assertTrue(panel.resultTextForTest().contains("new QueryWrapper"))
            panel.callerFieldForTest().pickForTest("baseMapper", null, AllIcons.Nodes.Field)
            val result = panel.resultTextForTest()
            assertTrue(result, result.contains("List<User> list = baseMapper.selectList(wrapper);"))
        } finally {
            state.reverseWrapperFormat = original
            panel.callerFieldForTest().text = ""
        }
    }

    fun testSpecialCallerMpjConsideredAvailableForJoin() {
        // 特殊调用值恒视为具备 MPJ 能力（invocationHasMpj 开头纯字符串分支直出 true，不依赖索引）：
        // 面板注入 mpj=true 后 JOIN 生成不报「无 MPJ 能力」
        val state = PluginSettings.getInstance().state
        val original = state.reverseWrapperFormat
        try {
            state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
            panel.refreshFormatFromSettings()
            panel.setCallerMpjForTest(true)
            panel.inputEditorForTest().setSql(
                "SELECT t.id, t1.city FROM user t LEFT JOIN address t1 ON t1.user_id = t.id WHERE t.status = 1",
            )
            panel.callerFieldForTest().text = "this"
            panel.convertForTest()
            val result = awaitResult()
            assertTrue(result, result.contains("List<User> list = this.list(wrapper);"))
        } finally {
            state.reverseWrapperFormat = original
            panel.callerFieldForTest().text = ""
            panel.setCallerMpjForTest(false)
        }
    }

    fun testInvocationHasMpjSpecialValuesShortCircuit() {
        // T-M42：this/baseMapper 非类名，索引反查必然落空——invocationHasMpj 开头纯字符串分支
        // 直出 true（Light 无索引也可验证；普通类名分支依赖索引见 InvocationMpjHeavyTest）
        assertTrue(SqlToJavaConverter.invocationHasMpj(project, "this"))
        assertTrue(SqlToJavaConverter.invocationHasMpj(project, "baseMapper"))
        // isBlank 守卫仍先于特殊分支：空串 false
        assertFalse(SqlToJavaConverter.invocationHasMpj(project, ""))
    }

    fun testCallerModelGetFullNameReadActionSafe() {
        // T-M44 增量：ChooseByNamePopup.close（EDT）→ getFullName → GotoClassModel2 委托读 PSI 全限定名，
        // 262 平台 stub 断言硬抛（用户实测堆栈 SqlToJavaPanel.kt CallerClassByNameModel.getFullName）——
        // 生产实现已包 ReadAction（EDT 同步执行）；测试在非 read 上下文直接调用（模拟无 read access
        // 的调用线程；unit test 模式平台豁免断言，此测试验证委托行为与特殊项直出，断言缺陷由沙盒/真机兜底）
        val model = panel.callerModelForTest()
        // 特殊项直出（不触 PSI）
        assertEquals("this", model.getFullName("this"))
        // PsiClass 委托：ReadAction 内取全限定名（configureByText 建内存 Java 文件，仿 psi 包测试约定）
        val psiClass = psiClassFixture()
        assertEquals("demo.UserService", model.getFullName(psiClass))
    }

    fun testCallerModelGetElementNameReadActionSafe() {
        val model = panel.callerModelForTest()
        assertEquals("this", model.getElementName("this"))
        val psiClass = psiClassFixture()
        assertEquals("UserService", model.getElementName(psiClass))
    }

    fun testPickPsiClassForTestSelection() {
        // elementChosen 的 PsiClass 分支同一路径：ReadAction 内读取 name/qualifiedName/getIcon 并写入字段
        val psiClass = psiClassFixture()
        panel.pickPsiClassForTest(psiClass)
        assertTrue(panel.callerFieldForTest().text == "UserService")
    }

    /** 内存 Java 类（仿 EntityResolverCommentTest.resolveEntity 约定：configureByText + PsiTreeUtil 取类）。 */
    private fun psiClassFixture(): PsiClass {
        myFixture.configureByText("UserService.java", "package demo; public class UserService {}")
        return PsiTreeUtil.findChildOfType(myFixture.file, PsiClass::class.java)!!
    }

    // ------------------------------------------------------------------
    // T-Mxx：调用类校验——VO/DTO/Utils/Controller 拒绝 + 合法类接受
    // ------------------------------------------------------------------

    fun testVoClassRejected() {
        val psiClass = createPsiClassNamed("UserVO")
        panel.pickPsiClassForTest(psiClass)
        assertTrue("VO 不应被选中", panel.callerFieldForTest().text.isEmpty())
        assertTrue("状态行应显示拒绝提示", panel.statusForTest().contains("VO/DTO"))
    }

    fun testDtoClassRejected() {
        val psiClass = createPsiClassNamed("UserDTO")
        panel.pickPsiClassForTest(psiClass)
        assertTrue("DTO 不应被选中", panel.callerFieldForTest().text.isEmpty())
    }

    fun testBoClassRejected() {
        val psiClass = createPsiClassNamed("UserBO")
        panel.pickPsiClassForTest(psiClass)
        assertTrue("BO 不应被选中", panel.callerFieldForTest().text.isEmpty())
    }

    fun testPoClassRejected() {
        val psiClass = createPsiClassNamed("UserPO")
        panel.pickPsiClassForTest(psiClass)
        assertTrue("PO 不应被选中", panel.callerFieldForTest().text.isEmpty())
    }

    fun testUtilsClassRejected() {
        val psiClass = createPsiClassNamed("StringUtils")
        panel.pickPsiClassForTest(psiClass)
        assertTrue("Utils 不应被选中", panel.callerFieldForTest().text.isEmpty())
    }

    fun testControllerClassRejected() {
        val psiClass = createPsiClassNamed("UserController")
        panel.pickPsiClassForTest(psiClass)
        assertTrue("Controller 不应被选中", panel.callerFieldForTest().text.isEmpty())
    }

    // T-CallerPicker：接口/抽象类拒绝（isInterface/hasModifierProperty 已收进单次 ReadAction，
    // 拒绝矩阵仍须与修复前一致）

    fun testInterfaceClassRejected() {
        // 接口（PsiClass.isInterface 走 stub 属性读取）→ 拒绝；类名不得落入白名单（Service 结尾会被放行）
        val interfaceFile = myFixture.configureByText(
            "IUserAuth.java",
            "package demo; public interface IUserAuth {}",
        )
        val iface = PsiTreeUtil.findChildOfType(interfaceFile, PsiClass::class.java)!!
        panel.pickPsiClassForTest(iface)
        assertTrue("接口不应被选中", panel.callerFieldForTest().text.isEmpty())
        // 中英文 bundle 均含 "Mapper/Service"（语言无关断言；zh 版为「接口/抽象类」）
        assertTrue("状态行应显示接口拒绝提示", panel.statusForTest().contains("Mapper/Service"))
    }

    fun testMapperClassAccepted() {
        val psiClass = createPsiClassNamed("UserMapper")
        panel.pickPsiClassForTest(psiClass)
        assertEquals("UserMapper", panel.callerFieldForTest().text)
    }

    fun testServiceClassAccepted() {
        val psiClass = createPsiClassNamed("UserService")
        panel.pickPsiClassForTest(psiClass)
        assertEquals("UserService", panel.callerFieldForTest().text)
    }

    fun testServiceImplClassAccepted() {
        val psiClass = createPsiClassNamed("UserServiceImpl")
        panel.pickPsiClassForTest(psiClass)
        assertEquals("UserServiceImpl", panel.callerFieldForTest().text)
    }

    fun testImplClassAccepted() {
        val psiClass = createPsiClassNamed("UserImpl")
        panel.pickPsiClassForTest(psiClass)
        assertEquals("UserImpl", panel.callerFieldForTest().text)
    }

    // ------------------------------------------------------------------
    // T-CallerPicker：this/baseMapper tooltip 上下文感知（兼顾 PM + MPJ）
    // ------------------------------------------------------------------

    fun testThisTooltipWithCurrentClass() {
        val currentClass = createPsiClassWithSuper("UserServiceImpl", "ServiceImpl<UserMapper, User>")
        panel.setCurrentEditorClassForTest(currentClass)
        panel.callerFieldForTest().pickSpecialForTest("this")
        assertTrue("tooltip 应含当前类名",
            panel.callerFieldForTest().toolTipText?.contains("UserServiceImpl") == true)
    }

    fun testBaseMapperTooltipWithServiceImpl() {
        // PM 场景：UserServiceImpl extends ServiceImpl<UserMapper, User>
        val currentClass = createPsiClassWithSuper("UserServiceImpl", "ServiceImpl<UserMapper, User>")
        panel.setCurrentEditorClassForTest(currentClass)
        panel.callerFieldForTest().pickSpecialForTest("baseMapper")
        assertTrue("tooltip 应含当前类名",
            panel.callerFieldForTest().toolTipText?.contains("UserServiceImpl") == true)
        assertTrue("tooltip 应含实体信息（第 2 个泛型参数 = User）",
            panel.callerFieldForTest().toolTipText?.contains("User") == true)
    }

    fun testBaseMapperTooltipWithBaseMapper() {
        // PM 场景：独立 UserMapper extends BaseMapper<User>
        val currentClass = createPsiClassWithSuper("UserMapper", "BaseMapper<User>")
        panel.setCurrentEditorClassForTest(currentClass)
        panel.callerFieldForTest().pickSpecialForTest("baseMapper")
        assertTrue("tooltip 应含当前类名",
            panel.callerFieldForTest().toolTipText?.contains("UserMapper") == true)
        assertTrue("tooltip 应含实体信息（第 1 个泛型参数 = User）",
            panel.callerFieldForTest().toolTipText?.contains("User") == true)
    }

    fun testBaseMapperTooltipWithMpjMapper() {
        // MPJ 场景：UserMapper extends MPJBaseMapper<User>
        val currentClass = createPsiClassWithSuper("UserMapper", "MPJBaseMapper<User>")
        panel.setCurrentEditorClassForTest(currentClass)
        panel.callerFieldForTest().pickSpecialForTest("baseMapper")
        assertTrue("tooltip 应含当前类名",
            panel.callerFieldForTest().toolTipText?.contains("UserMapper") == true)
        assertTrue("tooltip 应含实体信息（MPJ 第 1 个泛型参数 = User）",
            panel.callerFieldForTest().toolTipText?.contains("User") == true)
    }

    fun testBaseMapperTooltipNoSuperType() {
        // 无泛型父类 → 仅展示类名，不报错
        val currentClass = createPsiClassNamed("UserMapper")
        panel.setCurrentEditorClassForTest(currentClass)
        panel.callerFieldForTest().pickSpecialForTest("baseMapper")
        assertTrue("tooltip 应含当前类名",
            panel.callerFieldForTest().toolTipText?.contains("UserMapper") == true)
    }

    fun testThisTooltipNoCurrentClass() {
        // 当前无打开 Java 文件 → tooltip 保持原样（无追加）
        panel.callerFieldForTest().pickSpecialForTest("this")
        assertTrue("无当前类时 tooltip 不含「当前：」",
            panel.callerFieldForTest().toolTipText?.contains("当前：") != true)
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    private fun createPsiClassNamed(simpleName: String): PsiClass {
        myFixture.configureByText("$simpleName.java", "package demo; public class $simpleName {}")
        return PsiTreeUtil.findChildOfType(myFixture.file, PsiClass::class.java)!!
    }

    private fun createPsiClassWithSuper(simpleName: String, superDecl: String): PsiClass {
        myFixture.configureByText(
            "$simpleName.java",
            "package demo; public class $simpleName extends $superDecl {}",
        )
        return PsiTreeUtil.findChildOfType(myFixture.file, PsiClass::class.java)!!
    }

    // ------------------------------------------------------------------
    // T-MapResultVo：返回形态下拉联动（Map 列表 → Maps 族调用行）
    // ------------------------------------------------------------------

    fun testResultTypeMapsDeclarationGeneratesSelectJoinMapsCall() {
        val state = PluginSettings.getInstance().state
        val original = state.reverseWrapperFormat
        try {
            state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
            panel.refreshFormatFromSettings()
            panel.inputEditorForTest().setSql(
                "SELECT t.id, t1.city FROM user t LEFT JOIN address t1 ON t1.user_id = t.id WHERE t.status = 1",
            )
            panel.convertForTest()
            awaitResult()
            // 选调用类（QUERY 形态有调用行 Maps 才有载体）——T-FixMpjStale 起选类异步反查，等回填完成
            panel.callerFieldForTest().pickForTest("DeviceInfoMapper", "demo.DeviceInfoMapper")
            awaitContains("DeviceInfoMapper")
            // Light 反查恒 false（无 MPJ 索引）——注入 MPJ 能力后 join 场景才放行 MPJ 形态
            panel.setCallerMpjForTest(true)
            // T-MapResultVo v2：返回类型字段自由输入 Map 声明 → 重转换走 Maps 族
            panel.voFieldForTest().text = "List<Map<String, Object>>"
            panel.convertForTest()
            val result = awaitContains("selectJoinMaps")
            assertTrue(result, result.contains("selectJoinMaps(wrapper)"))
            assertTrue(result, result.contains("List<Map<String, Object>>"))
            // 键入 HashMap 形态（动态泛型）→ 仍归 Maps 族且前缀按方法真实返回类型渲染
            panel.voFieldForTest().text = "List<HashMap<String, Object>>"
            panel.convertForTest()
            val hashMapResult = awaitContains("selectJoinMaps")
            assertTrue(hashMapResult, hashMapResult.contains("List<Map<String, Object>>"))
            assertTrue(hashMapResult, !hashMapResult.contains("HashMap"))
            // 清空返回类型 → 回到实体形态（零回归）
            panel.voFieldForTest().text = ""
            panel.convertForTest()
            val restored = awaitContains("new MPJQueryWrapper")
            assertTrue(restored, !restored.contains("selectJoinMaps"))
            assertTrue(restored, restored.contains("DeviceInfoMapper"))
        } finally {
            state.reverseWrapperFormat = original
        }
    }

    // ------------------------------------------------------------------
    // T-MapResultVo v2 修复：返回类型字段占位文案与选类按钮可见性（用户真机反馈回归钉）
    // ------------------------------------------------------------------

    fun testResultTypeFieldPlaceholderAndPickButtonVisible() {
        val field = panel.voFieldForTest()
        // 空值占位灰字（JTextField.placeholderText）——真机曾被报「文案不见了」
        val placeholder = field.placeholderTextForTest()
        assertNotNull("返回类型字段应有空值占位文案", placeholder)
        assertTrue(
            "占位文案应含返回类型说明",
            placeholder.toString().contains("返回类型") || placeholder.toString().contains("Result type"),
        )        // 选类按钮在场且可点（真机曾被报「类搜索弹框没了」）
        val button = field.pickButtonForTest()
        assertTrue("选类按钮应可见", button.isVisible || button.parent != null)
        assertEquals("选类按钮应可点（有 ActionListener）", 1, button.actionListeners.size)
        assertNotNull("选类按钮应有图标", button.icon)
        // 文本框可直接键入返回声明
        field.text = "List<Map<String, Object>>"
        assertEquals("List<Map<String, Object>>", field.text)
    }

    /**
     * T-MapResultVo v4 修复（用户真机四轮反馈「应默认显示灰色字体在里面，和调用类一样」）：
     * 返回类型字段与「调用类」**同款形态**——灰字占位由 [JBLabel] 承担（空值态显示 voHint 全文、
     * 前景为 inactive 灰）；不再是组合框（v2）也不依赖 `JTextField.placeholderText`
     * 客户属性（v3 该做法在本仓不渲染，真机实测空框无灰字）。
     */
    fun testResultTypeFieldSameAsCallerFieldPlaceholderStyle() {
        val field = panel.voFieldForTest()
        val display = field.editorComponentForTest()
        assertTrue("显示层应为标签（JBLabel，与调用类同款）: ${display.javaClass.name}", display is javax.swing.JLabel)
        assertFalse("不得为组合框（无下拉）", display is javax.swing.JComboBox<*>)
        // 空值态：显示层文案 = 占位全文，且为灰字（inactive 前景）
        field.text = ""
        val shown = field.placeholderTextForTest()?.toString() ?: ""
        assertTrue("空值态应显示返回类型占位全文: $shown", shown.contains("返回类型"))
        assertEquals(
            "空值态应为灰字（与调用类占位同色）",
            com.intellij.util.ui.UIUtil.getInactiveTextColor(),
            (display as javax.swing.JLabel).foreground,
        )
        // 有值态：显示层切到声明文本
        field.text = "List<Map<String, Object>>"
        assertEquals("List<Map<String, Object>>", field.placeholderTextForTest()?.toString())
    }

    /**
     * T-MapResultVo v5 修复（用户真机五轮反馈「为什么 List<Map<String, Object>> / List<Object>
     * 不能出现在选择类的这个弹框里，类似当时的 this」）：返回类型弹框直接复用「选择类」弹框，
     * 三个固定候选（自动（留空）/两条 Map 声明）**前插在候选列表最前**——与调用类
     * [CallerClassByNameModel] 的 this/baseMapper 同款机制，不再有中间那层四项小列表。
     */
    fun testReturnTypeModelSpecialItemsPrepended() {
        val model = panel.returnTypeModelForTest()
        val auto = cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
            .message("reverse.panel.resultType.auto")
        // 前插顺序：自动 → List<Map<String, Object>> → Page<Map<String, Object>> → List<Object> → 项目类候选
        val names = model.getNames(false).toList()
        assertEquals(
            "四个固定候选应前插在类候选之前（Map 列表/分页并列，Objs 无分页重载不提供 Page<Object>）",
            listOf(auto, "List<Map<String, Object>>", "Page<Map<String, Object>>", "List<Object>"),
            names.take(4),
        )
        assertTrue("固定候选之后应接项目类候选: ${names.size}", names.size >= 4)
        // 固定项直出（元素名/全限定名 = 字符串本身，不触 PSI）；按名取元素返回自身
        assertEquals(auto, model.getFullName(auto))
        assertEquals("List<Object>", model.getElementName("List<Object>"))
        val elements = model.getElementsByName("List<Map<String, Object>>", false, "")
        assertEquals(1, elements.size)
        assertEquals("List<Map<String, Object>>", elements[0])
        val pageElements = model.getElementsByName("Page<Map<String, Object>>", false, "")
        assertEquals(1, pageElements.size)
        assertEquals("Page<Map<String, Object>>", pageElements[0])
    }

    /** T-MapResultVo v5：弹框选中映射——固定项（自动=清空 / Map 声明=直写）＋类元素（简单名）。 */
    fun testReturnTypeChosenMapping() {
        val field = panel.voFieldForTest()
        val auto = cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
            .message("reverse.panel.resultType.auto")

        panel.applyReturnTypeChosenForTest("List<Map<String, Object>>")
        assertEquals("List<Map<String, Object>>", field.text)
        assertNotNull("声明形态选中后应带类图标（用户反馈缺失点）", field.valueIconForTest())
        // T-MapResultVo v6：Maps 分页声明（生成器按含 Map 归 Maps 族，SQL 含 LIMIT 时前缀=Page<Map>）
        panel.applyReturnTypeChosenForTest("Page<Map<String, Object>>")
        assertEquals("Page<Map<String, Object>>", field.text)
        assertNotNull("Page<Map<String, Object>> 选中后应有类图标", field.valueIconForTest())
        panel.applyReturnTypeChosenForTest("List<Object>")
        assertEquals("List<Object>", field.text)
        assertNotNull("List<Object> 选中后同样应有图标", field.valueIconForTest())
        panel.applyReturnTypeChosenForTest(auto)
        assertEquals("自动项应清空为留空（实体/VO 推断）", "", field.text)
        assertNull("自动（空值）态不应有图标", field.valueIconForTest())
        // 类元素：取简单类名写回（图标来自 PsiClass.getIcon）
        panel.applyReturnTypeChosenForTest(psiClassFixture())
        assertEquals("UserService", field.text)
        assertNotNull("类元素选中后应有类图标", field.valueIconForTest())
        // null 元素（未选中）不动字段
        panel.applyReturnTypeChosenForTest(null)
        assertEquals("UserService", field.text)
    }

    private lateinit var panel: SqlToJavaPanel

    override fun setUp() {
        super.setUp()
        panel = SqlToJavaPanel(project)
    }

    override fun tearDown() {
        panel.dispose()
        super.tearDown()
    }

    private fun awaitResult(): String {
        var result = panel.resultTextForTest()
        var waited = 0
        while (result.isBlank() && waited < 100) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
            result = panel.resultTextForTest()
            waited++
        }
        return result
    }

    /** 等待结果区包含目标文本（区分新旧结果——切换形态重生成的异步路径）。 */
    private fun awaitContains(substring: String): String {
        var result = panel.resultTextForTest()
        var waited = 0
        while (!result.contains(substring) && waited < 100) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
            result = panel.resultTextForTest()
            waited++
        }
        return result
    }
}