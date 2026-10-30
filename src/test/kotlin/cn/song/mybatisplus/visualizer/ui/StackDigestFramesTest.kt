package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-StackHoverUi §M1：调用栈快照解析器（[StackDigestFrames.parse]）的自动化断言。
 *
 * 样本形态对齐 agent 侧 `StackDigest.build` 逐字契约（`<-` 连接、栈顶在左、行号负数不写、
 * 触顶追加 `…`）与用户真机截图实态（`$Proxy151` 无行号帧、hikari/ibatis 长链）：
 * 帧拆分/行号解析/截断末段丢弃/容错（垃圾段跳过、空入参空表）。
 */
class StackDigestFramesTest {

    /** 用户真机截图实态：agent 埋点两帧 + JDK 代理 + hikari + ibatis（含无行号帧）。 */
    private val realShape =
        "cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler#emitEvent:132" +
            "<-cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler#executeWithCapture:107" +
            "<-com.sun.proxy.\$Proxy151#execute" +
            "<-com.zaxxer.hikari.pool.HikariProxyPreparedStatement#execute:44" +
            "<-org.apache.ibatis.executor.SimpleExecutor#doQuery:63"


    @Test
    fun test真实形态_全链拆回帧列表_栈顶在左() {
        val frames = StackDigestFrames.parse(realShape)
        assertEquals(5, frames.size)
        // 栈顶在顶（串序即展示序）
        assertEquals("cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler", frames[0].fqcn)
        assertEquals("emitEvent", frames[0].method)
        assertEquals(132, frames[0].line)
        assertEquals("org.apache.ibatis.executor.SimpleExecutor", frames[4].fqcn)
        assertEquals("doQuery", frames[4].method)
        assertEquals(63, frames[4].line)
    }


    @Test
    fun test无行号帧_行号为0_方法名完整() {
        val frames = StackDigestFrames.parse(realShape)
        assertEquals(0, frames[2].line)
        assertEquals("com.sun.proxy.\$Proxy151", frames[2].fqcn)
        assertEquals("execute", frames[2].method)
    }


    @Test
    fun test内嵌类与lambda方法名_原样保留() {
        val frames = StackDigestFrames.parse(
            "com.corp.Outer\$Inner#run:42<-com.corp.Biz#lambda\$doIt\$0:7",
        )
        assertEquals(2, frames.size)
        assertEquals("com.corp.Outer\$Inner", frames[0].fqcn)
        assertEquals("run", frames[0].method)
        assertEquals(42, frames[0].line)
        assertEquals("lambda\$doIt\$0", frames[1].method)
        assertEquals(7, frames[1].line)
    }


    @Test
    fun testagent自身帧判定() {
        val frames = StackDigestFrames.parse(realShape)
        assertTrue("埋点链两帧应判 agent 帧", StackDigestFrames.isAgentFrame(frames[0]))
        assertTrue(StackDigestFrames.isAgentFrame(frames[1]))
        assertFalse("业务/框架帧不判 agent 帧", StackDigestFrames.isAgentFrame(frames[2]))
        assertFalse(StackDigestFrames.isAgentFrame(frames[3]))
    }


    @Test
    fun test帧数触顶截断_完整末帧随标记丢弃() {
        // agent 帧数触顶：完整帧后追加 …——末段（第 3 帧）随标记丢弃，前两帧保留
        val frames = StackDigestFrames.parse("a.A#m1:1<-b.B#m2:2<-c.C#m3:3…")
        assertEquals(2, frames.size)
        assertEquals("a.A", frames[0].fqcn)
        assertEquals("b.B", frames[1].fqcn)
    }


    @Test
    fun test长度触顶截断_半帧末段不造假展示() {
        // 2000 字符硬截把末帧切半：`c.C#doQu` 解析出残缺方法名也不展示（末段丢弃）
        val frames = StackDigestFrames.parse("a.A#m1:1<-b.B#m2:2<-c.C#doQu…")
        assertEquals(2, frames.size)
        assertNull(frames.getOrNull(2))
        assertEquals("m1", frames[0].method)
    }


    @Test
    fun test非数字行号段_按无行号处理() {
        val frames = StackDigestFrames.parse("a.A#native<-b.B#run:-1")
        assertEquals(0, frames[0].line)
        // 负号行号（agent 不产出，容错）：`-1` 非全数字 → 整段当方法名，行号 0
        assertEquals("run:-1", frames[1].method)
        assertEquals(0, frames[1].line)
    }


