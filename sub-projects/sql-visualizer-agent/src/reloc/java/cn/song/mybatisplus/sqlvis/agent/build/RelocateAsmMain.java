package cn.song.mybatisplus.sqlvis.agent.build;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * fat jar 打包 + ASM 自 relocate（构建期工具，跑在构建 JVM）：
 * ① agent 类原样入 jar；② ASM jar 的类经 ClassRemapper 把 {@code org.objectweb.asm} 前缀
 * 改名 {@code cn.song.mybatisplus.sqlvis.agent.asm}（asm/asm.commons/asm.signature/asm.tree 全前缀覆盖）；
 * ③ module-info / 签名文件剔除（自包含 jar 不参与模块系统）；④ manifest 写 attach 四键。
 * 不引入 shadow 插件：ASM 依赖面固定为 2 个 jar，自重写 ~40 行可控。
 *
 * <p>T-OldJvmPremainGuard 券02：入口改指 {@code AgentShim}，并在同一趟里把它的类文件版本降到
 * major 50（{@code Opcodes.V1_6}）——主源集按 {@code --release 8} 编译（major 52），而入口类必须
 * 能被 JDK 6（上限 50）加载，否则 {@code -javaagent} 会让宿主 JVM 启动即终止。降版本靠 ASM 重写，
 * 不引入 JDK 8 toolchain（javac 17 的 {@code --release} 最低 8，编不出 major 50）。
 * 该类只许用 Java 6 及更早 API，由 {@code AgentShimClassGuardTest} 守卫。
 */
public final class RelocateAsmMain {

    static final String ASM_PREFIX_OLD = "org/objectweb/asm";
    static final String ASM_PREFIX_NEW = "cn/song/mybatisplus/sqlvis/agent/asm";

    /** 引导类（manifest 双入口）在 jar 内的条目路径。 */
    static final String SHIM_ENTRY = "cn/song/mybatisplus/sqlvis/agent/AgentShim.class";

    /** 引导类在 jar 内的类版本（major 50 = Java 6）：JDK 6/7 都要能加载它。 */
    static final int SHIM_CLASS_VERSION = Opcodes.V1_6;

    public static void main(String[] args) throws Exception {
        String asmPaths = null, agentJar = null, outPath = null;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--asm": asmPaths = args[i + 1]; break;
                case "--agent": agentJar = args[i + 1]; break;
                case "--out": outPath = args[i + 1]; break;
                default: break;
            }
        }
        if (asmPaths == null || agentJar == null || outPath == null) {
            throw new IllegalArgumentException("usage: --asm <p1;p2> --agent <jar> --out <jar>");
        }

        final Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                if (internalName.equals(ASM_PREFIX_OLD) || internalName.startsWith(ASM_PREFIX_OLD + "/")) {
                    return ASM_PREFIX_NEW + internalName.substring(ASM_PREFIX_OLD.length());
                }
                return internalName;
            }
        };

        final Manifest manifest = new Manifest();
        final Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.put(new Attributes.Name("Premain-Class"), "cn.song.mybatisplus.sqlvis.agent.AgentShim");
        attrs.put(new Attributes.Name("Agent-Class"), "cn.song.mybatisplus.sqlvis.agent.AgentShim");
        attrs.put(new Attributes.Name("Can-Redefine-Classes"), "true");
        attrs.put(new Attributes.Name("Can-Retransform-Classes"), "true");

        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(outPath), manifest)) {
            // 1) agent 主类：字节码内的 org.objectweb.asm 引用同样重映射
            //    （否则 ConnectWeaver 运行期 NCDFE——fat jar 内已无明文 ASM）
            try (JarFile jf = new JarFile(agentJar)) {
                var entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    if (e.isDirectory() || skip(e.getName())) {
                        continue;
                    }
                    final byte[] bytes = readAll(jf.getInputStream(e));
                    final byte[] mapped;
                    if (e.getName().endsWith(".class") && !e.getName().endsWith("module-info.class")) {
                        // 引导类顺手降到 major 50：入口必须能被 JDK 6/7 加载（T-OldJvmPremainGuard）
                        mapped = rewriteClass(bytes, remapper, SHIM_ENTRY.equals(e.getName()));
                    } else {
                        mapped = bytes;
                    }
                    out.putNextEntry(new ZipEntry(e.getName()));
                    out.write(mapped);
                    out.closeEntry();
                }
            }
            // 2) ASM（relocate）
            for (String path : asmPaths.split(";")) {
                if (path.isBlank()) {
                    continue;
                }
                try (JarFile jf = new JarFile(new File(path))) {
                    var entries = jf.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry e = entries.nextElement();
                        final String name = e.getName();
                        if (e.isDirectory() || skip(name)) {
                            continue;
                        }
                        final String newName = name.startsWith(ASM_PREFIX_OLD) || name.startsWith(ASM_PREFIX_OLD + "/")
                                ? ASM_PREFIX_NEW + name.substring(ASM_PREFIX_OLD.length()) : name;
                        final byte[] bytes = readAll(jf.getInputStream(e));
                        final byte[] mapped;
                        if (name.endsWith(".class") && !name.endsWith("module-info.class")) {
                            mapped = rewriteClass(bytes, remapper, false);
                        } else {
                            mapped = bytes;
                        }
                        out.putNextEntry(new ZipEntry(newName));
                        out.write(mapped);
                        out.closeEntry();
                    }
                }
            }
        }
        System.out.println("agent fat jar written: " + outPath);
    }

    private static boolean skip(String name) {
        if (name.equals("module-info.class") || name.startsWith("META-INF/")) {
            return true;
        }
        return false;
    }

    /**
     * 类字节重写：ASM 引用重定位 +（引导类时）类版本下修。
     *
     * <p>版本下修只改 constant-pool 之后的 major 字段（ASM 的 {@code visit} 第一个参数），
     * 不触碰其余字节；引导类源码只许用 Java 6 及更早 API（守卫见 {@code AgentShimClassGuardTest}），
     * 否则降版本后 JDK 6 仍会在解析常量池时失败。
     */
    private static byte[] rewriteClass(byte[] bytes, Remapper remapper, boolean downlevelToShimVersion) {
        final ClassReader cr = new ClassReader(bytes);
        final ClassWriter cw = new ClassWriter(cr, 0);
        ClassVisitor target = new ClassRemapper(cw, remapper);
        if (downlevelToShimVersion) {
            target = new ClassVisitor(Opcodes.ASM9, target) {
                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    super.visit(SHIM_CLASS_VERSION, access, name, signature, superName, interfaces);
                }
            };
        }
        cr.accept(target, 0);
        return cw.toByteArray();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private RelocateAsmMain() {
    }
}
