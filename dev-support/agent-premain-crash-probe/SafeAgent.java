import java.lang.instrument.Instrumentation;
public class SafeAgent {
    public static void premain(String a, Instrumentation inst) {
        try {
            install(a, inst);
        } catch (Throwable t) {
            System.err.println("[ProbeAgent] premain degraded: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }
    private static void install(String a, Instrumentation inst) {
        throw new LinkageError("simulated: cn/song/mybatisplus/sqlvis/agent/asm/Opcodes");
    }
}
