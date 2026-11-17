package cn.song.mybatisplus.visualizer.update;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 归属 + 布局校验（T-LocalUpdateDir 计划 §3.3.1 G2）：<b>这个通道只能装本插件</b>。
 *
 * <p>两道判据：G1 描述符归属——复用 {@link DynamicInstallSupport#validateArtifact}（zip 可打开、
 * 能定位 plugin.xml——直属 META-INF/plugin.xml 或 lib 目录下插件 jar 内嵌两形态、id 非空可读），
 * id 必须逐字等于 {@link UpdateSource#PLUGIN_ID}；G2 布局归属——zip 顶层<b>只能有一个目录</b>且名 ==
 * {@link UpdateSource#INSTALL_DIR_NAME}（目录内部不限）；唯一例外是完全平坦的 META-INF/plugin.xml
 * 布局。任何第二个顶层目录、任何插件目录以外的顶层条目（含文件）即 TAINTED。</p>
 *
 * <p>真实 buildPlugin 产物布局实证（2026-11-12）：顶层仅 {@code mybatis-plus-sql-visualizer/} 一个目录
 * （LICENSE / THIRD-PARTY-NOTICES.md 在插件目录内，非 zip 根）——严格判定不误杀真包（计划 P-9）。</p>
 *
 * <p>{@code since-build} 在同一次描述符读取里顺带取出（D-5 兼容性判据），不二次开包。
 * 只读中央目录与描述符，不解压、不触平台安装；纯 JVM（单测直接覆盖）。调用方放 BGT。
 * master 侧为顶层声明集合，Java 侧按 k2j 惯例落 {@code PluginPackageOwnershipKt} 门面类。</p>
 */
public final class PluginPackageOwnershipKt {

    private PluginPackageOwnershipKt() {
    }

    /** 包归属校验结果（T-LocalUpdateDir 计划 §3.3.1；Kotlin sealed 的 Java 等价形态）。 */
    public static final class Ownership {

        /** 形态：Ours / NotOurs / Tainted。 */
        @NotNull
        private final String kind;
        /** Ours：包内描述符 version。 */
        @Nullable
        private final String version;
        /** Ours：包内 {@code <idea-version since-build>} 的主版本段（如 243）；未声明或不可解析为 null。 */
        @Nullable
        private final Integer sinceBuildBaseline;
        /** Ours：唯一顶层目录名（平坦布局为空串）。 */
        @Nullable
        private final String topLevelDir;
        /** NotOurs / Tainted：原因。 */
        @Nullable
        private final String reason;

        private Ownership(@NotNull String kind, @Nullable String version, @Nullable Integer sinceBuildBaseline,
                          @Nullable String topLevelDir, @Nullable String reason) {
            this.kind = kind;
            this.version = version;
            this.sinceBuildBaseline = sinceBuildBaseline;
            this.topLevelDir = topLevelDir;
            this.reason = reason;
        }

        public static Ownership ours(@NotNull String version, @Nullable Integer sinceBuildBaseline,
                                     @NotNull String topLevelDir) {
            return new Ownership("Ours", version, sinceBuildBaseline, topLevelDir, null);
        }

        public static Ownership notOurs(@NotNull String reason) {
            return new Ownership("NotOurs", null, null, null, reason);
        }

        public static Ownership tainted(@NotNull String reason) {
            return new Ownership("Tainted", null, null, null, reason);
        }

        @NotNull
        public String getKind() {
            return kind;
        }

        public boolean isOurs() {
            return "Ours".equals(kind);
        }

        public boolean isTainted() {
            return "Tainted".equals(kind);
        }

        @Nullable
        public String getVersion() {
            return version;
        }

        @Nullable
        public Integer getSinceBuildBaseline() {
            return sinceBuildBaseline;
        }

        @Nullable
        public String getTopLevelDir() {
            return topLevelDir;
        }

        @Nullable
        public String getReason() {
            return reason;
        }
    }

    /** {@code <idea-version since-build="…">} 属性提取（容忍属性序变化）。 */
    private static final Pattern SINCE_BUILD_ATTR =
            Pattern.compile("<idea-version[^>]*\\ssince-build\\s*=\\s*\"([^\"]+)");

    @NotNull
    public static Ownership verify(@NotNull Path zip) {
        try {
            return doVerify(zip);
        } catch (Throwable t) {
            return Ownership.notOurs("安装包不可读: "
                    + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
        }
    }

    @NotNull
    private static Ownership doVerify(@NotNull Path zip) throws Exception {
        // G1 描述符归属（复用既有校验：定位描述符 + id/version 非空）
        DynamicInstallSupport.ArtifactCheck artifact = DynamicInstallSupport.validateArtifact(zip);
        String version;
        if (artifact instanceof DynamicInstallSupport.ArtifactCheck.Invalid invalid) {
            return Ownership.notOurs("安装包缺描述符或损坏: " + invalid.reason());
        } else if (artifact instanceof DynamicInstallSupport.ArtifactCheck.Ok ok) {
            if (!UpdateSource.PLUGIN_ID.equals(ok.id())) {
                return Ownership.notOurs("这是别的插件（id=" + ok.id() + "）");
            }
            version = ok.version();
        } else {
            return Ownership.notOurs("安装包缺描述符或损坏");
        }

        // G2 布局归属：顶层唯一目录 == INSTALL_DIR_NAME。
        // 注意 zip 的 META-INF/plugin.xml 直属条目会登记出顶层段 "META-INF"——平坦布局与
        // 标准布局的判定都要先把这一段摘出去（flat = 除 META-INF 外没有任何顶层目录）。
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Map<String, Integer> topDirs = new LinkedHashMap<>();
            List<String> topFiles = new ArrayList<>();
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (name.isEmpty()) {
                    continue;
                }
                int slash = name.indexOf('/');
                if (slash < 0) {
                    if (!entry.isDirectory()) {
                        topFiles.add(name);
                    }
                    continue;
                }
                String top = name.substring(0, slash);
                topDirs.merge(top, 1, Integer::sum);
            }
            List<String> nonMetaTopDirs = new ArrayList<>();
            for (String top : topDirs.keySet()) {
                if (!"META-INF".equals(top)) {
                    nonMetaTopDirs.add(top);
                }
            }

            if (nonMetaTopDirs.isEmpty()) {
                // 平坦布局（直属 META-INF/plugin.xml + 根级直属文件）：G1 已确认描述符在位，放行
                return Ownership.ours(version, readSinceBuildFromDescriptor(zf, null), "");
            }
            if (nonMetaTopDirs.size() == 1) {
                String top = nonMetaTopDirs.get(0);
                // 精确匹配（非忽略大小写）：zip 条目名大小写敏感，大小写变体在 Linux/macOS 上
                // 会解出第二个插件目录（同 id 重复安装，平台状态损坏），在 Windows 上虽并入
                // 同名目录但形态已不可信——一律 Tainted（计划 §3.3.1）
                if (!top.equals(UpdateSource.INSTALL_DIR_NAME)) {
                    return Ownership.tainted(
                            "顶层目录名是 \"" + top + "\"，不是本插件目录 \"" + UpdateSource.INSTALL_DIR_NAME + "\"");
                }
                if (!topFiles.isEmpty()) {
                    return Ownership.tainted("顶层出现插件目录以外的条目: " + topFiles);
                }
                if (topDirs.containsKey("META-INF")) {
                    return Ownership.tainted("顶层同时出现插件目录与 META-INF 目录（非标准布局）");
                }
                return Ownership.ours(version, readSinceBuildFromDescriptor(zf, top), top);
            }
            return Ownership.tainted("包内含多个顶层目录（夹带其他插件？）: " + nonMetaTopDirs);
        }
    }

    /**
     * 从 zip 直属 META-INF/plugin.xml（或插件目录下同名条目、或 lib 目录下插件 jar 内嵌描述符）
     * 读 {@code <idea-version since-build>}。与 {@link DynamicInstallSupport#validateArtifact} 同查找序，
     * 读取复用其有界实现；找不到描述符（理论不可达——G1 已过）返回 null。
     */
    @Nullable
    private static Integer readSinceBuildFromDescriptor(@NotNull ZipFile zf, @Nullable String topLevelDir) {
        try {
            String xml = firstDescriptorXml(zf, topLevelDir);
            return xml != null ? parseSinceBuildBaseline(xml) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String firstDescriptorXml(@NotNull ZipFile zf, @Nullable String topLevelDir) throws Exception {
        List<String> directNames = new ArrayList<>();
        if (topLevelDir == null) {
            directNames.add("META-INF/plugin.xml");
        } else {
            directNames.add(topLevelDir + "/META-INF/plugin.xml");
            directNames.add("META-INF/plugin.xml");
        }
        for (String name : directNames) {
            ZipEntry entry = zf.getEntry(name);
            if (entry != null) {
                return DynamicInstallSupport.readDescriptorBounded(zf.getInputStream(entry));
            }
        }
        // 内嵌形态：lib 目录下插件 jar 里的 META-INF/plugin.xml（IPGP buildPlugin 布局，与 validateArtifact 同序）
        var entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory() || !entry.getName().endsWith(".jar")
                    || !entry.getName().replace('\\', '/').contains("/lib/")) {
                continue;
            }
            try (ZipInputStream inner = new ZipInputStream(new java.io.BufferedInputStream(zf.getInputStream(entry)))) {
                ZipEntry next = inner.getNextEntry();
                int scanned = 0;
                while (next != null && scanned < DynamicInstallSupport.MAX_INNER_ENTRIES) {
                    if ("META-INF/plugin.xml".equalsIgnoreCase(next.getName())) {
                        return DynamicInstallSupport.readDescriptorBounded(inner);
                    }
                    next = inner.getNextEntry();
                    scanned++;
                }
            }
        }
        return null;
    }

    /**
     * {@code since-build} 属性 → 主版本段整数（{@code 243.*} / {@code IU-243.x} 等形态取前导数字）；
     * 未声明或不可解析 → null（视为「未声明」，不拦）。纯函数。
     */
    @Nullable
    public static Integer parseSinceBuildBaseline(@NotNull String descriptorXml) {
        Matcher matcher = SINCE_BUILD_ATTR.matcher(descriptorXml);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).trim();
        Matcher digits = Pattern.compile("^\\d+").matcher(value);
        if (!digits.find()) {
            return null;
        }
        try {
            return Integer.valueOf(digits.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * D-5 兼容性判定（纯函数）：包要求的主版本段高于当前 IDE baseline ⇒ 不兼容。
     * 任一侧未知（包未声明 / 当前 baseline 读不到）⇒ 不拦（与远端通道 warnsIncompatible
     * 的「仅提示」口径对齐——这里拦的理由是共享盘可能出现「给别人 IDE 打的包」，未知侧不构成该风险）。
     */
    public static boolean isSinceBuildIncompatible(@Nullable Integer sinceBuildBaseline,
                                                   @Nullable Integer currentBaseline) {
        return sinceBuildBaseline != null && currentBaseline != null && sinceBuildBaseline > currentBaseline;
    }
}
