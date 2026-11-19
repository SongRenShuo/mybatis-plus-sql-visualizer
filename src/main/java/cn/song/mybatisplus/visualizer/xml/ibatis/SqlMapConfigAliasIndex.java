package cn.song.mybatisplus.visualizer.xml.ibatis;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.indexing.ScalarIndexExtension;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T-PerfSweep31 券01（2026-11-17）：iBatis {@code <sqlMapConfig>} 的 {@code <typeAlias>} 自定义标量索引。
 *
 * <h2>为什么（对位 MyBatis 侧 MapperNamespaceIndex / iBatis 侧 SqlMapNamespaceIndex）</h2>
 *
 * <p>旧链 {@code ui/IbatisTypeAliasResolver}（v1）的做法是：首次调用对<b>语句所在模块的内容根</b>做
 * {@code VfsUtilCore.iterateChildrenRecursively} 全走查，逐个 XML {@code PsiManager.findFile} 建 PSI 后筛
 * 根标签 {@code sqlMapConfig}、提取 {@code <typeAlias>}——跑在预览点击链的 {@code ReadAction.compute}
 * （池线程持读锁）内：大模块首次点击付出「整模块 XML 全部进 PSI」的代价，且持读锁期间平台写动作排队
 * （性能分析第八期 §4.3，P2 主菜）。另有会话级缓存——config 改动要重启会话才生效。
 *
 * <p>本索引把「哪些文件是 sqlMapConfig / 各自声明了哪些别名→FQN」在<b>索引期</b>摊平：运行时一次
 * {@code getContainingFiles} 出候选文件，失效全交平台增量（{@code dependsOnFileContent=true}）——没有全走查、
 * 没有会话级陈旧缓存（config 保存后索引自动更新，顺带修掉 v1 已知限制）。命中不到时调用方
 * （{@code IbatisTypeAliasResolver}）回落短名索引，行为不变。
 *
 * <h2>索引键设计</h2>
 * <table border="1">
 * <tr><th>键</th><th>含义</th><th>消费方</th></tr>
 * <tr><td>{@code cfg}</td><td>「本文件根标签是 {@code <sqlMapConfig>}」的常量标记</td><td>全量清单（诊断/测试）</td></tr>
 * <tr><td>{@code ta:&lt;alias&gt;}</td><td>该 config 声明的别名</td><td>{@code IbatisTypeAliasResolver.fqnOf} 主查询</td></tr>
 * </table>
 *
 * <p>前缀 {@code ta:} 与常量键 {@code cfg} 天然不撞（别名可含任意字符，但 {@code ta:} 前缀限定了键空间）。
 *
 * <h2>键的提取（索引期，纯文本，不建 PSI）</h2>
 *
 * <p>根锚判定照 {@link SqlMapNamespaceIndex} 同款互斥口径——sqlMapConfig 里常有
 * {@code <sqlMap resource=…/>} 子元素，必须先认出 config；{@code <sqlMap } 出现在 {@code <sqlMapConfig} 之前
 * 即映射文件，不产键。{@code <typeAlias>} 走全文扫描（配置文件通常 KB 级）。
 *
 * <p>提取内核全部是纯函数（{@link #aliasEntriesFromText} / {@link #keysForText}），可纯单测。已知边界同族：
 * 根锚/别名写在注释或 CDATA 里会多收，由 PSI 侧 {@code rootTag} + {@code alias} 精确匹配闸门否掉（只少收
 * 不误收的正确性口径，见下）。
 *
 * <h2>正确性闸门在 PSI 侧</h2>
 *
 * <p>索引只回答「去哪几个文件里找」。{@code IbatisTypeAliasResolver} 拿到候选文件后仍走
 * {@code rootTag.name == "sqlMapConfig"} + {@code <typeAlias alias>} 精确匹配——索引键偶有偏差只影响
 * 查得到查不到，不会给出错的 FQN。
 *
 * <h2>平台注记</h2>
 *
 * <p>{@code fileBasedIndex} EP {@code dynamic="true"}（product.jar 实证，两前置索引同款）非动态卸载屏障，
 * 热更新特性不受影响。键域有界（别名数 × 项目内 config 文件数）。
 */
public class SqlMapConfigAliasIndex extends ScalarIndexExtension<String> {

    /** 索引 ID：带插件前缀防跨插件撞名（对位既有两索引）。 */
    public static final ID<String, Void> NAME = ID.create("cn.song.mybatisplus.sqlMapConfigAlias");

    /** 「本文件是 sqlMapConfig」的常量键。 */
    public static final String CONFIG_KEY = "cfg";

    private static final String ALIAS_PREFIX = "ta:";

    /** config 根锚：{@code <sqlMapConfig} 后必须跟空白 / {@code >} / {@code /}。 */
    private static final Pattern SQL_MAP_CONFIG_TAG_START = Pattern.compile("<sqlMapConfig[\\s>/]");

    /** 映射文件根锚：{@code <sqlMap } 出现在 config 锚之前 ⇒ 本文件是 sqlMap，不是 config。 */
    private static final Pattern SQL_MAP_TAG_START = Pattern.compile("<sqlMap[\\s>/]");

    private static final Pattern TYPE_ALIAS_TAG_START = Pattern.compile("<typeAlias[\\s>/]");

    private static final Pattern ALIAS_ATTR = Pattern.compile("\\balias\\s*=\\s*([\"'])([^\"']+)\\1");
    private static final Pattern TYPE_ATTR = Pattern.compile("\\btype\\s*=\\s*([\"'])([^\"']+)\\1");

    /** 别名 → 声明它的 sqlMapConfig 文件（索引查询；dumb 期可能抛 IndexNotReadyException，调用方兜底）。 */
    public static List<VirtualFile> getConfigFilesByAlias(Project project, String alias) {
        String a = alias.trim();
        if (a.isEmpty()) {
            return List.of();
        }
        return query(project, ALIAS_PREFIX + a);
    }

    /** 项目内全部 sqlMapConfig 文件（全量清单/诊断用）。 */
    public static List<VirtualFile> getAllConfigFiles(Project project) {
        return query(project, CONFIG_KEY);
    }

    private static List<VirtualFile> query(Project project, String key) {
        return new ArrayList<>(FileBasedIndex.getInstance().getContainingFiles(NAME, key, GlobalSearchScope.projectScope(project)));
    }

    /**
     * 纯文本判定内核（可纯单测）：本文件产出的全部索引键；根不是 {@code <sqlMapConfig>} 时返回空集。
     */
    static Set<String> keysForText(String text) {
        Map<String, String> entries = aliasEntriesFromText(text);
        if (entries.isEmpty() && !isSqlMapConfigFile(text)) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>(entries.size() + 1);
        keys.add(CONFIG_KEY);
        for (String alias : entries.keySet()) {
            keys.add(ALIAS_PREFIX + alias);
        }
        return keys;
    }

    /** 根标签是否 {@code <sqlMapConfig>}（纯文本判定；{@code <sqlMap } 在前 ⇒ 映射文件，非 config）。 */
    static boolean isSqlMapConfigFile(String text) {
        Matcher configStart = SQL_MAP_CONFIG_TAG_START.matcher(text);
        if (!configStart.find()) {
            return false;
        }
        int configPos = configStart.start();
        Matcher sqlMapStart = SQL_MAP_TAG_START.matcher(text);
        if (sqlMapStart.find() && sqlMapStart.start() < configPos) {
            return false;
        }
        return true;
    }

    /**
     * 全文里的 {@code <typeAlias>} 别名 → type 表（去重、保序；同别名重复声明取首个）。
     *
     * <p>只看开标签内的属性（{@code >} 前），因此不会把 SQL 体里的 {@code alias} 字样误收；跨行属性、
     * 属性序任意、单双引号都认。alias/type 缺一即弃（照 {@code IbatisTypeAliasResolver} 旧口径）。
     */
    static Map<String, String> aliasEntriesFromText(String text) {
        if (!isSqlMapConfigFile(text)) {
            return Map.of();
        }
        if (!text.contains("alias")) {
            return Map.of();
        }
        Map<String, String> entries = new LinkedHashMap<>();
        Matcher tagStart = TYPE_ALIAS_TAG_START.matcher(text);
        while (tagStart.find()) {
            int tagEnd = text.indexOf('>', tagStart.start());
            if (tagEnd < 0) {
                continue;
            }
            String tag = text.substring(tagStart.start(), tagEnd);
            String alias = firstAttrValue(ALIAS_ATTR, tag);
            if (alias == null) {
                continue;
            }
            String type = firstAttrValue(TYPE_ATTR, tag);
            if (type == null) {
                continue;
            }
            entries.putIfAbsent(alias, type);
        }
        return entries;
    }

    /** 属性正则第 2 组取值并 trim；空串按缺失处理。 */
    private static String firstAttrValue(Pattern attr, String tag) {
        Matcher m = attr.matcher(tag);
        if (!m.find()) {
            return null;
        }
        String value = m.group(2).trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 键集 → 索引 value map。平台把 value 类型定为 {@code Void}（键即全部信息），{@code null} 值
     * 投过去即可（两前置索引同款写法）。
     */
    private static Map<String, Void> indexMap(Set<String> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        Map<String, Void> result = new HashMap<>(keys.size());
        for (String key : keys) {
            result.put(key, null);
        }
        return result;
    }

    @Override
    public @NotNull ID<String, Void> getName() {
        return NAME;
    }

    @Override
    public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    /** 键提取逻辑（锚/正则/键面）变化即 bump，触发平台全量重索引。 */
    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public @NotNull DefaultFileTypeSpecificInputFilter getInputFilter() {
        return new DefaultFileTypeSpecificInputFilter(XmlFileType.INSTANCE);
    }

    @Override
    public boolean dependsOnFileContent() {
        return true;
    }

    @Override
    public @NotNull DataIndexer<String, Void, FileContent> getIndexer() {
        return inputData -> indexMap(keysForText(inputData.getContentAsText().toString()));
    }
}
