// T-SyncK2JMaster180 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceFingerprint.kt 移植
package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * T-HdAbsorb D218（吸收自 Hibernate Debugger 的 {@code HibernateDataSourceFingerprint} 五元组思路）：
 * last-used 记忆的<b>指纹兜底层</b>。
 *
 * <p>背景：last-used 记忆有两级身份（UUID 键 {@code mpsql.visualizer.last.runner.id} + 显示名键
 * {@code mpsql.visualizer.last.runner}，见 {@link DataSourceSelector.LastUsedKeys}）——<b>两个键都以
 * 「名字/ID 不变」为前提</b>。数据源被改名（或 IDE 重装后 UUID 重排）时，T-SI-02 链
 * （UUID 精确命中 → 名字唯一命中 → 同名歧义弃）两级全部落空，静默复用失效、每次预览都要
 * 重新弹选择器。指纹兜底的记忆对象是<b>连接事实本身</b>（url+user+dbms 归一化哈希），改名/重建
 * 不影响——只要连接事实还在项目里，就能恢复静默复用。</p>
 *
 * <p>实现口径（吸收时按本仓可读面收敛，与对方五元组的差异如实登记）：</p>
 * <ul>
 *  <li>对方五元组 = jdbcUrl+driver+username+catalog+schema；本仓反射链（{@code DataSourceUrlProbe}
 *      同款 DatabaseTools 面）稳定可读的是 <b>url / user / dbms 显示名</b> 三元——catalog/schema
 *      无公开反射通道，刻意不猜（读不到就不进指纹，而不是拿空串凑满五元让两个不同库同指纹）。</li>
 *  <li><b>只做兜底、不做主键</b>：指纹命中排在 UUID/名字两级全落空之后；命中仍走 runner 侧
 *      方言闸与既有执行链，不新增任何「绕过闸」的路径。</li>
 *  <li><b>歧义即弃</b>（T-SI-02 同纪律）：多个数据源同指纹不猜，交选择器。</li>
 *  <li>写入走 EDT（T-LastUsedEdt 同款纪律——PropertiesComponent 是项目级持久化状态）。</li>
 * </ul>
 */
public final class DataSourceFingerprint {

    private DataSourceFingerprint() {
    }

    private static final Logger LOG = Logger.getInstance(DataSourceFingerprint.class);

    /** 指纹记忆键（经 {@link DataSourceSelector.LastUsedKeys} 拼模块后缀，与 last-used 双键同族）。 */
    public static final String FINGERPRINT_KEY = "mpsql.visualizer.last.runner.fingerprint";

