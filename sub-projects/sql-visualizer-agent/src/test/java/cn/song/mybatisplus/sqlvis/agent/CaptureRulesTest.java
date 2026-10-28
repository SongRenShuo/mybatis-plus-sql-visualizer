package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.hook.ParamCapture;
import cn.song.mybatisplus.sqlvis.agent.util.Desensitizer;
import cn.song.mybatisplus.sqlvis.agent.util.StackLocator;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 采集红线单测（§4.2 #4/#5 + §4.5）：
 * setXxx 显式枚举白名单（setFetchSize 等不误记）、列名提示解析、脱敏双条件、栈帧过滤规则。
 */
public class CaptureRulesTest {

    // ---------- ParamCapture：显式白名单 ----------

    @Test
    public void 白名单方法捕获_非白名单拒绝() {
        ParamCapture pc = new ParamCapture();
        assertTrue(pc.tryCapture("setString", new Object[]{1, "x"}));
        assertTrue(pc.tryCapture("setInt", new Object[]{2, 5}));
        assertTrue(pc.tryCapture("setNull", new Object[]{3, 92}));
        // DebugTools 误记坑：setFetchSize/setMaxRows/setQueryTimeout/setEscapeProcessing 不得捕获
        assertFalse(pc.tryCapture("setFetchSize", new Object[]{1, 100}));
        assertFalse(pc.tryCapture("setMaxRows", new Object[]{2, 10}));
        assertFalse(pc.tryCapture("setQueryTimeout", new Object[]{3, 5}));
        assertFalse(pc.tryCapture("setEscapeProcessing", new Object[]{true}));
        // setBoolean 白名单但首参不是 Integer（Statement.setEscapeProcessing(boolean) 形态冲突面）
        assertFalse(pc.tryCapture("setBoolean", new Object[]{Boolean.TRUE}));
    }

    @Test
    public void 快照按序排列_含类型与空值() {
        ParamCapture pc = new ParamCapture();
        pc.tryCapture("setString", new Object[]{2, "b"});
        pc.tryCapture("setInt", new Object[]{1, 7});
        pc.tryCapture("setNull", new Object[]{3, 92});
        List<String[]> snap = pc.snapshot(null, false);
        assertEquals(3, snap.size());
        assertEquals("1", snap.get(0)[0]);
        assertEquals("7", snap.get(0)[1]);
        assertEquals("Integer", snap.get(0)[2]);
        assertEquals("2", snap.get(1)[0]);
        assertEquals("b", snap.get(1)[1]);
        assertEquals("String", snap.get(1)[2]);
        assertEquals("3", snap.get(2)[0]);
        assertNull(snap.get(2)[1]);
        assertEquals("NULL", snap.get(2)[2]);
    }

    @Test
    public void 未覆写toString的驱动私有类型降级为形态标注() {
        // T-AgentDialectLoop：oracle.sql.Datum 族/达梦金仓私有类型 setObject 直传时
        // 默认 toString 产出 类名@哈希 垃圾值——须降级 <类名> 形态；常规值不受影响
        ParamCapture pc = new ParamCapture();
        pc.tryCapture("setObject", new Object[]{1, new Object()});
        pc.tryCapture("setObject", new Object[]{2, 7});
        pc.tryCapture("setString", new Object[]{3, "plain"});
        List<String[]> snap = pc.snapshot("INSERT INTO t VALUES (?, ?, ?)", false);
        assertEquals("<Object>", snap.get(0)[1]);
        assertEquals("7", snap.get(1)[1]);
        assertEquals("plain", snap.get(2)[1]);
    }

    // ---------- columnHint：第 n 个 `?` 前置标识符 ----------

    @Test
    public void 列名提示解析_跳过字面量内问号() {
        assertEquals("password", ParamCapture.columnHint("UPDATE u SET password = ? WHERE id = ?", 1));
        assertEquals("id", ParamCapture.columnHint("UPDATE u SET password = ? WHERE id = ?", 2));
        assertEquals("name", ParamCapture.columnHint("SELECT * FROM t WHERE name = 'a?b' AND name = ?", 1));
        // 字面量内的 ? 不占位：第 1 个真实占位符的提示是 name
        assertNull(ParamCapture.columnHint(null, 1));
    }

