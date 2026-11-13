// T-LocalUpdateDir 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/update/PluginPackageOwnership.kt object 移植（183c98b3d）
package cn.song.mybatisplus.visualizer.update;

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
 * 包归属 + 布局校验（T-LocalUpdateDir 计划 §3.3.1）：master 侧为 object {@code PluginPackageOwnership}
 * + 顶层 sealed interface {@code Ownership}，按 k2j 惯例落静态形态（{@link DynamicInstallSupport} 同款）。
 *
 * <p><b>这个通道只能装本插件</b>。两道判据：</p>
 * <ul>
 * <li>G1 描述符归属：复用 {@link DynamicInstallSupport#validateArtifact}（zip 可打开、能定位
 *     plugin.xml——直属 {@code META-INF/plugin.xml} 或 lib 目录下插件 jar 内嵌两形态、{@code <id>} 非空可读），
 *     {@code id} 必须逐字等于 {@link UpdateSource#PLUGIN_ID}；</li>
 * <li>G2 布局归属（本类新增）：zip 顶层<b>只能有一个目录</b>且名 == {@link UpdateSource#INSTALL_DIR_NAME}
 *     （目录内部不限）；唯一例外是完全平坦的 {@code META-INF/plugin.xml} 布局。任何第二个顶层目录、
 *     任何插件目录以外的顶层条目（含文件）即 {@link Ownership.Tainted}。</li>
 * </ul>
 *
 * <p>真实 buildPlugin 产物布局实证（2026-11-12，
 * {@code build/distributions/mybatis-plus-sql-visualizer-2026.11.12.zip}）：
 * 顶层仅 {@code mybatis-plus-sql-visualizer/} 一个目录（LICENSE / THIRD-PARTY-NOTICES.md 在插件目录内，
 * 非 zip 根）——严格判定不误杀真包（计划 P-9）。</p>
 *
 * <p>{@code since-build} 在同一次描述符读取里顺带取出（D-5 兼容性判据），不二次开包，也不改
 * {@link DynamicInstallSupport.ArtifactCheck} 现有签名。</p>
 *
 * <p>只读中央目录与描述符，不解压、不触平台安装；纯 JVM（单测直接覆盖）。调用方放 BGT。</p>
 */
final class PluginPackageOwnership {

    static final PluginPackageOwnership INSTANCE = new PluginPackageOwnership();
    private PluginPackageOwnership() {}

    /** {@code <idea-version since-build="…">} 属性提取（容忍属性序变化）。 */
    private static final Pattern SINCE_BUILD_ATTR = Pattern.compile("<idea-version[^>]*\\ssince-build\\s*=\\s*\"([^\"]+)\"");

    /**
     * 包归属校验结果（T-LocalUpdateDir 计划 §3.3.1）。master 侧 internal sealed interface。
     *
     * <ul>
     * <li>{@link Ours}：id 是本插件、顶层布局唯一目录，{@code version()}/{@code sinceBuildBaseline()} 取自包内描述符；</li>
     * <li>{@link NotOurs}：不是本插件（id 不符 / 描述符缺失或损坏）；</li>
     * <li>{@link Tainted}：<b>id 是本插件但包不可信</b>——夹带其他顶层目录 / 顶层目录名不是插件目录名
     *     （重启脚本路径把 zip 整包解压到 IDE 插件根目录，夹带目录会被一起装成别的插件；目录名
     *     大小写变体在 Windows 上会互相覆盖）。</li>
     * </ul>
     */
    sealed interface Ownership {

        record Ours(String version,
                    /** 包内 {@code <idea-version since-build>} 的主版本段（如 243）；未声明或不可解析为 null。 */
                    Integer sinceBuildBaseline,
                    String topLevelDir) implements Ownership {}

        record NotOurs(String reason) implements Ownership {}

        record Tainted(String reason) implements Ownership {}
    }

    static Ownership verify(Path zip) {
        try {
            return doVerify(zip);
        } catch (Throwable t) { // master runCatching.getOrElse 语义
            String reason = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            return new Ownership.NotOurs("安装包不可读: " + reason);
        }
    }

    private static Ownership doVerify(Path zip) throws java.io.IOException {
        // G1 描述符归属（复用既有校验：定位描述符 + id/version 非空）
        DynamicInstallSupport.ArtifactCheck artifact = DynamicInstallSupport.validateArtifact(zip);
        String version;
        if (artifact instanceof DynamicInstallSupport.ArtifactCheck.Invalid invalid) {
            return new Ownership.NotOurs("安装包缺描述符或损坏: " + invalid.reason());
        } else {
            DynamicInstallSupport.ArtifactCheck.Ok ok = (DynamicInstallSupport.ArtifactCheck.Ok) artifact;
            if (!UpdateSource.PLUGIN_ID.equals(ok.id())) {
                return new Ownership.NotOurs("这是别的插件（id=" + ok.id() + "）");
            }
            version = ok.version();
        }

        // G2 布局归属：顶层唯一目录 == INSTALL_DIR_NAME。
        // 注意 zip 的 `META-INF/plugin.xml` 直属条目会登记出顶层段 "META-INF"——平坦布局与
        // 标准布局的判定都要先把这一段摘出去（flat = 除 META-INF 外没有任何顶层目录）。
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Map<String, Integer> topDirs = new LinkedHashMap<>();
            List<String> topFiles = new ArrayList<>();
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (name.isEmpty()) continue;
                int slash = name.indexOf('/');
                if (slash < 0) {
                    if (!entry.isDirectory()) topFiles.add(name);
                    continue;
                }
                String top = name.substring(0, slash);
                topDirs.merge(top, 1, Integer::sum);
            }
            List<String> nonMetaTopDirs = new ArrayList<>();
            for (String key : topDirs.keySet()) {
                if (!key.equals("META-INF")) nonMetaTopDirs.add(key);
            }

            if (nonMetaTopDirs.isEmpty()) {
                // 平坦布局（直属 META-INF/plugin.xml + 根级直属文件）：G1 已确认描述符在位，放行
                return new Ownership.Ours(version, readSinceBuildFromDescriptor(zf, null), "");
            }
            if (nonMetaTopDirs.size() == 1) {
                String top = nonMetaTopDirs.get(0);
                // 精确匹配（非忽略大小写）：zip 条目名大小写敏感，大小写变体在 Linux/macOS 上
                // 会解出第二个插件目录（同 id 重复安装，平台状态损坏），在 Windows 上虽并入
                // 同名目录但形态已不可信——一律 Tainted（计划 §3.3.1）
                if (!top.equals(UpdateSource.INSTALL_DIR_NAME)) {
                    return new Ownership.Tainted(
                        "顶层目录名是 \"" + top + "\"，不是本插件目录 \"" + UpdateSource.INSTALL_DIR_NAME + "\"");
                }
                if (!topFiles.isEmpty()) {
                    return new Ownership.Tainted("顶层出现插件目录以外的条目: " + topFiles);
                }
                if (topDirs.containsKey("META-INF")) {
                    return new Ownership.Tainted("顶层同时出现插件目录与 META-INF 目录（非标准布局）");
                }
                return new Ownership.Ours(version, readSinceBuildFromDescriptor(zf, top), top);
            }
            return new Ownership.Tainted("包内含多个顶层目录（夹带其他插件？）: " + nonMetaTopDirs);
        }
    }

    /**
     * 从 zip 直属 {@code META-INF/plugin.xml}（或插件目录下同名条目、或 lib 目录下插件 jar 内嵌描述符）
     * 读 {@code <idea-version since-build>}。与 {@link DynamicInstallSupport#validateArtifact} 同查找序，
     * 读取复用其有界实现；找不到描述符（理论不可达——G1 已过）返回 null。
     */
    private static Integer readSinceBuildFromDescriptor(ZipFile zf, String topLevelDir) {
        try { // master runCatching.getOrNull 语义
            String xml = firstDescriptorXml(zf, topLevelDir);
            if (xml == null) return null;
            return parseSinceBuildBaseline(xml);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String firstDescriptorXml(ZipFile zf, String topLevelDir) throws java.io.IOException {
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
            if (entry.isDirectory()
                || !entry.getName().endsWith(".jar")
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
     * 未声明或不可解析 → null（视为「未声明」，不拦）。纯函数。master 侧 internal。
     */
    static Integer parseSinceBuildBaseline(String descriptorXml) {
        Matcher m = SINCE_BUILD_ATTR.matcher(descriptorXml);
        if (!m.find()) return null;
        String attr = m.group(1);
        Matcher digits = Pattern.compile("^\\d+").matcher(attr.trim());
        if (!digits.find()) return null;
        try {
            return Integer.valueOf(digits.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * D-5 兼容性判定（纯函数）：包要求的主版本段高于当前 IDE baseline ⇒ 不兼容。
     * 任一侧未知（包未声明 / 当前 baseline 读不到）⇒ 不拦（与远端通道 {@code warnsIncompatible}
     * 的「仅提示」口径对齐——这里拦的理由是共享盘可能出现「给别人 IDE 打的包」，未知侧不构成该风险）。
     * master 侧 internal。
     */
    static boolean isSinceBuildIncompatible(Integer sinceBuildBaseline, Integer currentBaseline) {
        return sinceBuildBaseline != null && currentBaseline != null && sinceBuildBaseline > currentBaseline;
    }
}
