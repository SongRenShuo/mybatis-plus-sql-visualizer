import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 三族 attach 事件回流探针宿主（2026-11-01）。
 *
 * <p>用途：起一个**独立 JVM**，用厂商驱动连瀚高 / 海量 / 金仓，按固定节奏执行几条语句，
 * 让插件的增强捕获（attach 通道）有真实事件可回流到 IDE 面板。宿主本身不做任何判定，
 * 只保证「有语句在跑」。</p>
 *
 * <p>用法：{@code java -cp "drivers/*;." Host <driverClass> <url> <user> <password> <tag> [秒数]}</p>
 *
 * <p>语句选择：全部为**只读且不依赖业务表**的形态（SELECT 常量、驱动元数据、临时表），
 * 避免在被测库上留下任何对象。三族里只有金仓是共享实例，故额外只做只读。</p>
 */
public final class Host {

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("usage: Host <driverClass> <url> <user> <password> <tag> [seconds]");
            System.exit(2);
        }
        final String driverClass = args[0];
        final String url = args[1];
        final String user = args[2];
        final String password = args[3];
        final String tag = args[4];
        final int seconds = args.length > 5 ? Integer.parseInt(args[5]) : 120;

        Class.forName(driverClass);
        System.out.println("[Host] tag=" + tag + " driver=" + driverClass + " url=" + url);

        final long deadline = System.currentTimeMillis() + seconds * 1000L;
        int round = 0;
        Connection conn = null;
        while (System.currentTimeMillis() < deadline) {
            round++;
            try {
                if (conn == null || conn.isClosed()) {
                    conn = DriverManager.getConnection(url, user, password);
                    System.out.println("[Host] tag=" + tag + " connected round=" + round);
                }
                try (Statement st = conn.createStatement()) {
                    st.executeQuery("SELECT 1");
                }
                try (PreparedStatement ps = conn.prepareStatement("SELECT ? AS probe_round")) {
                    ps.setInt(1, round);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt(1);
                        }
                    }
                }
                // 元数据语句：让事件里的 SQL 有可读文本，同时验证驱动层织入对非业务 SQL 也生效
                try (Statement st = conn.createStatement()) {
                    st.executeQuery("SELECT current_database()");
                }
                System.out.println("[Host] tag=" + tag + " round=" + round + " ok");
            } catch (Throwable t) {
                System.out.println("[Host] tag=" + tag + " round=" + round + " FAILED "
                        + t.getClass().getName() + ": " + t.getMessage());
                try {
                    if (conn != null) {
                        conn.close();
                    }
                } catch (Throwable ignore) {
                    // 收尾失败不影响下一轮重连
                }
                conn = null;
            }
            Thread.sleep(3000L);
        }
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (Throwable ignore) {
            // 退出即释放
        }
        System.out.println("[Host] tag=" + tag + " done rounds=" + round);
    }
}