    // ---------- Desensitizer：双条件打码 ----------

    @Test
    public void 列名关键词打码() {
        assertEquals("***", Desensitizer.mask("password", "p@ss"));
        assertEquals("***", Desensitizer.mask("user_token", "abc"));
        assertEquals("***", Desensitizer.mask("mobile_no", "13800138000"));
        // 值形态兜底：11 位 1 开头数字即便列提示无关也打码（行为固化断言）
        assertEquals("***", Desensitizer.mask("age", "13800138000"));
    }

    @Test
    public void 值形态打码_列提示缺失兜底() {
        assertEquals("***", Desensitizer.mask(null, "110101199003077758"));
        assertEquals("***", Desensitizer.mask(null, "13800138000"));
        assertEquals("12345", Desensitizer.mask(null, "12345"));
    }

    // ---------- StackLocator：帧过滤与相对路径 ----------

    @Test
    public void 框架帧过滤规则() {
        assertTrue(StackLocator.isFrameworkFrame("java.sql.DriverManager"));
        assertTrue(StackLocator.isFrameworkFrame("com.mysql.cj.jdbc.ClientPreparedStatement"));
        assertTrue(StackLocator.isFrameworkFrame("com.zaxxer.hikari.pool.HikariProxyPreparedStatement"));
        assertTrue(StackLocator.isFrameworkFrame("org.springframework.jdbc.core.JdbcTemplate"));
        assertTrue(StackLocator.isFrameworkFrame("com.sun.proxy.$Proxy12"));
        assertFalse(StackLocator.isFrameworkFrame("com.example.service.UserService"));
        assertFalse(StackLocator.isFrameworkFrame("cn.example.app.dao.UserDao"));
    }

    @Test
    public void 框架帧过滤规则_数据层插件家族_T_AgentSourceJump() {
        // PageHelper 漏收即被误选为用户帧（用户实测「跳转报 PageInterceptor.java 未找到」的根因）
        assertTrue(StackLocator.isFrameworkFrame("com.github.pagehelper.PageInterceptor"));
        assertTrue(StackLocator.isFrameworkFrame("org.mybatis.spring.SqlSessionTemplate"));
        assertTrue(StackLocator.isFrameworkFrame("net.sf.jsqlparser.parser.CCJSqlParser"));
        assertTrue(StackLocator.isFrameworkFrame("org.apache.commons.lang3.StringUtils"));
        assertTrue(StackLocator.isFrameworkFrame("redis.clients.jedis.Jedis"));
        // 通用工具库刻意不收（用户业务代码可能直接调用，误收即跳错）
        assertFalse(StackLocator.isFrameworkFrame("cn.hutool.core.util.StrUtil"));
    }

    @Test
    public void 来源判定_JAR与目录与未知_T_AgentSourceJump() {
        // 本测试类由构建目录加载 → 非 JAR（用户业务代码形态）
        assertEquals(Boolean.FALSE, StackLocator.loadedFromJar(
                "cn.song.mybatisplus.sqlvis.agent.CaptureRulesTest",
                CaptureRulesTest.class.getClassLoader()));
        // JDK 类无 codeSource → 未知（不武断判为用户帧）
        assertNull(StackLocator.loadedFromJar("java.lang.String", CaptureRulesTest.class.getClassLoader()));
        // 不可加载类 → 未知
        assertNull(StackLocator.loadedFromJar("no.such.Class", CaptureRulesTest.class.getClassLoader()));
        assertNull(StackLocator.loadedFromJar(null, CaptureRulesTest.class.getClassLoader()));
    }

    @Test
    public void locate_候选帧不含主位置且数量有界_T_AgentSourceJump() {
        StackLocator.Frame f = StackLocator.locate();
        org.junit.Assert.assertNotNull(f);
        org.junit.Assert.assertNotNull(f.candidates);
        // T-SourceJumpFunnel：候选上限 3→8（汇聚门面场景深层业务帧须可见）
        org.junit.Assert.assertTrue("候选数应 ≤8", f.candidates.size() <= 8);
        for (String[] c : f.candidates) {
            org.junit.Assert.assertNotEquals("候选不得与主位置重复", f.sourceFile, c[0]);
        }
    }

