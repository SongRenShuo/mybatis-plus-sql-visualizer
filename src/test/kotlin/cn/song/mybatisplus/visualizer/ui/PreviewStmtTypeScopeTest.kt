package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.ConsumptionKind
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperConsumption
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-PreviewStmtTypeScope：预览语句类型的**判型依据**与**判型作用域**。
 *
 * 背景（用户 2026-11-12 真机报障，见 `docs/开发计划/20261112-1740-T-PreviewStmtTypeScope-*`）：
 * Component 形态工程里查询链的预览出 `UPDATE`/`DELETE`，而 `selectOne(wrapper)` 处出 `SELECT`。
 * 根因是判型只看「消费方法名」且扫描范围是**该变量全部引用**——同一 wrapper 在方法另一条语句上
 * 被写调用消费时，判型与用户正在看的语句脱钩，且面板不说明依据。
 *
 * 本类钉三件事：
 *  - F1 作用域（D-2）：同一 wrapper 多处消费时按**锚点位置**选依据（[WrapperLocator.decideConsumption]）；
 *  - F2 能力边界：Component 形参锚点取不到调用方条件，产出无 WHERE 的 SELECT（如实，不凭空造条件）；
 *  - F3 接收者负门（M3）：JDK 容器上的同名调用（`list.remove(w)`）不改判语句类型，且不误伤
 *    「自有 Service 桩」上的真消费（`service.remove(w)` 仍 DELETE）；
 *  - M1 依据可见化：证据进 `PreviewResult` 且有可读文案。
 */
class PreviewStmtTypeScopeTest : BasePlatformTestCase() {

    private fun newService() = SqlPreviewService(project)

    /** 按变量名取锚点（与 gutter 图标锚点同源：wrapper 变量的名字标识符）。 */
    private fun variableAnchor(name: String): PsiElement = PsiTreeUtil
        .collectElementsOfType(myFixture.file, PsiLocalVariable::class.java)
        .first { it.name == name }
        .nameIdentifier!!

    /** 按消费方法名取锚点：该调用里 wrapper 实参那处引用（与「光标停在消费语句里」同源）。 */
    private fun argumentAnchorOf(methodName: String): PsiElement = PsiTreeUtil
        .collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
        .first { it.methodExpression.referenceName == methodName }
        .argumentList.expressions.last()

