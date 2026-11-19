// T-SyncK2JMaster29（波C）翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/TinyClassFileWriter.kt；T-SyncK2JMaster180 对齐 master cd1b94562 终态（D239 扩「方法 + 注解」生成）
package cn.song.mybatisplus.visualizer.mcp;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 class 文件生成器（T-McpDynamicToolset 真机 ASM 不可达修复，20261005；
 * D239 扩「方法 + 注解」生成，2026-11-19）——零外部依赖。
 *
 * <p><b>为什么不用 org.objectweb.asm</b>：平台 262 自带 asm 库（{@code intellij.libraries.asm.jar}），
 * {@code org.objectweb.asm} 因此是平台已知包——插件 classloader 对该包 parent-first 接管，插件自带
 * asm jar 的同包类被跳过；平台 asm 又在对插件不可见的内部库 jar——两处落空 → 真机
 * {@code NoClassDefFoundError: org/objectweb/asm/ClassWriter}（20261005 日常 IDE 262.10968 六轮日志
 * 实锤，见开发计划 20261005-1335）。壳结构固定且极小，直接按 class 文件规范写字节即可，
 * 不依赖任何字节码库；壳为运行期生成、不进插件 jar（verifier 不可见，与 ASM 版形态一致）。</p>
 *
 * <p><b>D239（2026-11-19）扩了什么、为什么</b>：真机证明「只 extends 业务类 + 无参构造」的壳类在
 * kotlin-reflect 眼里 {@code declared=0 / kotlinMeta=false}，平台扫不到带 {@code @McpTool} 的方法 →
 * {@code requireAnyTool} 抛异常打断 toolset 遍历 → 工具清单只列前 1~3 个（详见 D238/D239 册）。
 * 现壳类形态：{@code final class X extends <superName> implements McpToolset}，且
 * <ul>
 *   <li><b>自己声明工具方法</b>（委托体：{@code new <target>} → {@code <load args>} →
 *       {@code invokevirtual} → return），使 {@code declaredMethods} 非 0；</li>
 *   <li>每个方法带<b>复制的 {@code @McpTool} / {@code @McpDescription} / {@code @McpToolHints} 注解</b>；</li>
 *   <li>class 上带<b>复制的 {@code kotlin.Metadata}</b>，让 kotlin-reflect 按父类元数据解析它。</li>
 * </ul></p>
 *
 * <p><b>写盘纪律（两阶段）</b>：常量池必须在 {@code constant_pool_count} 写出前完全定稿。因此所有
 * 「会产生新常量池条目」的工作（方法体、注解 payload）都先在第一阶段做完并落成字节数组，
 * 第二阶段才写文件头 + 常量池 + 结构体。任何在写盘后追加 CP 条目的写法都会造成索引错乱
 * （2026-11-19 首版自测踩过）。</p>
 *
 * <p>版本 V17（major 61）：与插件编译目标一致，243 JBR 21 可加载。</p>
 *
 * <p>移植注记：master 的 {@code internal object} 按本仓惯例落为 final class + 全静态成员（internal 升
 * public）；常量池条目写盘闭包落为 {@link Entry} 函数式接口（{@link DataOutputStream} 写出抛受检
 * {@link IOException}）；{@code 0xCAFEBABE} Java 十六进制 int 字面量按 32 位回绕合法直写。
 * utf8 索引构建期急切解析（master c7a75a3：惰性解析曾致 writeTo 迭代期 ConcurrentModificationException）。</p>
 */
public final class TinyClassFileWriter {

    private TinyClassFileWriter() {
    }

    /** 一个注解（type descriptor 形如 {@code Lcom/intellij/mcpserver/annotations/McpTool;}）。 */
    public static final class AnnotationValue {

        public final String typeDescriptor;
        /** 元素名 → 值；值支持 String / Int / Boolean / Enum / Class / List&lt;*&gt;（数组，元素递归同规则）。 */
        public final List<kotlin.Pair<String, Object>> elements;