    @Test
    public void locate_候选增厚四列且上界八条_T_SourceJumpFunnel() {
        StackLocator.Frame f = StackLocator.locate();
        org.junit.Assert.assertNotNull(f);
        org.junit.Assert.assertNotNull(f.candidates);
        org.junit.Assert.assertTrue("候选数应 ≤8", f.candidates.size() <= 8);
        for (String[] c : f.candidates) {
            org.junit.Assert.assertEquals("候选应为 [file, line, className, method] 四列", 4, c.length);
            org.junit.Assert.assertNotNull("类名列不得缺失", c[2]);
        }
        // 判定路径标记（L1=目录帧命中 / SAFETY=安全网），不在则打点改动回归
        assertTrue("via 应为 L1/SAFETY 之一，实为 " + f.via,
                "L1".equals(f.via) || "SAFETY".equals(f.via));
    }

    @Test
    public void 动态前缀登记_热生效与清空_T_SourceJumpFunnel() {
        // 自研框架前缀登记后，引擎帧即刻被识别为框架帧（P1-3 CONFIG 热下发语义）
        StackLocator.setExtraSkipPrefixes(new String[]{"com.corp.engine."});
        try {
            assertTrue(StackLocator.isFrameworkFrame("com.corp.engine.DataUpdateTool"));
            assertTrue(StackLocator.isFrameworkFrame("com.corp.engine.repo.Repository"));
            assertFalse(StackLocator.isFrameworkFrame("com.corp.app.service.UserService"));
            // 空组=清空
            StackLocator.setExtraSkipPrefixes(new String[0]);
            assertFalse(StackLocator.isFrameworkFrame("com.corp.engine.DataUpdateTool"));
            // 畸形项过滤（null/空白）
            StackLocator.setExtraSkipPrefixes(new String[]{"  ", null, "com.corp2."});
            assertFalse(StackLocator.isFrameworkFrame("com.corp.engine.DataUpdateTool"));
            assertTrue(StackLocator.isFrameworkFrame("com.corp2.x.Y"));
        } finally {
            StackLocator.setExtraSkipPrefixes(new String[0]);
        }
    }

    @Test
    public void 相对路径与聚焦键() {
        assertEquals("com/example/service/UserService.java",
                StackLocator.toRelativePath("com.example.service.UserService", "UserService.java"));
        assertEquals("App.java", StackLocator.toRelativePath("App", "App.java"));
        assertNull(StackLocator.toRelativePath("com.example.Foo", null));
    }

    @Test
    public void locate_从当前栈返回聚焦键形态() {
        StackLocator.Frame f = StackLocator.locate();
        // 测试栈内必有用户帧（本测试类）——methodKey 形如 SimpleClass#method
        org.junit.Assert.assertNotNull(f);
        org.junit.Assert.assertTrue(f.methodKey.contains("#"));
    }

    @Test
    public void 驱动登记表覆盖矩阵() {
        for (String cn : new String[]{
                "com.mysql.cj.jdbc.NonRegisteringDriver", "org.postgresql.Driver", "org.h2.Driver",
                "org.sqlite.JDBC", "com.microsoft.sqlserver.jdbc.SQLServerDriver",
                "com.kingbase8.Driver", "com.gbasedbt.jdbc.Driver",
                "org.opengauss.Driver"}) {
            assertTrue("应命中登记表: " + cn, DriverRegistry.isWeavable(cn));
        }
        assertFalse(DriverRegistry.isWeavable("com.example.NotADriver"));
        // T-ConnCastSafety：达梦 connect 面撤出——其 connect 出口织入是已知 VerifyError 形态
        // （premain 上会让 DmDriver 类定义失败、应用起不来），采集由语句面/prepare 面覆盖。
        assertFalse("达梦 connect 面应保持撤出（VerifyError 会让宿主起不来）",
                DriverRegistry.isWeavable("dm.jdbc.driver.DmDriver"));
    }
}
