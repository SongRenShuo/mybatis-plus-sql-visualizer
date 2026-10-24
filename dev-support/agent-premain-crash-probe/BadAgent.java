import java.lang.instrument.Instrumentation;
public class BadAgent {
    public static void premain(String a, Instrumentation inst) {
        throw new LinkageError("simulated: cn/song/mybatisplus/sqlvis/agent/asm/Opcodes");
    }
}
