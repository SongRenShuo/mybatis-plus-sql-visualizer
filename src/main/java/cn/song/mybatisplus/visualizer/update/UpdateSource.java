package cn.song.mybatisplus.visualizer.update;

/**
 * 更新源配置（M28 → 内测通道改造）：清单 URL 与下载文件名约定集中于此。
 *
 * 内部更新通道已转为「Gitee 抢先体验通道」，检查 [MANIFEST_PATH] 指向 [update/update.json]
 *（含 `-beta.N` 内测版与正式版提前推送）；稳定版由 JetBrains Marketplace 接管，本通道不参与。
 * 草稿请写 [update/preview.update.json]，发布时复制到 [update/update.json]——预览清单常规通道不读，
 * 仅开发者模式「检测更新（开发版）」按 [previewManifestUrl] 读取（T-DevUpdateChannel）。
 *
 * 系统属性 `mpvs.update.manifest.url` 可覆盖清单 URL（沙盒/本地 HTTP server 验证用，
 * 与项目「发布前本地验证」惯例一致；内网私有通道亦复用此入口，见 README「内网与离线环境」）。
 *
 * 注意：该属性在<b>进程启动时读取一次</b>——manifestUrl 首次调用触发本类初始化，
 * 覆盖值随即固化；启动后修改 {@code -D} 不生效，须重启 IDE。
 */
public final class UpdateSource {

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(UpdateSource.class);

    public static final UpdateSource INSTANCE = new UpdateSource();
    private UpdateSource() {}

    /**
     * 换源生效时的诊断日志（T-Intranet-01）：仅在被覆盖时打印一行，默认路径静默。
     * 内网运维排查「配了内网源却仍在打 Gitee」时由此处定位；类初始化保证单次。
     */
    public static void logOverrideIfAny(String url) {
        if (!DEFAULT_MANIFEST_URL.equals(url) && !DEFAULT_PREVIEW_MANIFEST_URL.equals(url)) {
            LOG.info("update manifest url overridden by system property: " + url);
        }
    }

    /** 插件 id（与 plugin.xml `<id>` 一致；安装/卸载判定与下载文件命名共用）。 */
    public static final String PLUGIN_ID = "cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer";

    /** 清单在仓库内的固定路径（raw URL 必须稳定，发布流程约定不可改）。 */
    public static final String MANIFEST_PATH = "update/update.json";

    /** 默认清单地址（Gitee 公开仓库 raw，无需鉴权，国内直连）。 */
    public static final String DEFAULT_MANIFEST_URL =
        "https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/raw/master/" + MANIFEST_PATH;

    /** 清单 URL 覆盖系统属性名（沙盒验证/换源用）。 */
    public static final String MANIFEST_URL_OVERRIDE = "mpvs.update.manifest.url";

    /**
     * 预览（草稿）清单路径（T-DevUpdateChannel）：开发者模式「检测更新（开发版）」读它。
     * 与 [MANIFEST_PATH] 同目录同分支，发布流程仍是「草稿写预览、发布复制到正式清单」——
     * 本常量只新增一个<b>读取入口</b>，不改发布流程（见 {@code docs/开发计划/20261106-1955-…}）。
     */
    public static final String PREVIEW_MANIFEST_PATH = "update/preview.update.json";

    /** 默认预览清单地址（同仓同分支 raw，只需多一条路由即可给本地 server 验证）。 */
    public static final String DEFAULT_PREVIEW_MANIFEST_URL =
        "https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/raw/master/" + PREVIEW_MANIFEST_PATH;

    /** 预览清单 URL 覆盖系统属性名（沙盒/本地 server 验证用；空白视为未设置）。 */
    public static final String PREVIEW_MANIFEST_URL_OVERRIDE = "mpvs.update.previewManifestUrl";

    /** Gitee 发布页（T-M28.4 变更日志链接目标——设置页/通知可点击跳转）。 */
    public static final String GITEE_RELEASES_URL = "https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/releases";

    /**
     * JetBrains Marketplace 插件页（R62 兜底链接之一——更新通道 404 时的稳定版下载入口）。
     * 值与仓内发行页 {@code src/main/resources/html/marketplace-custom-page.html} 的 Marketplace 链接
     * 同源（此前无 Kotlin 常量，V114② 半数证伪——本常量为首个可复用落点）。
     */
    public static final String MARKETPLACE_URL = "https://plugins.jetbrains.com/plugin/33633-sql-visualizer";

    /** 插件安装目录名（2026-08-20 修复）：zip 顶层目录名（= 插件安装时解压出的目录名）。 */
    public static final String INSTALL_DIR_NAME = "mybatis-plus-sql-visualizer";

    /** 实际清单 URL（系统属性覆盖优先；属性空白视为未设置）。 */
    public String manifestUrl() {
        String prop = System.getProperty(MANIFEST_URL_OVERRIDE);
        if (prop != null && !prop.isBlank()) return prop;
        return DEFAULT_MANIFEST_URL;
    }

    /**
     * 实际预览清单 URL（T-DevUpdateChannel）：专用覆盖属性优先，否则由 [manifestUrl] 同源改路径
     *（换源/本地 server 只需多一条 {@code /update/preview.update.json} 路由，不必再配第二个属性）。
     *
     * <p>换源值里不含 [MANIFEST_PATH] 时（自定义文件名等）同源改名无从谈起，回落默认预览地址，
     * <b>不静默复用正式 URL</b>——否则「开发版通道」会悄悄读正式清单，把彩蛋变成错误读数。</p>
     */
    public String previewManifestUrl() {
        String previewProp = System.getProperty(PREVIEW_MANIFEST_URL_OVERRIDE);
        if (previewProp != null && !previewProp.isBlank()) return previewProp;
        String stable = manifestUrl();
        return stable.contains(MANIFEST_PATH)
                ? stable.replace(MANIFEST_PATH, PREVIEW_MANIFEST_PATH)
                : DEFAULT_PREVIEW_MANIFEST_URL;
    }

    /** Gitee Release 附件下载文件名（与 buildPlugin 产物命名一致）。 */
    public String downloadFileName(String version) {
        return "mybatis-plus-sql-visualizer-" + version + ".zip";
    }
}
