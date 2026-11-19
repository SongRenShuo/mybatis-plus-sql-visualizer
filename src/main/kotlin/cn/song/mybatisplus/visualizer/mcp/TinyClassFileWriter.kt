package cn.song.mybatisplus.visualizer.mcp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * 极简 class 文件生成器（T-McpDynamicToolset 真机 ASM 不可达修复，20261005；
 * D239 扩「方法 + 注解」生成，2026-11-19）——零外部依赖。
 *
 * **为什么不用 org.objectweb.asm**：平台 262 自带 asm 库（`intellij.libraries.asm.jar`），
 * `org.objectweb.asm` 因此是平台已知包——插件 classloader 对该包 parent-first 接管，插件自带
 * asm jar 的同包类被跳过；平台 asm 又在对插件不可见的内部库 jar——两处落空 → 真机
 * `NoClassDefFoundError: org/objectweb/asm/ClassWriter`（20261005 日常 IDE 262.10968 六轮日志
 * 实锤，见开发计划 20261005-1335）。壳结构固定且极小，直接按 class 文件规范写字节即可，
 * 不依赖任何字节码库；壳为运行期生成、不进插件 jar（verifier 不可见，与 ASM 版形态一致）。
 *
 * **D239（2026-11-19）扩了什么、为什么**：真机证明「只 extends 业务类 + 无参构造」的壳类在
 * kotlin-reflect 眼里 `declared=0 / kotlinMeta=false`，平台扫不到带 `@McpTool` 的方法 →
 * `requireAnyTool` 抛异常打断 toolset 遍历 → 工具清单只列前 1~3 个（详见 D238/D239 册）。
 * 现壳类形态：`final class X extends <superName> implements McpToolset`，且
 *  - **自己声明工具方法**（委托体：`new <target>` → `<load args>` → `invokevirtual` → return），
 *    使 `declaredMethods` 非 0；
 *  - 每个方法带**复制的 `@McpTool` / `@McpDescription` / `@McpToolHints` 注解**；
 *  - class 上带**复制的 `kotlin.Metadata`**，让 kotlin-reflect 按父类元数据解析它。
 *
 * **写盘纪律（两阶段）**：常量池必须在 `constant_pool_count` 写出前完全定稿。因此所有
 * 「会产生新常量池条目」的工作（方法体、注解 payload）都先在第一阶段做完并落成字节数组，
 * 第二阶段才写文件头 + 常量池 + 结构体。任何在写盘后追加 CP 条目的写法都会造成索引错乱
 * （2026-11-19 首版自测踩过）。
 *
 * 版本 V17（major 61）：与插件编译目标一致，243 JBR 21 可加载。
 */
internal object TinyClassFileWriter {

    /** 一个注解（type descriptor 形如 `Lcom/intellij/mcpserver/annotations/McpTool;`）。 */
    internal class AnnotationValue(
        val typeDescriptor: String,
        /** 元素名 → 值；值支持 String / Int / Boolean / List<*>（数组，元素递归同规则）。 */
        val elements: List<Pair<String, Any>> = emptyList(),
    )

    /** 一个委托方法：壳类声明它，方法体 `new target` 后转发同名同描述符调用。 */
    internal class DelegateMethod(
        val name: String,
        /** JVM 方法描述符（suspend 方法末位含 `Lkotlin/coroutines/Continuation;`）。 */
        val descriptor: String,
        val targetClassFqn: String,
        val annotations: List<AnnotationValue> = emptyList(),
    )

    /** 兼容旧签名：只生成「extends + 无参构造」的最小壳（无方法/无注解）。 */
    fun shellBytes(shellName: String, superName: String, ifaceName: String): ByteArray =
        shellBytes(shellName, superName, ifaceName, superCtorArg = null, delegates = emptyList(), classAnnotations = emptyList())

