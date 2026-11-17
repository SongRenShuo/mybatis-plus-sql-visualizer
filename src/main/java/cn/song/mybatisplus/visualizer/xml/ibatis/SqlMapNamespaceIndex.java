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

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T-IbatisGutterPerf（2026-11-16）：iBatis 2.x {@code <sqlMap>} 的自定义标量索引。
 *
 * <h2>为什么（对位 MyBatis 侧的 {@code MapperNamespaceIndex}）</h2>
 *
 * <p>旧链 {@code ui/nav/IbatisXmlStatementTargets.sqlMapListing} 的做法是：{@code FileTypeIndex} 取
 * <b>全项目 XML</b>（demo 根工程实测 2493 个 / 16.58 MB），再对每个文件 {@code PsiManager.findFile} +
 * 取 rootTag 才筛出 303 个 {@code <sqlMap>}——每 5 秒 TTL 过期就重扫一遍，且被慢趟、Ctrl+B、预览点击
 * 三条路径共用。本索引把「哪些文件是 sqlMap / 各自 namespace / 各自有哪些语句 id」在<b>索引期</b>摊平，
 * 运行时一次 {@code getContainingFiles} 出候选文件，失效全交平台增量（dependsOnFileContent=true），
 * 没有 TTL、没有「清单未就绪」窗口、没有进程级单槽被多工程互踢的问题。
 *
 * <h2>索引键设计（一份文件可产多键）</h2>
 * <ul>
 * <li>{@code all}：「本文件是 sqlMap」的常量标记（裸 id 全量兜底 / 诊断）；</li>
 * <li>{@code ns:<namespace>}：根标签 namespace 属性值（{@code ns.id} 形态的主查询）；</li>
 * <li>{@code stmt:<id>}：6 类语句标签（{@link IbatisStatementKind}）的 id 属性值
 *     （裸 id 形态，{@code useStatementNamespaces=false}，iBatis 默认）。</li>
 * </ul>
 *
 * <p>键名带前缀，故 namespace 或语句 id 字面量等于 {@code all} 也不会撞键。
 *
 * <h2>键的提取（索引期，纯文本，不建 PSI）</h2>
 *
 * <p>索引期拿到的是全文（{@code FileContent.contentAsText}），按根标签锚 {@code <sqlMap} 判定是否
 * iBatis 文件，再在根标签内的 namespace 取键；语句 id 走全文扫描（iBatis 语句可在文件的任意位置）。
 * 提取内核全部是纯函数（{@link #namespaceFromText} / {@link #statementIdsFromText} / {@link #keysForText}），
 * 可纯单测。
 *
 * <p>提取面的两条已知边界（都只有「少收」风险，而少收由 PSI 侧兜底为「本次不出图标」，不会错跳）：
 * {@code <sqlMap} 出现在长注释里会被当成根（多收，PSI 侧 rootTag 校验直接否掉）；语句 id 写在
 * CDATA/注释里会被多收（同上）；写在正则覆盖不到的畸形属性序里会少收。
 *
 * <h2>正确性闸门在 PSI 侧</h2>
 *
 * <p>索引只回答「去哪几个文件里找」。{@code IbatisXmlStatementTargets} 拿到候选文件后仍走
 * rootTag + id 精确匹配——索引键偶有偏差只影响查得到查不到，不会跳错位置。
 *
 * <h2>与 {@code MapperNamespaceIndex} 的关系（为什么不合并）</h2>
 *
 * <p>复用那个已发行索引要把它的版本从 1 抬到 2 并触发全量重建，还会把 MyBatis 与 iBatis 两个 ORM 域
 * 的失效/失败面耦在一起。新开索引各自独立 bump，代价只是 XML 文件多跑一遍文本扫描（相对整文件
 * PSI 解析可以忽略）。平台 EP {@code <fileBasedIndex … dynamic="true"/>}（product.jar 实证）非卸载屏障，
 * 热更新特性不受影响。
 */
public class SqlMapNamespaceIndex extends ScalarIndexExtension<String> {

    /** 索引 ID：带插件前缀防跨插件撞名（对位 MyBatis 侧的 {@code cn.song.mybatisplus.mapperNamespace}）。 */
    public static final ID<String, Void> NAME = ID.create("cn.song.mybatisplus.sqlMapNs");

    /** 「本文件是 sqlMap」的常量键。 */
    public static final String ALL_KEY = "all";

    private static final String NS_PREFIX = "ns:";
    private static final String STMT_PREFIX = "stmt:";

    /** 根标签锚：{@code <sqlMap} 后必须跟空白 / {@code >} / {@code /}（{@code <sqlMapConfig} 不命中）。 */
    private static final Pattern SQL_MAP_TAG_START = Pattern.compile("<sqlMap[\\s>/]");

    /** 配置文件根锚：{@code <sqlMapConfig>} 里也有 {@code <sqlMap resource=…>} 子元素，必须先认出来排掉。 */
    private static final Pattern SQL_MAP_CONFIG_TAG_START = Pattern.compile("<sqlMapConfig[\\s>/]");

    private static final Pattern NS_ATTR = Pattern.compile("\\bnamespace\\s*=\\s*([\"'])([^\"']+)\\1");

    /** 6 类语句标签的开锚（{@code <selectKey>} / {@code <statementX>} 不命中）；名字表来自 {@link IbatisStatementKind}。 */
    private static final Pattern STATEMENT_TAG_START = compileStatementTagStart();

    private static final Pattern ID_ATTR = Pattern.compile("\\bid\\s*=\\s*([\"'])([^\"']+)\\1");

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

    /**
     * 根 {@code namespace} → 项目内 sqlMap 文件（索引查询，毫秒级；多模块同名全返回，调用方取第一个
     * 经 PSI 校验命中的文件）。dumb / 索引未就绪期可能抛 {@code IndexNotReadyException}——
     * 调用方（慢趟 provider）在 dumb 窗不查索引，并保留「正缓存 / 乐观产出」兜底。
     */
    public static List<VirtualFile> getSqlMapFilesByNamespace(Project project, String namespace) {
        String ns = namespace.trim();
        if (ns.isEmpty()) {
            return List.of();
        }
        return query(project, NS_PREFIX + ns);
    }

    /** 语句 {@code id} → 声明它的 sqlMap 文件（裸 id 形态的主查询）。 */
    public static List<VirtualFile> getSqlMapFilesByStatementId(Project project, String statementId) {
        String id = statementId.trim();
        if (id.isEmpty()) {
            return List.of();
        }
        return query(project, STMT_PREFIX + id);
    }

    /** 项目内全部 sqlMap 文件（裸 id 的清单兜底与诊断用）。 */
    public static List<VirtualFile> getAllSqlMapFiles(Project project) {
        return query(project, ALL_KEY);
    }

    private static List<VirtualFile> query(Project project, String key) {
        return List.copyOf(FileBasedIndex.getInstance()
                .getContainingFiles(NAME, key, GlobalSearchScope.projectScope(project)));
    }

    /**
     * 纯文本判定内核（可纯单测）：本文件产出的全部索引键；根标签不是 {@code <sqlMap>} 时返回空集。
     *
     * <p>语句 id 走<b>全文</b>扫描——iBatis 允许语句出现在根标签订阅之后的任意位置；namespace 取
     * 首个 {@code <sqlMap …>} 开标签内的属性（DTD 规定它是根标签属性）。
     */
    static Set<String> keysForText(String text) {
        Integer rootStart = rootTagStart(text);
        if (rootStart == null) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>(8);
        keys.add(ALL_KEY);
        String namespace = namespaceFromRootTag(text, rootStart);
        if (namespace != null) {
            keys.add(NS_PREFIX + namespace);
        }
        for (String id : statementIdsFromText(text)) {
            keys.add(STMT_PREFIX + id);
        }
        return keys;
    }

    /** 首个 {@code <sqlMap …>} 开标签的位置；根不是 {@code <sqlMap>}（含 {@code <sqlMapConfig>} 里的子元素）→ null。 */
    static Integer rootTagStart(String text) {
        Matcher rootMatcher = SQL_MAP_TAG_START.matcher(text);
        if (!rootMatcher.find()) {
            return null;
        }
        int rootStart = rootMatcher.start();
        Matcher configMatcher = SQL_MAP_CONFIG_TAG_START.matcher(text);
        if (configMatcher.find() && configMatcher.start() < rootStart) {
            return null;
        }
        return rootStart;
    }

    /** 首个 {@code <sqlMap …>} 开标签的 {@code namespace} 属性值；缺失/空串 → null。 */
    static String namespaceFromText(String text) {
        Integer rootStart = rootTagStart(text);
        return rootStart == null ? null : namespaceFromRootTag(text, rootStart);
    }

    @org.jetbrains.annotations.Nullable
    private static String namespaceFromRootTag(String text, int rootStart) {
        int tagEnd = text.indexOf('>', rootStart);
        if (tagEnd < 0) {
            return null;
        }
        Matcher matcher = NS_ATTR.matcher(text.substring(rootStart, tagEnd));
        if (!matcher.find()) {
            return null;
        }
        String namespace = matcher.group(2).trim();
        return namespace.isEmpty() ? null : namespace;
    }

    /**
     * 全文里的 6 类语句标签 {@code id} 属性值（去重、保序）。
     *
     * <p>只看开标签内的属性（{@code >} 前），因此不会把标签体 SQL 里的 {@code id} 字样误收；
     * 跨行属性、属性序任意、单双引号都认。
     */
    static Set<String> statementIdsFromText(String text) {
        if (!text.contains("id")) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>(8);
        Matcher tagMatcher = STATEMENT_TAG_START.matcher(text);
        while (tagMatcher.find()) {
            int tagStart = tagMatcher.start();
            int tagEnd = text.indexOf('>', tagStart);
            if (tagEnd < 0) {
                continue;
            }
            Matcher idMatcher = ID_ATTR.matcher(text.substring(tagStart, tagEnd));
            if (!idMatcher.find()) {
                continue;
            }
            String id = idMatcher.group(2).trim();
            if (id.isEmpty()) {
                continue;
            }
            ids.add(id);
        }
        return ids;
    }

    private static Pattern compileStatementTagStart() {
        StringBuilder names = new StringBuilder();
        for (IbatisStatementKind kind : IbatisStatementKind.values()) {
            if (names.length() > 0) {
                names.append('|');
            }
            names.append(kind.getTagName());
        }
        return Pattern.compile("<(?:" + names + ")(?=[\\s>/])");
    }

    /**
     * 键集 → 索引 value map。平台把 value 类型定为 {@code Void}（键即全部信息），HashMap 允许 null 值。
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
}
