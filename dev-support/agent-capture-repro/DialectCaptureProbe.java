import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 最小捕获载荷（T-CaptureCoverage25 真机取证用）：只用 java.sql，不依赖任何 ORM。
 *
 * <p>用法：{@code java -cp <probe+driver> DialectCaptureProbe <url> <user> <password>}
 *
 * <p>跑 4 条 DML（建表后 INSERT(带参) / SELECT(带参) / UPDATE / SELECT），供假接收端断言
 * 「增强捕获能收到该方言驱动的 SQL 事件」。建表/删表用普通 Statement，不参与判定。
 */
public final class DialectCaptureProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: DialectCaptureProbe <url> <user> <password>");
            System.exit(2);
        }
        Class.forName(args[3].trim());
        try (Connection c = DriverManager.getConnection(args[0], args[1], args[2])) {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("DROP TABLE IF EXISTS mpv_capture_probe");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("CREATE TABLE mpv_capture_probe(id INT PRIMARY KEY, label VARCHAR(40))");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mpv_capture_probe(id, label) VALUES(?, ?)")) {
                ps.setInt(1, 1);
                ps.setString(2, "synthetic");
                if (ps.executeUpdate() != 1) throw new AssertionError("insert count");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT label FROM mpv_capture_probe WHERE id = ?")) {
                ps.setInt(1, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new AssertionError("select empty");
                    System.out.println("PROBE_SELECT_OK=" + rs.getString(1));
                }
            }
            try (Statement s = c.createStatement()) {
                if (s.executeUpdate("UPDATE mpv_capture_probe SET label = 'updated' WHERE id = 1") != 1) {
                    throw new AssertionError("update count");
                }
            }
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM mpv_capture_probe")) {
                rs.next();
                System.out.println("PROBE_COUNT=" + rs.getInt(1));
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("DROP TABLE mpv_capture_probe");
            }
        }
        System.out.println("PROBE_DONE");
        // 捕获事件由 agent 的发送循环异步 flush；进程立刻退出会丢事件（实测 TiDB 首轮 EVENT=0）。
        // 留 6s 让 flush 落盘，避免把「跑太快」误判成「捕获不工作」。
        Thread.sleep(6000L);
    }
}
