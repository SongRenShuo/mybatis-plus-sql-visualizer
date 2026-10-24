import java.lang.instrument.Instrumentation;
public class PreAgent {
    public static void premain(String a, Instrumentation inst) {
        try {
            Class.forName("org.h2.Driver");
            System.out.println("PRE: loaded org.h2.Driver");
        } catch (Throwable t) { System.out.println("PRE: no h2 " + t); }
    }
}