    /**
     * 生成壳类字节码。
     *
     * @param superCtorArg 非 null 时构造器为 `(String)` 形态（`ldc arg` + `super(arg)`）——用于
     *   `extends McpToolsetSupport`（该基类只有 `(String toolName)` 构造）；null 走无参 `super()`。
     * @param delegates 壳类自己声明并转发的方法（D239：让 declaredMethods 非 0）
     * @param classAnnotations class 级注解（D239：复制 `kotlin.Metadata`）
     */
    fun shellBytes(
        shellName: String,
        superName: String,
        ifaceName: String,
        superCtorArg: String?,
        delegates: List<DelegateMethod> = emptyList(),
        classAnnotations: List<AnnotationValue> = emptyList(),
    ): ByteArray {
        val cp = ConstantPool()

        // ---------- 第一阶段：把一切会新增常量的东西先算成字节 ----------
        val thisClass = cp.classRef(shellName.replace('.', '/'))
        val superClass = cp.classRef(superName.replace('.', '/'))
        val ifaceClass = cp.classRef(ifaceName.replace('.', '/'))
        val initName = cp.utf8("<init>")
        val ctorDesc = if (superCtorArg == null) "()V" else "(Ljava/lang/String;)V"
        val ctorDescIdx = cp.utf8(ctorDesc)
        val superInit = cp.methodRef(superClass, cp.nameAndType(initName, ctorDescIdx))

        // 构造器方法体：aload_0 [+ ldc_w arg] + invokespecial super.<init> + return
        val ctorCode = ByteArrayOutputStream(16).also { b ->
            val o = DataOutputStream(b)
            o.writeByte(0x2a) // aload_0
            if (superCtorArg != null) {
                o.writeByte(0x13) // ldc_w（u2 索引，避开 ldc 的 255 上限）
                o.writeShort(cp.string(superCtorArg))
            }
            o.writeByte(0xb7) // invokespecial
            o.writeShort(superInit)
            o.writeByte(0xb1) // return
        }.toByteArray()

        // 每个委托方法：方法体 + 注解 payload
        class PreparedMethod(
            val nameIdx: Int,
            val descIdx: Int,
            val code: ByteArray,
            val maxStack: Int,
            val maxLocals: Int,
            val annotations: ByteArray,
        )

        val prepared = delegates.map { d ->
            val nameIdx = cp.utf8(d.name)
            val descIdx = cp.utf8(d.descriptor)
            val targetClass = cp.classRef(d.targetClassFqn.replace('.', '/'))
            val targetInit = cp.methodRef(targetClass, cp.nameAndType(initName, cp.utf8("()V")))
            val targetCall = cp.methodRef(targetClass, cp.nameAndType(nameIdx, descIdx))

            val body = ByteArrayOutputStream(64)
            val o = DataOutputStream(body)
            o.writeByte(0xbb) // new
            o.writeShort(targetClass)
            o.writeByte(0x59) // dup
            o.writeByte(0xb7) // invokespecial <target>.<init>()V
            o.writeShort(targetInit)
            var slot = 1
            var stack = 3 // new 结果 + dup 副本 + 接收者
            jvmParameters(d.descriptor).forEach { p ->
                writeLoad(o, p, slot)
                val width = if (p == 'J' || p == 'D') 2 else 1
                stack += width
                slot += width
            }
            o.writeByte(0xb6) // invokevirtual <target>.<name><desc>
            o.writeShort(targetCall)
            when (jvmReturnKind(d.descriptor)) {
                'V' -> o.writeByte(0xb1)
                'J' -> o.writeByte(0xad)
                'D' -> o.writeByte(0xaf)
                'F' -> o.writeByte(0xae)
                'I' -> o.writeByte(0xac)
                else -> o.writeByte(0xb0)
            }
            PreparedMethod(
                nameIdx = nameIdx,
                descIdx = descIdx,
                code = body.toByteArray(),
                maxStack = stack.coerceAtLeast(1),
                maxLocals = slot,
                annotations = buildAnnotations(cp, d.annotations),
            )
        }

        val classAnnBytes = buildAnnotations(cp, classAnnotations)
        val codeAttr = cp.utf8("Code")
        val rvaAttr = if (classAnnBytes.isNotEmpty() || prepared.any { it.annotations.isNotEmpty() }) {
            cp.utf8("RuntimeVisibleAnnotations")
        } else {
            0
        }

        // ---------- 第二阶段：写盘（此后不得再新增常量池条目） ----------
        val bos = ByteArrayOutputStream(1024)
        val out = DataOutputStream(bos)
        out.writeInt(0xCAFEBABE.toInt())
        out.writeShort(0) // minor_version
        out.writeShort(V17_MAJOR) // major_version
        out.writeShort(cp.size() + 1) // constant_pool_count（1-based，index 0 保留）
        cp.writeTo(out)
        out.writeShort(ACC_PUBLIC or ACC_FINAL or ACC_SUPER)
        out.writeShort(thisClass)
        out.writeShort(superClass)
        out.writeShort(1) // interfaces_count
        out.writeShort(ifaceClass)
        out.writeShort(0) // fields_count
        out.writeShort(1 + prepared.size) // methods_count

        // 构造器（max_locals 必须覆盖 this + 形参：带参形态 2，无参 1）
        out.writeShort(ACC_PUBLIC)
        out.writeShort(initName)
        out.writeShort(ctorDescIdx)
        out.writeShort(1) // attributes_count: Code
        writeCodeAttribute(
            out,
            codeAttr,
            ctorCode,
            maxStack = if (superCtorArg == null) 1 else 2,
            maxLocals = if (superCtorArg == null) 1 else 2,
        )

        // 委托方法
        prepared.forEach { m ->
            out.writeShort(ACC_PUBLIC)
            out.writeShort(m.nameIdx)
            out.writeShort(m.descIdx)
            val attrs = 1 + (if (m.annotations.isNotEmpty()) 1 else 0)
            out.writeShort(attrs)
            writeCodeAttribute(out, codeAttr, m.code, m.maxStack, m.maxLocals)
            if (m.annotations.isNotEmpty()) {
                out.writeShort(rvaAttr)
                out.writeInt(m.annotations.size)
                out.write(m.annotations)
            }
        }

        // class 级注解
        if (classAnnBytes.isEmpty()) {
            out.writeShort(0)
        } else {
            out.writeShort(1)
            out.writeShort(rvaAttr)
            out.writeInt(classAnnBytes.size)
            out.write(classAnnBytes)
        }
        return bos.toByteArray()
    }