        public AnnotationValue(String typeDescriptor, List<kotlin.Pair<String, Object>> elements) {
            this.typeDescriptor = typeDescriptor;
            this.elements = elements != null ? elements : List.of();
        }
    }

    /** 一个委托方法：壳类声明它，方法体 {@code new target} 后转发同名同描述符调用。 */
    public static final class DelegateMethod {

        public final String name;
        /** JVM 方法描述符（suspend 方法末位含 {@code Lkotlin/coroutines/Continuation;}）。 */
        public final String descriptor;
        public final String targetClassFqn;
        public final List<AnnotationValue> annotations;
        /**
         * 占位方法（D239 四轮）：方法体只有 {@code throw new UnsupportedOperationException()}。
         *
         * <p>为什么需要：壳类复制了业务类的 {@code kotlin.Metadata}，而 metadata 里描述的<b>每一个</b>成员
         * 都必须在壳类里按签名找得到 Java 方法，否则 kotlin-reflect 在展开函数表时抛
         * {@code KotlinReflectionInternalError: Could not compute caller for function: private final
         * suspend fun executeExplain(...)}（真机 2026-11-19 18:01 逐壳类实证）→ 该 toolset 一个
         * 工具都出不来。业务类的私有/内部辅助函数不会被平台当工具调用，故占位体抛异常即可。</p>
         */
        public final boolean stub;

        public DelegateMethod(String name, String descriptor, String targetClassFqn,
                              List<AnnotationValue> annotations, boolean stub) {
            this.name = name;
            this.descriptor = descriptor;
            this.targetClassFqn = targetClassFqn;
            this.annotations = annotations != null ? annotations : List.of();
            this.stub = stub;
        }
    }

    /** 兼容旧签名：只生成「extends + 无参构造」的最小壳（无方法/无注解）。 */
    public static byte[] shellBytes(String shellName, String superName, String ifaceName) {
        return shellBytes(shellName, superName, ifaceName, null, List.of(), List.of());
    }

