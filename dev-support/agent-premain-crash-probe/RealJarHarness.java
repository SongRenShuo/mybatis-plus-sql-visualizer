import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

/**
 * 在 Corretto 21 上用「真实 mps-sql-agent.jar + 隐藏 asm 包的 ClassLoader」复现用户现场。
 * asm 包不可见 = 模拟 JRebel 接管 premain 类加载后重定位 ASM 解析不到的形态。
 */
public class Harness {
    static File agentJar;
    static Class<?>[] LOADED = new Class<?>[0];

    public static void main(String[] args) throws Exception {
        agentJar = new File(args[0]);
        LOADED = new Class<?>[]{ Class.forName("com.mysql.cj.jdbc.StatementImpl") };
        String mode = args[1];   // hide | normal
        URL[] urls = { agentJar.toURI().toURL() };
        ClassLoader ldr = "hide".equals(mode) ? new AsmHidingLoader(urls) : new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());

        Class<?> main;
        try {
            main = Class.forName("cn.song.mybatisplus.sqlvis.agent.AgentMain", true, ldr);
        } catch (Throwable t) {
            System.out.println("HARNESS: AgentMain 加载失败 " + t);
            return;
        }
        System.out.println("HARNESS: AgentMain loaded by " + main.getClassLoader());
        Instrumentation inst = stubInstrumentation();
        try {
            Method pm = main.getMethod("premain", String.class, Instrumentation.class);
            pm.invoke(null, "port=47931;logLevel=warn", inst);
            System.out.println("HARNESS: premain RETURNED NORMALLY");
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            System.out.println("HARNESS: premain THREW " + c.getClass().getName() + ": " + c.getMessage());
            Throwable cur = c;
            int depth = 0;
            while (cur != null && depth < 4) {
                System.out.println("  == chain[" + depth + "] " + cur.getClass().getName() + ": " + cur.getMessage());
                StackTraceElement[] st = cur.getStackTrace();
                for (int i = 0; i < Math.min(8, st.length); i++) System.out.println("      at " + st[i]);
                cur = cur.getCause();
                depth++;
            }
        }
    }

    static Instrumentation stubInstrumentation() {
        return (Instrumentation) java.lang.reflect.Proxy.newProxyInstance(
            Harness.class.getClassLoader(),
            new Class<?>[]{ Instrumentation.class },
            (p, m, a) -> {
                switch (m.getName()) {
                    case "getAllLoadedClasses": return LOADED;
                    case "isModifiableClass": return true;
                    case "addTransformer": return null;
                    case "retransformClasses": return null;
                    case "toString": return "StubInstrumentation";
                    case "hashCode": return System.identityHashCode(p);
                    case "equals": return p == a[0];
                    default:
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == int.class) return 0;
                        if (rt == long.class) return 0L;
                        return null;
                }
            });
    }

    /** 让 cn/song/.../agent/asm.* 不可见——模拟 JRebel 路径上的 ASM 解析失败。 */
    static final class AsmHidingLoader extends URLClassLoader {
        AsmHidingLoader(URL[] u) { super(u, ClassLoader.getPlatformClassLoader()); }
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("cn.song.mybatisplus.sqlvis.agent.asm.")) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