    private class PreparedMethod(
        val nameIdx: Int,
        val descIdx: Int,
        val code: ByteArray,
        val maxStack: Int,
        val maxLocals: Int,
        val annotations: ByteArray,
    )

    private fun writeCodeAttribute(
        out: DataOutputStream,
        codeAttrIdx: Int,
        code: ByteArray,
        maxStack: Int,
        maxLocals: Int,
    ) {
        out.writeShort(codeAttrIdx)
        out.writeInt(2 + 2 + 4 + code.size + 2 + 0 + 2 + 0)
        out.writeShort(maxStack)
        out.writeShort(maxLocals)
        out.writeInt(code.size)
        out.write(code)
        out.writeShort(0) // exception_table_length
        out.writeShort(0) // Code attributes_count
    }

    /** 构建 RuntimeVisibleAnnotations 的 **info 部分**（不含属性名与长度），空列表返回空数组。 */
    private fun buildAnnotations(cp: ConstantPool, annotations: List<AnnotationValue>): ByteArray {
        if (annotations.isEmpty()) return ByteArray(0)
        val payload = ByteArrayOutputStream(256)
        val p = DataOutputStream(payload)
        p.writeShort(annotations.size)
        annotations.forEach { ann ->
            p.writeShort(cp.utf8(ann.typeDescriptor))
            p.writeShort(ann.elements.size)
            ann.elements.forEach { (name, value) ->
                p.writeShort(cp.utf8(name))
                writeElementValue(p, cp, value)
            }
        }
        return payload.toByteArray()
    }

    private fun writeElementValue(out: DataOutputStream, cp: ConstantPool, value: Any) {
        when (value) {
            is String -> {
                out.writeByte('s'.code)
                out.writeShort(cp.utf8(value))
            }
            is Boolean -> {
                out.writeByte('Z'.code)
                out.writeShort(cp.integer(if (value) 1 else 0))
            }
            is Int -> {
                out.writeByte('I'.code)
                out.writeShort(cp.integer(value))
            }
            is List<*> -> {
                out.writeByte('['.code)
                out.writeShort(value.size)
                value.forEach { writeElementValue(out, cp, it ?: "") }
            }
            is Enum<*> -> {
                // D239：@McpToolHints 是枚举数组，必须按 'e'（enum_const_value）编码——
                // 降级成字符串会让注解解析在类加载时抛 AnnotationFormatError。
                out.writeByte('e'.code)
                out.writeShort(cp.utf8("L" + value.declaringJavaClass.name.replace('.', '/') + ";"))
                out.writeShort(cp.utf8(value.name))
            }
            is Class<*> -> {
                out.writeByte('c'.code)
                out.writeShort(cp.utf8("L" + value.name.replace('.', '/') + ";"))
            }
            else -> {
                // 未知类型降级为字符串：注解内容失真好过整体注册失败
                out.writeByte('s'.code)
                out.writeShort(cp.utf8(value.toString()))
            }
        }
    }

    private val Enum<*>.declaringJavaClass: Class<*>
        get() = javaClass.enclosingClass?.takeIf { it.isEnum } ?: javaClass.superclass