    @Test
    fun test溢出行号不能让历史快照解析抛异常() {
        val frame = StackDigestFrames.parse("com.acme.OrderController#list:999999999999999999").single()
        assertEquals(0, frame.line)
    }

    @Test
    fun test容错_null_空串_纯空白() {
        assertEquals(0, StackDigestFrames.parse(null).size)
        assertEquals(0, StackDigestFrames.parse("").size)
        assertEquals(0, StackDigestFrames.parse("   ").size)
        assertEquals(0, StackDigestFrames.parse("…").size)
    }


    @Test
    fun test容错_无井号垃圾段跳过_有效帧保留() {
        val frames = StackDigestFrames.parse("garbage-without-hash<-a.A#m1:1<-<-b.B#m2:2")
        assertEquals(2, frames.size)
        assertEquals("a.A", frames[0].fqcn)
        assertEquals("b.B", frames[1].fqcn)
    }


    @Test
    fun test容错_整串不可解析返回空表() {
        assertEquals(0, StackDigestFrames.parse("not-a-stack").size)
        assertEquals(0, StackDigestFrames.parse("#-only-method").size)
    }


    @Test
    fun test前后空白与段间空白容忍() {
        // 历史条目/日志链路可能的空格形态：段前后空白 trim 后解析
        val frames = StackDigestFrames.parse(" a.A#m1:1 <- b.B#m2:2 ")
        assertEquals(2, frames.size)
        assertEquals("a.A", frames[0].fqcn)
        assertEquals(1, frames[0].line)
    }

    @Test
    fun test两端采样省略标记段_保留不可展开且不计作真实帧() {
        val digest = "com.a.F1#m1:1<-com.a.F2#m2:2<-<…中间省略 37 帧…><-com.biz.Controller#list:42"
        val items = StackDigestFrames.parseItems(digest)
        assertEquals(4, items.size)
        assertEquals(StackDigestItem.Omitted(37), items[2])
        assertEquals("com.biz.Controller", (items[3] as StackDigestItem.Frame).entry.fqcn)
        assertEquals(3, items.filterIsInstance<StackDigestItem.Frame>().size)
        assertEquals(digest, StackFramesProjection.build(digest).original)
        // 旧版纯帧解析入口仍兼容，不将缺失 37 帧伪装成采到的帧。
        assertEquals(3, StackDigestFrames.parse(digest).size)
    }

    @Test
    fun test投影反向判别_业务夹在两段内部调用之间默认可见且展开保序() {
        val digest = "cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler#emit:1" +
            "<-java.lang.reflect.Method#invoke:2" +
            "<-com.acme.service.OrderService#query:3" +
            "<-org.apache.ibatis.executor.SimpleExecutor#doQuery:4" +
            "<-com.acme.web.OrderEndpoint#list:5" +
            "<-com.zaxxer.hikari.pool.HikariProxyPreparedStatement#execute:6"
        val projection = StackFramesProjection.build(digest)
        assertEquals(digest, projection.original)
        assertEquals(5, projection.nodes.size)
        assertEquals(2, (projection.nodes[0] as StackFramesNode.Internal).frames.size)
        assertEquals("com.acme.service.OrderService", (projection.nodes[1] as StackFramesNode.Visible).frame.fqcn)
        assertEquals("com.acme.web.OrderEndpoint", (projection.nodes[3] as StackFramesNode.Visible).frame.fqcn)
        assertEquals(6, projection.nodes.flatMap { it.frames() }.size)
        assertEquals(StackDigestFrames.parse(digest), projection.nodes.flatMap { it.frames() })
        assertFalse(projection.noProjectFrames)
    }

    @Test
    fun test分类保守_框架命名空间中的项目类和未知包不被误折() {
        val digest = "org.springframework.web.myapp.CustomController#run:1" +
            "<-com.company.generated.\$Proxy5#invoke" +
            "<-org.example.inventory.Inventory#save:9" +
            "<-org.springframework.web.servlet.myapp.OrderController#list:10" +
            "<-sun.reflect.NativeMethodAccessorImpl#invoke0"
        val projection = StackFramesProjection.build(digest)
        assertEquals(5, projection.nodes.size)
        assertTrue(projection.nodes.take(4).all { it is StackFramesNode.Visible })
        assertEquals(0, (projection.nodes[1] as StackFramesNode.Visible).frame.line)
        assertTrue(projection.nodes[4] is StackFramesNode.Internal)
    }

