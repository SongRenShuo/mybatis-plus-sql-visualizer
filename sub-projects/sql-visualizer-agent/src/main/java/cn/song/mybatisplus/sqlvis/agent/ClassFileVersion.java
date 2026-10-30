package cn.song.mybatisplus.sqlvis.agent;

/**
 * 类文件 major 版本闸（T-CaptureCatchAll 券 05）。
 *
 * <p><b>为什么必须有。</b>ASM 对「比自己新的类文件」直接抛
 * {@code IllegalArgumentException: Unsupported class file major version N}。现场实测：
 * {@code h2-2.3.232.jar} 带 {@code META-INF/versions/21/} 的多版本类（major 65），ASM 9.4
 * （最高支持 major 64 / Java 20）一读即抛。该异常虽被 transformer 的 {@code catch (Throwable)}
 * 吞掉（返回 null = 原字节装载），但代价是「整类零钩子」且每个这类类都白付一次异常构造；
 * 更糟的是它会污染 failed 计数，让「名单命中但织不上」的判读失真。
 *
 * <p><b>形态</b>：只读 {@code bytes[6..7]}（大端 major），不跑任何解析。超上限即由调用方原字节
 * 返回并计数 {@link CaptureBudget#skippedByVersionCount()}。这是**纯字节比较**，与 ASM 版本
 * 解耦——升级 ASM 时只需同步 {@link #MAX_SUPPORTED_MAJOR}。
 *
 * <p><b>零 ASM 依赖</b>（红线）：本类被 transformer 入口调用，而 transformer 在 premain 阶段
 * 实例化；若这里引用 {@code org.objectweb.asm.Opcodes}，ASM 不可见时（JRebel 等第三方 agent
 * 接管 premain 类加载的现场）transformer 连类初始化都过不去。上限值由
 * {@code ClassFileVersionTest} 与 {@code Opcodes.V25} 双向钉住（测试侧才引 ASM）。
 *
 * <p><b>上限取值</b>：69 = Java 25 = ASM 9.8 支持的最高版本（T-CaptureCatchAll 已把 agent 侧
 * ASM 9.4 → 9.8；9.4 的上限是 64/Java 20，读不了 major 65 的 JDK21 类）。升级 ASM 时本常量
 * 必须同步，否则新 JDK 的驱动类会被误挡在闸外（表现为 skippedByVersion 持续增长、零钩子）。
 */
final class ClassFileVersion {

    /** 类文件头最小长度（magic 4 + minor 2 + major 2）。 */
    private static final int HEADER_LEN = 8;

    /** ASM 9.8 支持的最高 major（Java 25）。与 {@code Opcodes.V25} 同值，由测试双向锁定。 */
    static final int MAX_SUPPORTED_MAJOR = 69;

    private ClassFileVersion() {
    }

    /** 读 major（大端无符号）；字节不足 8 或 magic 不符返回 -1（交给织入器按原逻辑处理）。 */
    static int majorOf(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER_LEN) {
            return -1;
        }
        // magic 0xCAFEBABE 校验：非 class 字节（测试夹具的垃圾字节）不进闸，由织入器自身报错计数
        if ((bytes[0] & 0xff) != 0xCA || (bytes[1] & 0xff) != 0xFE
                || (bytes[2] & 0xff) != 0xBA || (bytes[3] & 0xff) != 0xBE) {
            return -1;
        }
        return ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
    }

    /** 该字节是否在 ASM 支持范围内（读不出 major 时返回 true——不越权拦截）。 */
    static boolean supported(byte[] bytes) {
        final int major = majorOf(bytes);
        return major < 0 || major <= MAX_SUPPORTED_MAJOR;
    }
}