    /** 解析方法描述符参数类型（引用/数组统一归为 'L'，基本类型按原字符 'I'/'J'/'F'/'D'/'Z'…）。 */
    private fun jvmParameters(descriptor: String): List<Char> {
        val params = ArrayList<Char>()
        var i = 1 // 跳过 '('
        while (i < descriptor.length && descriptor[i] != ')') {
            when (descriptor[i]) {
                'L' -> {
                    while (i < descriptor.length && descriptor[i] != ';') i++
                    params.add('L')
                }
                '[' -> {
                    while (i < descriptor.length && descriptor[i] == '[') i++
                    if (i < descriptor.length && descriptor[i] == 'L') {
                        while (i < descriptor.length && descriptor[i] != ';') i++
                    }
                    params.add('L')
                }
                else -> params.add(descriptor[i])
            }
            i++
        }
        return params
    }

    private fun jvmReturnKind(descriptor: String): Char =
        descriptor.substringAfter(')', "").firstOrNull() ?: 'V'

    /**
     * 参数加载指令。
     *
     * **短形态基址各不相同**（JVM 规范 §6）：`iload_0`=0x1a、`lload_0`=0x1e、`fload_0`=0x22、
     * `dload_0`=0x26、`aload_0`=0x2a，而带索引形态是 `iload`=0x15、`lload`=0x16、`fload`=0x17、
     * `dload`=0x18、`aload`=0x19——**不能拿带索引基址 + slot 硬算**（2026-11-19 首版这么写，
     * 参数为 int 时会生成 lload 导致 VerifyError: Bad local variable type）。
     */
    private fun writeLoad(out: DataOutputStream, kind: Char, slot: Int) {
        val shortBase = when (kind) {
            'J' -> 0x1e // lload_0..3
            'F' -> 0x22 // fload_0..3
            'D' -> 0x26 // dload_0..3
            'I', 'Z', 'B', 'C', 'S' -> 0x1a // iload_0..3
            else -> 0x2a // aload_0..3
        }
        val indexed = when (kind) {
            'J' -> 0x16
            'F' -> 0x17
            'D' -> 0x18
            'I', 'Z', 'B', 'C', 'S' -> 0x15
            else -> 0x19
        }
        if (slot in 0..3) {
            out.writeByte(shortBase + slot)
        } else {
            out.writeByte(indexed)
            out.writeByte(slot)
        }
    }

    private const val V17_MAJOR = 61
    private const val ACC_PUBLIC = 0x0001
    private const val ACC_FINAL = 0x0010
    private const val ACC_SUPER = 0x0020

    /** 极简常量池：Utf8 / Class / String / Integer / NameAndType / Methodref。 */
    private class ConstantPool {
        private val items = ArrayList<(DataOutputStream) -> Unit>()
        private val utf8Index = HashMap<String, Int>()
        private val cache = HashMap<String, Int>()

        fun utf8(value: String): Int {
            utf8Index[value]?.let { return it }
            val index = items.size + 1
            items.add { out -> out.writeByte(1); out.writeUTF(value) }
            utf8Index[value] = index
            return index
        }

        fun classRef(name: String): Int = cache.getOrPut("C:$name") {
            val nameIdx = utf8(name) // 先解析（急切），writeTo 迭代期不得再改 items
            val index = items.size + 1
            items.add { out -> out.writeByte(7); out.writeShort(nameIdx) }
            index
        }

        fun string(value: String): Int = cache.getOrPut("S:$value") {
            val idx = utf8(value)
            val index = items.size + 1
            items.add { out -> out.writeByte(8); out.writeShort(idx) }
            index
        }

        fun integer(value: Int): Int = cache.getOrPut("I:$value") {
            val index = items.size + 1
            items.add { out -> out.writeByte(3); out.writeInt(value) }
            index
        }

        fun nameAndType(nameIdx: Int, descriptorIdx: Int): Int {
            val index = items.size + 1
            items.add { out -> out.writeByte(12); out.writeShort(nameIdx); out.writeShort(descriptorIdx) }
            return index
        }

        fun methodRef(classIdx: Int, nameAndTypeIdx: Int): Int {
            val index = items.size + 1
            items.add { out -> out.writeByte(10); out.writeShort(classIdx); out.writeShort(nameAndTypeIdx) }
            return index
        }

        fun size(): Int = items.size

        fun writeTo(out: DataOutputStream) {
            items.forEach { it(out) }
        }
    }
}