    @Test
    fun test截图中的Web过滤链默认折叠但不误折项目同前缀类() {
        val digest = "org.apache.catalina.core.ApplicationFilterChain#doFilter:138" +
            "<-cn.dev33.satoken.filter.SaServletFilter#doFilter:52" +
            "<-org.springframework.web.servlet.DispatcherServlet#doDispatch:12" +
            "<-org.springframework.web.myapp.CustomController#run:15"
        val nodes = StackFramesProjection.build(digest).nodes
        assertEquals(2, nodes.size)
        assertEquals(3, (nodes[0] as StackFramesNode.Internal).frames.size)
        assertEquals("org.springframework.web.myapp.CustomController", (nodes[1] as StackFramesNode.Visible).frame.fqcn)
    }

    @Test
    fun test常见Spring过滤器纯内部链不能伪装有业务帧() {
        val digest = "org.springframework.web.filter.FormContentFilter#doFilter:10" +
            "<-org.springframework.web.filter.CharacterEncodingFilter#doFilter:11" +
            "<-org.springframework.web.servlet.DispatcherServlet#doDispatch:12"
        val projection = StackFramesProjection.build(digest)
        assertTrue(projection.noProjectFrames)
        assertEquals(1, projection.nodes.size)
        assertEquals(3, (projection.nodes.single() as StackFramesNode.Internal).frames.size)
    }

    @Test
    fun test中间省略切断内部组_纯内部无业务提示_空快照保真() {
        val digest = "java.lang.Thread#run:1<-<…中间省略 9 帧…><-jdk.internal.Foo#bar:2"
        val projection = StackFramesProjection.build(digest)
        assertEquals(3, projection.nodes.size)
        assertEquals(StackFramesNode.Omission(9), projection.nodes[1])
        assertEquals(2, projection.nodes.flatMap { it.frames() }.size)
        assertTrue(projection.noProjectFrames)
        assertTrue(StackFramesProjection.build(null).nodes.isEmpty())
    }

    // ------------------------------------------------------------------
    // T-StackBizFrame S3：驱动族/MyBatis 日志族折叠 + 内联摘要取景
    // ------------------------------------------------------------------

    @Test
    fun test驱动族与MyBatis日志族_按内部帧折叠_不再冒充可见帧() {
        val digest = "org.postgresql.jdbc.PgPreparedStatement#execute:182" +
            "<-org.apache.ibatis.logging.jdbc.PreparedStatementLogger#invoke:58" +
            "<-org.apache.ibatis.binding.MapperMethod#execute:79" +
            "<-com.acme.service.OrderService#find:42"
        val projection = StackFramesProjection.build(digest)
        assertEquals(2, projection.nodes.size)
        assertEquals(3, (projection.nodes[0] as StackFramesNode.Internal).frames.size)
        assertEquals("com.acme.service.OrderService", (projection.nodes[1] as StackFramesNode.Visible).frame.fqcn)
        assertFalse(projection.noProjectFrames)
    }

    @Test
    fun test取景_头部全为内部帧时跳到首个项目帧() {
        val digest = "cn.song.mybatisplus.sqlvis.agent.hook.WovenEvents#emit:70" +
            "<-org.postgresql.jdbc.PgPreparedStatement#execute:182" +
            "<-<…中间省略 40 帧…>" +
            "<-com.acme.service.OrderService#find:42" +
            "<-com.acme.task.OrderTask#run:45"
        assertEquals(
            "com.acme.service.OrderService#find:42<-com.acme.task.OrderTask#run:45",
            StackDigestFrames.projectExcerpt(digest),
        )
    }

    @Test
    fun test取景_首个非内部帧就在开头时原样返回() {
        val digest = "com.acme.service.OrderService#find:42<-java.lang.Thread#run:1"
        assertEquals(digest, StackDigestFrames.projectExcerpt(digest))
    }

    @Test
    fun test取景_全内部帧或空串时原样返回() {
        val allInternal = "java.lang.Thread#run:1<-org.postgresql.jdbc.PgPreparedStatement#execute:2"
        assertEquals(allInternal, StackDigestFrames.projectExcerpt(allInternal))
        assertEquals("", StackDigestFrames.projectExcerpt(""))
    }
}