    /** 第 [index] 个 wrapper `new` 表达式的 `new` 关键字（gutter 图标锚点与该 leaf 同源）。 */
    private fun newKeywordOf(index: Int): PsiElement {
        val newExpr = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiNewExpression::class.java)
            .filter { (it.type as? PsiClassType)?.className == "LambdaQueryWrapper" }[index]
        return newExpr.firstChild
    }

    private fun evidence(
        kind: ConsumptionKind,
        methodName: String,
        referenceOffset: Int,
        statementRange: IntRange,
        line: Int = 1,
    ) = WrapperConsumption(kind, methodName, referenceOffset, statementRange, line, "Demo.java")

    // ------------------------------------------------------------------
    // F1 判型作用域（D-2）
    // ------------------------------------------------------------------

    fun testConsumptionAfterAnchorDecidesAndOthersBecomeCandidates() {
        // 同一 wrapper：声明+条件在第 3 行，消费在 4/5/6 行（getOne 未知名 + update + delete）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;
                public void m() {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>().eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoService.getOne(w);
                    deviceInfoMapper.update(entity, w);
                    deviceInfoMapper.delete(w);
                }

                interface DeviceInfoMapper {
                    int update(Object entity, Object wrapper);
                    int delete(Object wrapper);
                }

                static class DeviceInfoService {
                    public DeviceInfoEntity getOne(Object w) { return null; }
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val located = WrapperLocator.locateWithTable(variableAnchor("w"))
        assertNotNull("wrapper 变量应被定位", located)
        // D-2 第 2 条：锚点之后的**最近**一条消费决定判型（update 早于 delete）→ UPDATE，不是 delete
        assertTrue(
            "锚点之后最近的一条消费应是实体更新: ${located!!.chain.decidingConsumption}",
            located.chain.updateEntityConsumed,
        )
        assertFalse("更靠后的 delete 不应参与判型", located.chain.deleteConsumed)
        assertEquals("update", located.chain.decidingConsumption?.methodName)
        assertEquals(
            "落选的消费进候选集（面板悬停展示）",
            listOf("delete"),
            located.chain.allConsumptions.filterNot { it == located.chain.decidingConsumption }.map { it.methodName },
        )

        val result = newService().preview(variableAnchor("w"))
        assertNotNull(result)
        assertTrue("update 消费应产出 UPDATE: ${result!!.sql}", result.sql.startsWith("UPDATE"))
    }

    fun testReusedWrapperVariablePicksConsumptionOfAnchoredStatement() {
        // 用户 2026-11-12 真机代码形态（H1）：同一个 wrapper 变量在方法里被复用——
        // 「条件语句 + update(entity, w)」在上，「重新赋值 + 条件 + delete(w)」在下。
        // 旧口径两处都出 UPDATE（update 档位恒先命中），点击位置不同会看到不同类型的语义由此而来。
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;
                public void m(Param inParam) {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>();
                    w.eq(DeviceInfoEntity::getPartyId, inParam.getPartyId());
                    deviceInfoMapper.update(entity, w);
                    w = new LambdaQueryWrapper<DeviceInfoEntity>();
                    w.eq(DeviceInfoEntity::getOrgMemberId, inParam.getOrgMemberId())
                        .eq(DeviceInfoEntity::getPartyId, inParam.getOrgMemberId());
                    deviceInfoMapper.delete(w);
                }

                interface DeviceInfoMapper {
                    int update(Object entity, Object wrapper);
                    int delete(Object wrapper);
                }

                static class Param { private Long orgMemberId; private Long partyId; }
                static class DeviceInfoEntity { private Long partyId; }
            }
            """.trimIndent(),
        )
        // ① 光标在 update 那条语句里 → UPDATE
        val updateLocated = WrapperLocator.locateWithTable(argumentAnchorOf("update"))
        assertNotNull(updateLocated)
        assertTrue(
            "锚点在 update 语句里应判 UPDATE: ${updateLocated!!.chain.decidingConsumption}",
            updateLocated.chain.updateEntityConsumed,
        )
        // ② 光标在 delete 那条语句里 → DELETE（不再是全局「update 优先」）
        val deleteLocated = WrapperLocator.locateWithTable(argumentAnchorOf("delete"))
        assertNotNull(deleteLocated)
        assertTrue(
            "锚点在 delete 语句里应判 DELETE: ${deleteLocated!!.chain.decidingConsumption}",
            deleteLocated.chain.deleteConsumed,
        )
        assertFalse("同一次判型不应同时置 update 档", deleteLocated.chain.updateEntityConsumed)
        // ③ 两条依据都进候选集（面板悬停可核对「另一个写法在第几行」）
        assertEquals(
            "该变量上的消费调用应全部留痕",
            listOf("update", "delete"),
            deleteLocated.chain.allConsumptions.map { it.methodName },
        )
        val result = newService().preview(argumentAnchorOf("delete"))
        assertNotNull(result)
        assertTrue("锚点语句是 delete → 产出 DELETE: ${result!!.sql}", result.sql.startsWith("DELETE"))
        assertEquals("delete", result.decidingConsumption?.methodName)
    }

    fun testReassignmentAnchorUsesVariableChainAndAnchorSegment() {
        // 用户 2026-11-13 真机复现（demo 语料 WrapperReuseRepro 第 100 行）：重新赋值语句的 `new` 锚点。
        // 修复前该锚点走 buildChainFromNew，只收赋值表达式内部的链 → 丢掉后续 .eq 语句与 delete(w) 消费，
        // 预览退化成无 WHERE 的 SELECT；修复后按目标变量走变量路径，且只收锚点所在段的条件。
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private OrderComponent orderComponent;
                public int m(Order patch, Integer userId, String orderNo) {
                    LambdaQueryWrapper<Order> w = new LambdaQueryWrapper<Order>();
                    w.eq(Order::getUserId, userId)
                        .eq(Order::getStatus, 0);
                    orderComponent.update(patch, w);
                    w = new LambdaQueryWrapper<Order>();
                    w.eq(Order::getOrderNo, orderNo)
                        .eq(Order::getUserId, userId);
                    return orderComponent.delete(w);
                }

                static class OrderComponent {
                    public boolean update(Object entity, Object wrapper) { return true; }
                    public int delete(Object wrapper) { return 0; }
                }

                static class Order { private Integer userId; private Integer status; private String orderNo; }
            }
            """.trimIndent(),
        )
        // ① 重新赋值行的 `new` 锚点：按目标变量走变量路径，判到锚点之后最近的消费 → DELETE
        val located = WrapperLocator.locateWithTable(newKeywordOf(1))
        assertNotNull("重新赋值锚点应定位到变量链（修复前落到内联 new 路径）", located)
        assertTrue(
            "锚点之后最近的消费是 delete，应判 DELETE: ${located!!.chain.decidingConsumption}",
            located.chain.deleteConsumed,
        )
        // ② 只收锚点所在段的条件：delete 段是 order_no + user_id，不该带 update 段的 status
        val result = newService().preview(newKeywordOf(1))
        assertNotNull(result)
        assertTrue("应产出 DELETE: ${result!!.sql}", result.sql.startsWith("DELETE"))
        assertTrue("delete 段条件应保留 order_no: ${result.sql}", result.sql.contains("order_no"))
        assertTrue("delete 段条件应保留 user_id: ${result.sql}", result.sql.contains("user_id"))
        assertFalse("不该把 update 段的 status 条件带进来: ${result.sql}", result.sql.contains("status"))
        // ③ 声明段的锚点仍按声明段的链渲染（存量行为）：WHERE 只该有 user_id / status
        val declaration = newService().preview(newKeywordOf(0))
        assertNotNull(declaration)
        val declarationWhere = declaration!!.sql.substringAfter("WHERE", "")
        assertTrue("声明段 WHERE 应保留 status 条件: ${declaration.sql}", declarationWhere.contains("status"))
        assertFalse(
            "声明段 WHERE 不该带 delete 段的 order_no（SET 列里出现属正常）: ${declaration.sql}",
            declarationWhere.contains("order_no"),
        )
    }

    fun testSegmentFilterKeepsEverythingWithoutReassignment() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void m() {
                    LambdaQueryWrapper<Order> w = new LambdaQueryWrapper<Order>();
                    w.eq(Order::getUserId, 1);
                }
                static class Order { private Integer userId; }
            }
            """.trimIndent(),
        )
        val calls = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java).toList()
        // 无重新赋值点 / 无锚点时全量返回（存量行为零变化）
        assertEquals(
            "无重新赋值点 → 全量返回",
            calls,
            WrapperLocator.filterCallsToAnchorSegment(calls, emptyList(), 0),
        )
        assertEquals(
            "无锚点 → 全量返回",
            calls,
            WrapperLocator.filterCallsToAnchorSegment(calls, listOf(5), null),
        )
        assertEquals("空输入恒空", emptyList<PsiMethodCallExpression>(), WrapperLocator.filterCallsToAnchorSegment(emptyList(), emptyList(), 10))
    }

    fun testDecidingConsumptionFollowsAnchorStatement() {
        val delete = evidence(ConsumptionKind.DELETE, "remove", 100, 90..120)
        val update = evidence(ConsumptionKind.UPDATE_ENTITY, "update", 200, 190..220)
        // ① 锚点落在 delete 语句内 → 该语句的消费优先（哪怕 update 优先级更高）
        assertEquals(
            ConsumptionKind.DELETE,
            WrapperLocator.decideConsumption(listOf(delete, update), 110)?.kind,
        )
        // ② 锚点在两条之间 → 锚点之后最近的一条
        assertEquals(
            ConsumptionKind.UPDATE_ENTITY,
            WrapperLocator.decideConsumption(listOf(delete, update), 150)?.kind,
        )
        // ③ 锚点在全部消费之后 → 无消费（语句类型维持默认 SELECT）
        assertNull(
            "锚点之后没有消费时不再拿前面的消费改判（旧口径会给出 DELETE）",
            WrapperLocator.decideConsumption(listOf(delete, update), 300),
        )
        // ④ 无锚点上下文（程序化调用）→ 退回档位优先级：count > exists > update > delete
        val count = evidence(ConsumptionKind.COUNT, "count", 400, 390..420)
        assertEquals(
            ConsumptionKind.COUNT,
            WrapperLocator.decideConsumption(listOf(delete, update, count), null)?.kind,
        )
        assertNull("空候选集恒为无消费", WrapperLocator.decideConsumption(emptyList(), 10))
    }

    fun testSameStatementConsumptionsUseKindPriority() {
        val statement = 100..200
        val delete = evidence(ConsumptionKind.DELETE, "remove", 120, statement)
        val update = evidence(ConsumptionKind.UPDATE_ENTITY, "update", 150, statement)
        assertEquals(
            "同一条语句内多条消费按档位优先级取（与 SqlPreviewService 分派顺序同源）",
            ConsumptionKind.UPDATE_ENTITY,
            WrapperLocator.decideConsumption(listOf(delete, update), 110)?.kind,
        )
    }

    // ------------------------------------------------------------------
    // F2 参数锚点（Component 形态）：读不到调用方条件，如实产出无 WHERE 的 SELECT
    // ------------------------------------------------------------------

    fun testParameterAnchorYieldsSelectWithoutConditions() {
        myFixture.configureByText(
            "Component.java",
            """
            public class Component {
                private DeviceInfoMapper deviceInfoMapper;

                public DeviceInfoEntity qryOneByWrapper(LambdaQueryWrapper<DeviceInfoEntity> wrapper) {
                    return deviceInfoMapper.selectOne(wrapper);
                }

                interface DeviceInfoMapper {
                    DeviceInfoEntity selectOne(Object wrapper);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val parameter = PsiTreeUtil.findChildOfType(myFixture.file, PsiParameter::class.java)
        assertNotNull("fixture 应有 wrapper 形参", parameter)
        val result = newService().preview(parameter!!.nameIdentifier!!)
        assertNotNull("形参锚点应能出预览（跨方法取不到条件）", result)
        assertTrue("形参锚点维持 SELECT: ${result!!.sql}", result.sql.startsWith("SELECT"))
        assertFalse("参数锚点没有条件可渲染，不应凭空造 WHERE", result.sql.contains("WHERE"))
        assertNull("selectOne 不是消费方法名，无判型依据", result.decidingConsumption)
    }

    // ------------------------------------------------------------------
    // F3 接收者负门（M3）
    // ------------------------------------------------------------------

    fun testJdkCollectionRemoveIsNotSqlConsumption() {
        myFixture.configureByText(
            "Demo.java",
            """
            import java.util.ArrayList;
            import java.util.List;

            public class Demo {
                public void m() {
                    List<Object> list = new ArrayList<Object>();
                    LambdaQueryWrapper<DeviceInfoEntity> w1 = new LambdaQueryWrapper<DeviceInfoEntity>().eq(DeviceInfoEntity::getId, 1L);
                    list.remove(w1);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val located = WrapperLocator.locateWithTable(variableAnchor("w1"))
        assertNotNull("wrapper 变量应被定位", located)
        assertFalse(
            "List.remove(Object) 不是 SQL 删除消费，不应改判 DELETE",
            located!!.chain.deleteConsumed,
        )
        assertNull("负门拦下的调用不进判型依据", located.chain.decidingConsumption)
        val result = newService().preview(variableAnchor("w1"))
        assertNotNull(result)
        assertTrue("语句类型维持 SELECT: ${result!!.sql}", result.sql.startsWith("SELECT"))
    }

    fun testOwnServiceStubConsumptionIsNotBlockedByReceiverGate() {
        // 负门的反向守卫：接收者是自有 Service 桩（未接 MP 依赖的工程常态）时，真消费照旧改判
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;
                public void m() {
                    LambdaQueryWrapper<DeviceInfoEntity> w2 = new LambdaQueryWrapper<DeviceInfoEntity>().eq(DeviceInfoEntity::getId, 2L);
                    deviceInfoService.remove(w2);
                }

                static class DeviceInfoService {
                    public int remove(Object wrapper) { return 0; }
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val located = WrapperLocator.locateWithTable(variableAnchor("w2"))
        assertNotNull("wrapper 变量应被定位", located)
        assertTrue(
            "自有 Service 桩上的 remove(wrapper) 仍是删除消费（负门不得误伤）",
            located!!.chain.deleteConsumed,
        )
        assertEquals("remove", located.chain.decidingConsumption?.methodName)
    }

    // ------------------------------------------------------------------
    // M1 判型依据（数据面 + 呈现面）
    // ------------------------------------------------------------------

    fun testEvidenceCarriesMethodNameAndLine() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;
                public void m() {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>().eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoMapper.update(entity, w);
                }

                interface DeviceInfoMapper {
                    int update(Object entity, Object wrapper);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(variableAnchor("w"))
        assertNotNull(result)
        val evidence = result!!.decidingConsumption
        assertNotNull("判型依据应随结果进面板", evidence)
        assertEquals("update", evidence!!.methodName)
        assertEquals("证据行号取消费调用所在行", 6, evidence.line)
        assertEquals("Demo.java", evidence.fileName)
    }

    // ------------------------------------------------------------------
    // D-3 多档消费时给「预览目标」切换（不替用户拍板）
    // ------------------------------------------------------------------

    fun testMultiConsumptionOffersSwitchableTargets() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;
                public void m() {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>();
                    w.eq(DeviceInfoEntity::getPartyId, 1L);
                    deviceInfoMapper.update(entity, w);
                    w = new LambdaQueryWrapper<DeviceInfoEntity>();
                    w.eq(DeviceInfoEntity::getOrgMemberId, 2L);
                    deviceInfoMapper.delete(w);
                }

                interface DeviceInfoMapper {
                    int update(Object entity, Object wrapper);
                    int delete(Object wrapper);
                }

                static class DeviceInfoEntity { private Long partyId; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(variableAnchor("w"))
        assertNotNull(result)
        assertEquals(
            "多档消费应全部列为可切换目标（按分派优先级排序）",
            listOf(ConsumptionKind.UPDATE_ENTITY, ConsumptionKind.DELETE),
            result!!.statementTypeOptions,
        )
        val switcher = result.statementTypeSwitch
        assertNotNull("多档时应挂重算闭包", switcher)
        // 默认（D-2）：锚点之后的最近一条 → UPDATE
        assertTrue(result.sql.startsWith("UPDATE"))
        // 切到 DELETE
        val asDelete = switcher!!.invoke(ConsumptionKind.DELETE)
        assertNotNull("切换档位应能重算出结果", asDelete)
        assertTrue("切到删除档应产出 DELETE: ${asDelete!!.sql}", asDelete.sql.startsWith("DELETE"))
        assertEquals("delete", asDelete.decidingConsumption?.methodName)
        assertEquals("切换后仍带全部可切换目标", result.statementTypeOptions, asDelete.statementTypeOptions)
        // 切回 UPDATE
        val asUpdate = switcher.invoke(ConsumptionKind.UPDATE_ENTITY)
        assertTrue("切回更新档应产出 UPDATE: ${asUpdate!!.sql}", asUpdate.sql.startsWith("UPDATE"))
    }

    fun testSingleConsumptionHasNoSwitchEntry() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;
                public void m() {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>().eq(DeviceInfoEntity::getId, 1L);
                    deviceInfoMapper.update(entity, w);
                }

                interface DeviceInfoMapper {
                    int update(Object entity, Object wrapper);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(variableAnchor("w"))
        assertNotNull(result)
        assertEquals("单档不产生切换列表", 1, result!!.statementTypeOptions.size)
        assertNull("单档不挂重算闭包（不留 PSI 引用）", result.statementTypeSwitch)
    }

    fun testDecidingConsumptionHonorsPreferredKind() {
        val update = evidence(ConsumptionKind.UPDATE_ENTITY, "update", 200, 190..220)
        val delete = evidence(ConsumptionKind.DELETE, "remove", 300, 290..320)
        // D-3 强制档位优先于 D-2 的位置判定（含「锚点之后没有该档」的情形：取最早一条）
        assertEquals(
            ConsumptionKind.DELETE,
            WrapperLocator.decideConsumption(listOf(update, delete), 100, ConsumptionKind.DELETE)?.kind,
        )
        assertEquals(
            ConsumptionKind.UPDATE_ENTITY,
            WrapperLocator.decideConsumption(listOf(update, delete), 400, ConsumptionKind.UPDATE_ENTITY)?.kind,
        )
        // 强制档位没有证据时回落 D-2（防御）
        assertEquals(
            ConsumptionKind.UPDATE_ENTITY,
            WrapperLocator.decideConsumption(listOf(update, delete), 150, ConsumptionKind.COUNT)?.kind,
        )
    }

    fun testEvidenceTextAndCandidateSection() {
        val evidence = evidence(ConsumptionKind.UPDATE_ENTITY, "update", 0, IntRange.EMPTY, line = 120)
        val text = stmtTypeSourceText(evidence)
        assertTrue("可见行文案含方法名: $text", text.contains("update()"))
        assertTrue("可见行文案含行号: $text", text.contains("120"))
        val noLine = stmtTypeSourceText(evidence.copy(line = 0))
        assertFalse("行号不可得时不显示『第 0 行』: $noLine", noLine.contains("0"))

        val result = PreviewResult(
            sql = "SELECT 1", compactSql = "SELECT 1", unresolvedParams = emptyList(),
            paramTable = null, wrapperType = null, tableName = null,
            otherConsumptions = listOf(evidence, evidence.copy(kind = ConsumptionKind.DELETE, methodName = "remove", line = 130)),
        )
        val section = stmtTypeCandidatesSection(result)
        assertTrue("悬停段含位置: $section", section.contains("Demo.java:120"))
        assertTrue("悬停段含方法名与语句形态: $section", section.contains("remove()") && section.contains("DELETE"))
        assertEquals("无落选者时不加悬停段", "", stmtTypeCandidatesSection(result.copy(otherConsumptions = emptyList())))
    }
}
