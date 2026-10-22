package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * 实时日志「跳转到源码」解析回归（F-44-6 精确跳转 + T-JumpCapture 语义定案）：
 *
 * 用户定性「按表名搜索 Wrapper 定位」不可用（同表名 ≠ 同调用点，命中即误导），
 * SqlLogPanel.resolveEntryJump 现仅依赖 SqlSourceInterceptor 捕获的源码位置：
 *  - 携带位置 → Precise（file:line 精确跳转，行号 = sourceLine-1）；
 *  - 位置指向的文件缺失 → NotFound（仅提示，与拦截器无关）；
 *  - MP 分页 COUNT 标记 → NoSource（无用户调用点，纯提示，不得引导开拦截器）;
 *  - 无位置（未开拦截器 / 旧版条目）→ MissingSource（SourceCaptureGuide 引导弹框）。
 *
 * headless 下不触发 jumpToSource 弹窗副作用，仅断言确定性解析分支。
 */
public class SqlLogPanelLiveJumpWithSourceTest extends BasePlatformTestCase {

    private SqlLogManager manager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        manager = new SqlLogManager(getProject());
        manager.getEntries().setDedupeEnabled(false);
    }

    @Override
    protected void tearDown() throws Exception {
        manager.dispose();
        super.tearDown();
    }

    private static LogSqlEntry entry(String restoredSql) {
        return entry(restoredSql, null, 0);
    }

    private static LogSqlEntry entry(String restoredSql, String sourceFile, int sourceLine) {
        return new LogSqlEntry(restoredSql, restoredSql, List.<LogSqlParam>of(), 1L, sourceFile, sourceLine, null);
    }

    private static LogSqlEntry entryWithCandidates(String restoredSql, String sourceFile, int sourceLine,
                                                   List<kotlin.Pair<String, Integer>> candidates) {
        return new LogSqlEntry(restoredSql, restoredSql, List.<LogSqlParam>of(), 1L, null,
                LogSqlEntry.summarizeSql(restoredSql), SqlSource.UNKNOWN, null, sourceFile, sourceLine,
                candidates, List.of(), null, 0L, null, null, null, null, null);
    }

    private File writeRealSource(String relativePath, String content) throws Exception {
        File f = new File(getProject().getBasePath(), relativePath);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
        VfsUtil.markDirtyAndRefresh(false, true, true, f);
        return f;
    }

    @Test
    public void test携带源码位置_精确跳转_Precise() throws Exception {
        writeRealSource("com/example/service/UserService.java", "class UserService {}\n");
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            LogJumpTarget target = panel.resolveEntryJump(entry("SELECT * FROM user WHERE id = 1", "com/example/service/UserService.java", 10));
            assertTrue("有源码位置应精确跳转", target instanceof LjtPrecise);
            LjtPrecise precise = (LjtPrecise) target;
            assertEquals("行号 = sourceLine-1", 9, precise.getLine());
            com.intellij.openapi.vfs.VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(
                new File(getProject().getBasePath(), "com/example/service/UserService.java").getAbsolutePath());
            assertEquals(vf, precise.getVirtualFile());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test携带源码位置_真实跳转不抛异常_链路可达() throws Exception {
        writeRealSource("com/example/service/NavService.java", "class NavService {}\n");
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            LogSqlEntry e = entry("SELECT * FROM nav WHERE id = 1", "com/example/service/NavService.java", 3);
            assertTrue("解析应为 Precise", panel.resolveEntryJump(e) instanceof LjtPrecise);
            panel.jumpToSourceForTest(e); // 等价右键跳转，真实文件下不抛异常即证明 navigate 链路可达
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test源码文件不存在_NotFound_提示() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            LogJumpTarget target = panel.resolveEntryJump(entry("SELECT 1", "missing/Missing.java", 5));
            assertTrue(target instanceof LjtNotFound);
            assertEquals("missing/Missing.java", ((LjtNotFound) target).getSourceFile());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test主位置不在工程内_回退候选_首个可解析项() throws Exception {
        // T-AgentSourceJump：主位置误选第三方插件帧（PageHelper 的 PageInterceptor 等）
        // → 按栈序候选取首个工程内可解析项
        writeRealSource("com/example/service/UserServiceImpl.java", "class UserServiceImpl {}\n");
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            LogSqlEntry e = entryWithCandidates(
                "SELECT COUNT(*) FROM user",
                "com/github/pagehelper/PageInterceptor.java", 132,
                List.of(
                    new kotlin.Pair<>("com/example/service/PageHelper.java", 7),          // 工程内不存在 → 跳过
                    new kotlin.Pair<>("com/example/service/UserServiceImpl.java", 36)));  // 命中
            LogJumpTarget target = panel.resolveEntryJump(e);
            assertTrue("主位置缺失应回退到候选的 Precise，而非 NotFound", target instanceof LjtPrecise);
            assertEquals("行号 = 候选 line-1", 35, ((LjtPrecise) target).getLine());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test主位置与候选全不可解析_NotFound不劣化() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            LogSqlEntry e = entryWithCandidates(
                "SELECT 1",
                "missing/Primary.java", 5,
                List.of(new kotlin.Pair<>("missing/A.java", 1), new kotlin.Pair<>("missing/B.java", 2)));
            LogJumpTarget target = panel.resolveEntryJump(e);
            assertTrue("候选全落空仍应 NotFound（真实失效场景不劣化）", target instanceof LjtNotFound);
            assertEquals("missing/Primary.java", ((LjtNotFound) target).getSourceFile());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test无主位置_候选可解析_落候选() throws Exception {
        // agent 侧主位置无调试信息（file=null）时，候选首位顶上
        writeRealSource("com/example/service/NavServiceImpl.java", "class NavServiceImpl {}\n");
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            LogSqlEntry e = entryWithCandidates(
                "SELECT 1",
                null, 0,
                List.of(new kotlin.Pair<>("com/example/service/NavServiceImpl.java", 12)));
            LogJumpTarget target = panel.resolveEntryJump(e);
            assertTrue("主位置 null 但有可解析候选 → Precise", target instanceof LjtPrecise);
            assertEquals(11, ((LjtPrecise) target).getLine());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void testMP分页COUNT标记_NoSource_不引导开拦截器() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            LogJumpTarget target = panel.resolveEntryJump(entry("SELECT COUNT(*) FROM user", SourceMarkers.MP_PAGINATION_COUNT, 0));
            assertTrue(
                "MP 分页 COUNT 应为 NoSource（纯提示），不得判为 MissingSource（引导开拦截器对其无意义）",
                target instanceof LjtNoSource);
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test无源码位置_有表名_MissingSource_引导弹框() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            // 旧行为回退表名搜索 SearchByTable，T-JumpCapture 后同输入必须判 MissingSource（防回退复活）
            LogJumpTarget target = panel.resolveEntryJump(entry("SELECT * FROM user u WHERE u.id = 1"));
            assertTrue("无源码位置应判 MissingSource（引导开启源码捕获）", target instanceof LjtMissingSource);
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test无源码位置_无表名_MissingSource() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            LogJumpTarget target = panel.resolveEntryJump(entry("SELECT 1"));
            assertTrue("提不到表名同样 MissingSource（不再区分 NoTable）", target instanceof LjtMissingSource);
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test旧版条目_sourceFile为null_不崩() throws Exception {
        // 旧版 F-43 保存的实时条目 sourceFile 恒为 null → MissingSource，不得 NPE
        // （headless 下不实际调用 jumpToSourceForTest，因其触发 Messages 弹窗；仅断言决策分支）
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            LogJumpTarget target = panel.resolveEntryJump(entry("SELECT * FROM t", null, 0));
            assertTrue("无源码位置应判 MissingSource，不得抛异常", target instanceof LjtMissingSource);
        } finally {
            panel.dispose();
        }
    }
}