    /**
     * 指纹计算（纯函数，headless 直测）：url 必须非空（无 URL 不构成指纹——非 SQL 源
     * （Redis/Mongo）不该进记忆）；user/dbms 空按 null 归一参与哈希。稳定哈希 SHA-256 hex。
     */
    @Nullable
    public static String compute(@Nullable String url, @Nullable String user, @Nullable String dbms) {
        String u = url != null ? url.trim() : "";
        if (u.isEmpty()) {
            return null;
        }
        String normalized = u.replaceAll("\\s+", "") + "\u0000"
            + (user != null && !user.trim().isEmpty() ? user.trim() : "-") + "\u0000"
            + (dbms != null && !dbms.trim().isEmpty() ? dbms.trim() : "-");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Throwable t) {
            // T-FP 纪律：fatal 透传（指纹算不出只是降级为「无指纹兜底」，VirtualMachineError 不吞）
            if (FatalErrors.isFatal(t)) throw (Error) t;
            LOG.debug("DataSourceFingerprint: digest unavailable", t);
            return null;
        }
    }

    /** 读记忆指纹（{@code moduleKey} 语义与 last-used 双键一致：模块级优先、项目级兜底）。 */
    @Nullable
    public static String remembered(@Nullable Project project, @Nullable String moduleKey) {
        try {
            if (project == null) {
                return null;
            }
            PropertiesComponent pc = PropertiesComponent.getInstance(project);
            return pc.getValue(DataSourceSelector.LastUsedKeys.key(FINGERPRINT_KEY, moduleKey));
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            return null;
        }
    }

    /**
     * 写/清记忆指纹（EDT 纪律同 {@link DataSourceSelector#rememberRunner}；null/blank = 清——
     * 「这次选的源算不出指纹」时留着旧指纹会指认另一个源，指纹说谎比没有指纹更糟）。
     */
    public static void remember(Project project, @Nullable String moduleKey, @Nullable String fingerprint) {
        com.intellij.openapi.application.Application application = ApplicationManager.getApplication();
        if (!application.isDispatchThread()) {
            application.invokeLater(
                () -> remember(project, moduleKey, fingerprint),
                ModalityState.any());
            return;
        }
        try {
            PropertiesComponent pc = PropertiesComponent.getInstance(project);
            String key = DataSourceSelector.LastUsedKeys.key(FINGERPRINT_KEY, moduleKey);
            if (fingerprint == null || fingerprint.isBlank()) {
                pc.unsetValue(key);
            } else {
                pc.setValue(key, fingerprint);
            }
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
        }
    }

    /**
     * 指纹兜底解析（纯函数核心，headless 直测）：在候选指纹清单里找与记忆指纹唯一相等的下标。
     * 唯一命中 → 下标；零命中或多条同指纹歧义 → null（歧义即弃，不猜）。
     */
    @Nullable
    public static Integer matchIn(List<String> candidateFingerprints, @Nullable String remembered) {
        if (remembered == null || remembered.isBlank()) {
            return null;
        }
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < candidateFingerprints.size(); i++) {
            String candidate = candidateFingerprints.get(i);
            if (candidate != null && candidate.equals(remembered)) {
                hits.add(i);
            }
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    /** O-96 同款测试缝：headless 注入「候选数据源 (名, 指纹) 清单」替代真反射链；产线恒 null。 */
    public static volatile Supplier<List<kotlin.Pair<String, String>>> enumerateForTest = null;

    /**
     * 兜底解析（反射链，失败静默 null）：按记忆指纹在项目数据源清单里找唯一命中，返回其显示名。
     * 上层（DataSourceSelectorResults 静默复用链）拿名字到 runner 集合里精确匹配后走既有执行链。
     */
    @Nullable
    public static String resolveRunnerName(Project project, @Nullable String moduleKey) {
        String rememberedFingerprint = remembered(project, moduleKey);
        if (rememberedFingerprint == null) {
            return null;
        }
        Supplier<List<kotlin.Pair<String, String>>> seam = enumerateForTest;
        List<kotlin.Pair<String, String>> candidates = seam != null ? seam.get() : null;
        if (candidates == null) {
            candidates = enumerateDataSourceFingerprints(project);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        List<String> fingerprints = new ArrayList<>(candidates.size());
        for (kotlin.Pair<String, String> candidate : candidates) {
            fingerprints.add(candidate.getSecond());
        }
        Integer at = matchIn(fingerprints, rememberedFingerprint);
        if (at == null) {
            return null;
        }
        String name = at >= 0 && at < candidates.size() ? candidates.get(at).getFirst() : null;
        LOG.info("[D218] fingerprint fallback matched: " + name + " (moduleKey=" + moduleKey + ")");
        return name;
    }

    /**
     * 枚举项目数据源的 (显示名, 指纹)（反射链与 {@code DataSourceUrlProbe} 同款类名面：
     * DataSourceManager.getManagers → getDataSources → getConnectionConfig.getUrl/getUser(或
     * getUserName) + DasDataSource.getDbms().getDisplayName）；读不到的维度按 null 归一。
     */
    private static List<kotlin.Pair<String, String>> enumerateDataSourceFingerprints(Project project) {
        try {
            if (project.isDisposed()) {
                return List.of();
            }
            Class<?> managerClass = Class.forName(DATA_SOURCE_MANAGER_CLASS);
            Object managersObj = managerClass.getMethod("getManagers", Project.class).invoke(null, project);
            if (!(managersObj instanceof List)) {
                return List.of();
            }
            List<kotlin.Pair<String, String>> out = new ArrayList<>();
            for (Object manager : (List<?>) managersObj) {
                if (manager == null) {
                    continue;
                }
                Object sourcesObj;
                try {
                    sourcesObj = manager.getClass().getMethod("getDataSources").invoke(manager);
                } catch (Throwable t) {
                    if (FatalErrors.isFatal(t)) throw (Error) t;
                    continue;
                }
                if (!(sourcesObj instanceof List)) {
                    continue;
                }
                for (Object ds : (List<?>) sourcesObj) {
                    if (ds == null) {
                        continue;
                    }
                    String name = null;
                    try {
                        name = (String) ds.getClass().getMethod("getName").invoke(ds);
                    } catch (Throwable t) {
                        if (FatalErrors.isFatal(t)) throw (Error) t;
                        continue;
                    }
                    if (name == null) {
                        continue;
                    }
                    out.add(new kotlin.Pair<>(name, fingerprintOfDataSource(ds)));
                }
            }
            return out;
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            return List.of();
        }
    }

    /** 单数据源 → 指纹（{@code rememberRunner} 写入与枚举共用同一口径）；读不到 url → null。 */
    @Nullable
    public static String fingerprintOfDataSource(Object ds) {
        Object config = null;
        try {
            config = ds.getClass().getMethod("getConnectionConfig").invoke(ds);
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            config = null;
        }
        String url = null;
        if (config != null) {
            try {
                url = (String) config.getClass().getMethod("getUrl").invoke(config);
            } catch (Throwable t) {
                if (FatalErrors.isFatal(t)) throw (Error) t;
                url = null;
            }
        }
        String user = null;
        if (config != null) {
            try {
                user = (String) config.getClass().getMethod("getUser").invoke(config);
            } catch (Throwable t) {
                // getUser 反射链失败（NoSuchMethod 等）才回落 getUserName；返回 null 不回落
                //（master runCatching.getOrElse 的 Success(null) 直传口径）。
                if (FatalErrors.isFatal(t)) throw (Error) t;
                try {
                    user = (String) config.getClass().getMethod("getUserName").invoke(config);
                } catch (Throwable t2) {
                    if (FatalErrors.isFatal(t2)) throw (Error) t2;
                    user = null;
                }
            }
        }
        String dbms = null;
        try {
            Class<?> dasType = Class.forName(DAS_DATA_SOURCE_CLASS);
            Object dbmsValue = dasType.getMethod("getDbms").invoke(ds);
            Class<?> dbmsClass = Class.forName("com.intellij.database.Dbms");
            String displayName = (String) dbmsClass.getMethod("getDisplayName").invoke(dbmsValue);
            if (displayName == null) {
                displayName = (String) dbmsClass.getMethod("getName").invoke(dbmsValue);
            }
            dbms = displayName;
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            dbms = null;
        }
        return compute(url, user, dbms);
    }

    private static final String DATA_SOURCE_MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager";
    private static final String DAS_DATA_SOURCE_CLASS = "com.intellij.database.model.DasDataSource";
}