    /**
     * 生成壳类字节码。
     *
     * @param superCtorArg 非 null 时构造器为 {@code (String)} 形态（{@code ldc arg} + {@code super(arg)}）——用于
     *   {@code extends McpToolsetSupport}（该基类只有 {@code (String toolName)} 构造）；null 走无参 {@code super()}。
     * @param delegates 壳类自己声明并转发的方法（D239：让 declaredMethods 非 0）
     * @param classAnnotations class 级注解（D239：复制 {@code kotlin.Metadata}）
     */
    public static byte[] shellBytes(String shellName, String superName, String ifaceName,
                                    String superCtorArg, List<DelegateMethod> delegates,
                                    List<AnnotationValue> classAnnotations) {
        try {
            ConstantPool cp = new ConstantPool();

            // ---------- 第一阶段：把一切会新增常量的东西先算成字节 ----------
            int thisClass = cp.classRef(shellName.replace('.', '/'));
            int superClass = cp.classRef(superName.replace('.', '/'));
            int ifaceClass = cp.classRef(ifaceName.replace('.', '/'));
            int initName = cp.utf8("<init>");
            String ctorDesc = superCtorArg == null ? "()V" : "(Ljava/lang/String;)V";
            int ctorDescIdx = cp.utf8(ctorDesc);
            int superInit = cp.methodRef(superClass, cp.nameAndType(initName, ctorDescIdx));

            // 构造器方法体：aload_0 [+ ldc_w arg] + invokespecial super.<init> + return
            ByteArrayOutputStream ctorBody = new ByteArrayOutputStream(16);
            DataOutputStream c = new DataOutputStream(ctorBody);
            c.writeByte(0x2a); // aload_0
            if (superCtorArg != null) {
                c.writeByte(0x13); // ldc_w（u2 索引，避开 ldc 的 255 上限）
                c.writeShort(cp.string(superCtorArg));
            }
            c.writeByte(0xb7); // invokespecial
            c.writeShort(superInit);
            c.writeByte(0xb1); // return
            byte[] ctorCode = ctorBody.toByteArray();

            List<PreparedMethod> prepared = new ArrayList<>(delegates.size());
            for (DelegateMethod d : delegates) {
                prepared.add(prepareMethod(cp, initName, d));
            }

            byte[] classAnnBytes = buildAnnotations(cp, classAnnotations);
            int codeAttr = cp.utf8("Code");
            boolean anyAnnotations = !classAnnBytes.isEmpty()
                || prepared.stream().anyMatch(m -> m.annotations.length > 0);
            int rvaAttr = anyAnnotations ? cp.utf8("RuntimeVisibleAnnotations") : 0;

            // ---------- 第二阶段：写盘（此后不得再新增常量池条目） ----------
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // minor_version
            out.writeShort(V17_MAJOR); // major_version
            out.writeShort(cp.size() + 1); // constant_pool_count（1-based，index 0 保留）
            cp.writeTo(out);
            out.writeShort(ACC_PUBLIC | ACC_FINAL | ACC_SUPER);
            out.writeShort(thisClass);
            out.writeShort(superClass);
            out.writeShort(1); // interfaces_count
            out.writeShort(ifaceClass);
            out.writeShort(0); // fields_count
            out.writeShort(1 + prepared.size()); // methods_count

            // 构造器（max_locals 必须覆盖 this + 形参：带参形态 2，无参 1）
            out.writeShort(ACC_PUBLIC);
            out.writeShort(initName);
            out.writeShort(ctorDescIdx);
            out.writeShort(1); // attributes_count: Code
            writeCodeAttribute(out, codeAttr, ctorCode,
                superCtorArg == null ? 1 : 2,
                superCtorArg == null ? 1 : 2);

            // 委托方法
            for (PreparedMethod m : prepared) {
                out.writeShort(ACC_PUBLIC);
                out.writeShort(m.nameIdx);
                out.writeShort(m.descIdx);
                out.writeShort(1 + (m.annotations.length > 0 ? 1 : 0)); // attributes_count
                writeCodeAttribute(out, codeAttr, m.code, m.maxStack, m.maxLocals);
                if (m.annotations.length > 0) {
                    out.writeShort(rvaAttr);
                    out.writeInt(m.annotations.length);
                    out.write(m.annotations);
                }
            }

            // class 级注解
            if (classAnnBytes.isEmpty()) {
                out.writeShort(0);
            } else {
                out.writeShort(1);
                out.writeShort(rvaAttr);
                out.writeInt(classAnnBytes.length);
                out.write(classAnnBytes);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            // master 无受检异常：内存流写出实际不可能抛，透传保形（Kotlin 语义 = 原样抛出）
            throw sneakyThrow(e);
        }
    }

    private static final class PreparedMethod {
        final int nameIdx;
        final int descIdx;
        final byte[] code;
        final int maxStack;
        final int maxLocals;
        final byte[] annotations;

        PreparedMethod(int nameIdx, int descIdx, byte[] code, int maxStack, int maxLocals, byte[] annotations) {
            this.nameIdx = nameIdx;
            this.descIdx = descIdx;
            this.code = code;
            this.maxStack = maxStack;
            this.maxLocals = maxLocals;
            this.annotations = annotations;
        }
    }

    private static PreparedMethod prepareMethod(ConstantPool cp, int initName, DelegateMethod d) throws IOException {
        int nameIdx = cp.utf8(d.name);
        int descIdx = cp.utf8(d.descriptor);
        ByteArrayOutputStream body = new ByteArrayOutputStream(64);
        DataOutputStream o = new DataOutputStream(body);
        int paramWidth = 0;
        for (char p : jvmParameters(d.descriptor)) {
            paramWidth += (p == 'J' || p == 'D') ? 2 : 1;
        }
        if (d.stub) {
            // new UnsupportedOperationException(); athrow
            int uoe = cp.classRef("java/lang/UnsupportedOperationException");
            o.writeByte(0xbb); // new
            o.writeShort(uoe);
            o.writeByte(0x59); // dup
            o.writeByte(0xb7); // invokespecial <init>()V
            o.writeShort(cp.methodRef(uoe, cp.nameAndType(initName, cp.utf8("()V"))));
            o.writeByte(0xbf); // athrow
            return new PreparedMethod(nameIdx, descIdx, body.toByteArray(), 2,
                1 + paramWidth, buildAnnotations(cp, d.annotations));
        }
        int targetClass = cp.classRef(d.targetClassFqn.replace('.', '/'));
        int targetInit = cp.methodRef(targetClass, cp.nameAndType(initName, cp.utf8("()V")));
        int targetCall = cp.methodRef(targetClass, cp.nameAndType(nameIdx, descIdx));

        o.writeByte(0xbb); // new
        o.writeShort(targetClass);
        o.writeByte(0x59); // dup
        o.writeByte(0xb7); // invokespecial <target>.<init>()V
        o.writeShort(targetInit);
        int slot = 1;
        int stack = 3; // new 结果 + dup 副本 + 接收者
        for (char p : jvmParameters(d.descriptor)) {
            writeLoad(o, p, slot);
            int width = (p == 'J' || p == 'D') ? 2 : 1;
            stack += width;
            slot += width;
        }
        o.writeByte(0xb6); // invokevirtual <target>.<name><desc>
        o.writeShort(targetCall);
        switch (jvmReturnKind(d.descriptor)) {
            case 'V' -> o.writeByte(0xb1);
            case 'J' -> o.writeByte(0xad);
            case 'D' -> o.writeByte(0xaf);
            case 'F' -> o.writeByte(0xae);
            case 'I' -> o.writeByte(0xac);
            default -> o.writeByte(0xb0);
        }
        return new PreparedMethod(nameIdx, descIdx, body.toByteArray(), Math.max(stack, 1), slot,
            buildAnnotations(cp, d.annotations));
    }

    private static void writeCodeAttribute(DataOutputStream out, int codeAttrIdx, byte[] code,
                                           int maxStack, int maxLocals) throws IOException {
        out.writeShort(codeAttrIdx);
        out.writeInt(2 + 2 + 4 + code.length + 2 + 0 + 2 + 0);
        out.writeShort(maxStack);
        out.writeShort(maxLocals);
        out.writeInt(code.length);
        out.write(code);
        out.writeShort(0); // exception_table_length
        out.writeShort(0); // Code attributes_count
    }

    /** 构建 RuntimeVisibleAnnotations 的 <b>info 部分</b>（不含属性名与长度），空列表返回空数组。 */
    private static byte[] buildAnnotations(ConstantPool cp, List<AnnotationValue> annotations) throws IOException {
        if (annotations.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream(256);
        DataOutputStream p = new DataOutputStream(payload);
        p.writeShort(annotations.size());
        for (AnnotationValue ann : annotations) {
            p.writeShort(cp.utf8(ann.typeDescriptor));
            p.writeShort(ann.elements.size());
            for (kotlin.Pair<String, Object> element : ann.elements) {
                p.writeShort(cp.utf8(element.getFirst()));
                writeElementValue(p, cp, element.getSecond());
            }
        }
        return payload.toByteArray();
    }

    private static void writeElementValue(DataOutputStream out, ConstantPool cp, Object value) throws IOException {
        if (value instanceof String s) {
            out.writeByte('s');
            out.writeShort(cp.utf8(s));
        } else if (value instanceof Boolean b) {
            out.writeByte('Z');
            out.writeShort(cp.integer(b ? 1 : 0));
        } else if (value instanceof Integer i) {
            out.writeByte('I');
            out.writeShort(cp.integer(i));
        } else if (value instanceof Byte b) {
            // B/S/C 与 Z/I 一样用 CONSTANT_Integer 承载（class 文件规范），仅 tag 不同。
            out.writeByte('B');
            out.writeShort(cp.integer(b.intValue()));
        } else if (value instanceof Short s) {
            out.writeByte('S');
            out.writeShort(cp.integer(s.intValue()));
        } else if (value instanceof Character ch) {
            out.writeByte('C');
            out.writeShort(cp.integer(ch.charValue()));
        } else if (value instanceof List<?> list) {
            out.writeByte('[');
            out.writeShort(list.size());
            for (Object element : list) {
                writeElementValue(out, cp, element != null ? element : "");
            }
        } else if (value instanceof Enum<?> e) {
            // D239：@McpToolHints 是枚举数组，必须按 'e'（enum_const_value）编码——
            // 降级成字符串会让注解解析在类加载时抛 AnnotationFormatError。
            out.writeByte('e');
            out.writeShort(cp.utf8("L" + declaringJavaClass(e).getName().replace('.', '/') + ";"));
            out.writeShort(cp.utf8(e.name()));
        } else if (value instanceof Class<?> clazz) {
            out.writeByte('c');
            out.writeShort(cp.utf8("L" + clazz.getName().replace('.', '/') + ";"));
        } else {
            // 未知类型降级为字符串：注解内容失真好过整体注册失败
            out.writeByte('s');
            out.writeShort(cp.utf8(String.valueOf(value)));
        }
    }

    /**
     * 枚举常量所属的枚举类。
     *
     * <p><b>不能从 {@code javaClass.enclosingClass ?: superclass} 推</b>（D239 真机 2026-11-19 定案）：
     * 无类体的枚举常量 {@code javaClass} 就是枚举类本身，{@code enclosingClass} 为 null、{@code superclass} 是
     * {@code java.lang.Enum}——按旧写法会把 type_name 写成 {@code Ljava/lang/Enum;}，而 JDK 的
     * {@code AnnotationParser.parseEnumValue} 要求 class 文件里的枚举类型名与注解元素声明类型<b>逐字相等</b>，
     * 不等即抛 AnnotationTypeMismatchException（真机原话：
     * {@code Incorrectly typed data found for annotation element McpToolHints.readOnlyHint()
     * (Found data of type java.lang.Enum.TRUE)}）→ 整个 toolset 加载失败、工具全不出现。
     * 带类体的常量（{@code javaClass} 是匿名子类）才回退到 {@code superclass}。</p>
     */
    private static Class<?> declaringJavaClass(Enum<?> value) {
        Class<?> javaClass = value.getClass();
        return javaClass.isEnum() ? javaClass : javaClass.getSuperclass();
    }

    /** 解析方法描述符参数类型（引用/数组统一归为 'L'，基本类型按原字符 'I'/'J'/'F'/'D'/'Z'…）。 */
    private static List<Character> jvmParameters(String descriptor) {
        List<Character> params = new ArrayList<>();
        int i = 1; // 跳过 '('
        while (i < descriptor.length() && descriptor.charAt(i) != ')') {
            char c = descriptor.charAt(i);
            switch (c) {
                case 'L' -> {
                    while (i < descriptor.length() && descriptor.charAt(i) != ';') i++;
                    params.add('L');
                }
                case '[' -> {
                    while (i < descriptor.length() && descriptor.charAt(i) == '[') i++;
                    if (i < descriptor.length() && descriptor.charAt(i) == 'L') {
                        while (i < descriptor.length() && descriptor.charAt(i) != ';') i++;
                    }
                    params.add('L');
                }
                default -> params.add(c);
            }
            i++;
        }
        return params;
    }

    private static char jvmReturnKind(String descriptor) {
        int idx = descriptor.indexOf(')');
        return (idx >= 0 && idx + 1 < descriptor.length()) ? descriptor.charAt(idx + 1) : 'V';
    }

    /**
     * 参数加载指令。
     *
     * <p><b>短形态基址各不相同</b>（JVM 规范 §6）：{@code iload_0}=0x1a、{@code lload_0}=0x1e、
     * {@code fload_0}=0x22、{@code dload_0}=0x26、{@code aload_0}=0x2a，而带索引形态是
     * {@code iload}=0x15、{@code lload}=0x16、{@code fload}=0x17、{@code dload}=0x18、
     * {@code aload}=0x19——<b>不能拿带索引基址 + slot 硬算</b>（2026-11-19 首版这么写，
     * 参数为 int 时会生成 lload 导致 VerifyError: Bad local variable type）。</p>
     */
    private static void writeLoad(DataOutputStream out, char kind, int slot) throws IOException {
        int shortBase = switch (kind) {
            case 'J' -> 0x1e; // lload_0..3
            case 'F' -> 0x22; // fload_0..3
            case 'D' -> 0x26; // dload_0..3
            case 'I', 'Z', 'B', 'C', 'S' -> 0x1a; // iload_0..3
            default -> 0x2a; // aload_0..3
        };
        int indexed = switch (kind) {
            case 'J' -> 0x16;
            case 'F' -> 0x17;
            case 'D' -> 0x18;
            case 'I', 'Z', 'B', 'C', 'S' -> 0x15;
            default -> 0x19;
        };
        if (slot >= 0 && slot <= 3) {
            out.writeByte(shortBase + slot);
        } else {
            out.writeByte(indexed);
            out.writeByte(slot);
        }
    }

    private static final int V17_MAJOR = 61;
    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_FINAL = 0x0010;
    private static final int ACC_SUPER = 0x0020;

    /** 常量池条目写盘闭包（DataOutputStream 写出抛受检 IOException，专用函数式接口承载）。 */
    @FunctionalInterface
    private interface Entry {
        void writeTo(DataOutputStream out) throws IOException;
    }

    /** 极简常量池：Utf8 / Class / String / Integer / NameAndType / Methodref。 */
    private static final class ConstantPool {

        private final List<Entry> items = new ArrayList<>();
        private final Map<String, Integer> utf8Index = new HashMap<>();
        private final Map<String, Integer> cache = new HashMap<>();

        int utf8(String value) {
            Integer existing = utf8Index.get(value);
            if (existing != null) {
                return existing;
            }
            int index = items.size() + 1;
            items.add(out -> {
                out.writeByte(1);
                out.writeUTF(value);
            });
            utf8Index.put(value, index);
            return index;
        }

        int classRef(String name) {
            Integer cached = cache.get("C:" + name);
            if (cached != null) {
                return cached;
            }
            int nameIdx = utf8(name); // 先解析（急切），writeTo 迭代期不得再改 items
            int index = items.size() + 1;
            items.add(out -> {
                out.writeByte(7);
                out.writeShort(nameIdx);
            });
            cache.put("C:" + name, index);
            return index;
        }

        int string(String value) {
            Integer cached = cache.get("S:" + value);
            if (cached != null) {
                return cached;
            }
            int idx = utf8(value);
            int index = items.size() + 1;
            items.add(out -> {
                out.writeByte(8);
                out.writeShort(idx);
            });
            cache.put("S:" + value, index);
            return index;
        }

        int integer(int value) {
            Integer cached = cache.get("I:" + value);
            if (cached != null) {
                return cached;
            }
            int index = items.size() + 1;
            items.add(out -> {
                out.writeByte(3);
                out.writeInt(value);
            });
            cache.put("I:" + value, index);
            return index;
        }

        int nameAndType(int nameIdx, int descriptorIdx) {
            int index = items.size() + 1;
            items.add(out -> {
                out.writeByte(12);
                out.writeShort(nameIdx);
                out.writeShort(descriptorIdx);
            });
            return index;
        }

        int methodRef(int classIdx, int nameAndTypeIdx) {
            int index = items.size() + 1;
            items.add(out -> {
                out.writeByte(10);
                out.writeShort(classIdx);
                out.writeShort(nameAndTypeIdx);
            });
            return index;
        }

        int size() {
            return items.size();
        }

        void writeTo(DataOutputStream out) throws IOException {
            for (Entry item : items) {
                item.writeTo(out);
            }
        }
    }

    /** 受检异常透传（本仓既有 sneakyThrow 惯例，McpToolsetSupport/DatabaseSchemaResolver 同款）。 */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }
}
