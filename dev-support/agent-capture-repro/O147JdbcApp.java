package acceptance.o147;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** Synthetic data only. No agent classes on the application classpath. */
public final class O147JdbcApp {
    public interface Mapper {
        @Insert("INSERT INTO o147_item(id, label) VALUES(#{id}, 'synthetic')")
        int insert(int id);
        @Select("SELECT label FROM o147_item WHERE id = #{id}")
        String select(int id);
        @Update("UPDATE o147_item SET label = 'synthetic-updated' WHERE id = #{id}")
        int update(int id);
    }

    public static void main(String[] args) throws Exception {
        String url = "jdbc:h2:mem:o147_acceptance;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE o147_item(id INT PRIMARY KEY, label VARCHAR(80))");
        }
        Configuration config = new Configuration(new Environment("local-synthetic", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver", url, "sa", "")));
        config.setLogImpl(StdOutImpl.class);
        config.addMapper(Mapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
        System.out.println("O147_READY");
        System.out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        String command;
        while ((command = in.readLine()) != null) {
            if ("QUIT".equals(command)) break;
            if (!"RUN".equals(command)) throw new IllegalArgumentException(command);
            try (SqlSession session = factory.openSession(true)) {
                Mapper mapper = session.getMapper(Mapper.class);
                if (mapper.insert(147) != 1) throw new AssertionError("insert count");
                if (!"synthetic".equals(mapper.select(147))) throw new AssertionError("select result");
                if (mapper.update(147) != 1) throw new AssertionError("update count");
                boolean failed = false;
                try { mapper.insert(147); } catch (org.apache.ibatis.exceptions.PersistenceException expected) {
                    failed = true;
                    System.out.println("O147_EXPECTED_DUPLICATE_KEY");
                }
                if (!failed) throw new AssertionError("duplicate insert must fail");
            }
            System.out.println("O147_DONE");
            System.out.flush();
        }
    }
}
