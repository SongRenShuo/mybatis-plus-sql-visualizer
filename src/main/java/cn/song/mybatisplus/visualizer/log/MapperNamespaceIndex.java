package cn.song.mybatisplus.visualizer.log;

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
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F1'''（T-GutterFirstOpenDelay 精华采纳刀，2026-11-15）：mapper XML 的 namespace 自定义标量索引。
 *
 * <h2>为什么（MPJ 反编译调研的 Top1 可借鉴点）</h2>
 *
 * <p>调研报告 {@code docs/调研报告/20261115-0202-MPJ跳转实现反编译调研-调研报告.md}：MPJ
 * （MyBatisCodeHelper-Pro 3.6.8+2321）把每个 mapper XML 的 namespace 在<b>索引期</b>摊平成键
 * （自定义 fileBasedIndex，索引 ID {@code MybatisCodeHelperIndexer6}），运行时一次
 * {@code FileBasedIndex.getContainingFiles} 出答案，失效全交平台增量——没有全扫、没有 TTL、
 * 没有「清单未就绪」窗口。
 *
 * <p>我们此前的三轮修复（F1 正缓存 / F1'' 异步头扫 + serve stale + 落地 restart）都是在「无索引」
 * 前提下打补丁；平台 {@code product.jar} 的 EP 声明实证 fileBasedIndex <b>dynamic="true"</b>
 * （非动态卸载屏障）——本插件的热更新特性不受影响（字节码/平台声明为唯一权威，推翻既有记忆里的
 * 屏障清单）。故本轮直接采纳 MPJ 精华：<b>用平台索引替换整套清单状态机</b>。
 *
 * <h2>索引键设计</h2>
 *
 * <p>键 = mapper 根标签的 {@code namespace} 属性值；值 = Void（键→包含文件即答案）。
 * 提取走 {@link #namespaceFromHeadText}（原始头 16KB 内的 {@code <mapper …>} 开标签，双/单引号都认）——
 * MyBatis DTD 规定 namespace 是根标签属性、必在文件头部；iBatis 2.x {@code <sqlMap>} 根不命中锚
 * （与本定位器旧口径一致：iBatis 跳转走 IbatisXmlLocators 独立链，本索引不扩面）。
 *
 * <h2>v2（T-IndexScanSweep2 P1，2026-11-16）：新增两类<b>文件级标记键</b></h2>
 *
 * <p>补全候选清单服务（{@code ui/highlight/XmlSymbolNameIndexCache}）需要「项目里有哪些 mapper 文件 /
 * 哪些 mybatis-config 文件」这两个<b>文件集合</b>——若仍退化成 {@code FileTypeIndex(XML)} 全量，
 * 就回到了「每次补全对最多 500 个 XML 建 PSI」的老形态（本刀要消灭的正是它）。故本索引顺带摊平：
 *
 * <ul>
 * <li>{@code #mapper}（常量键）：「本文件根标签是 {@code <mapper>}」→ 跨文件 {@code <sql id>} 清单构建；</li>
 * <li>{@code #config}（常量键）：「本文件是 mybatis-config 形态：根 {@code <configuration>} 且含
 *     {@code <properties>}/{@code <mappers>} 之一」→ {@code <properties>} key 清单构建。</li>
 * </ul>
 *
 * <p>前缀用 {@code #}：namespace 是 Java 包名形态、不含 {@code #}，故与既有 namespace 键天然不撞
 * （iBatis 侧 all/ns:/stmt: 方案在那边允许无前缀字面量，这边不行）。<b>既有 namespace 键语义零改动</b>
 * （MapperXmlLocator/XmlCrossNsFragmentResolver/MybatisXmlTargets 三个消费方不变），
 * {@code getVersion()} 1 → 2 触发一次平台增量重建（计划内）。
 *
 * <p>根标签判定走 {@link #rootElementName}（跳过 XML 声明/DOCTYPE/注释后首个元素名），比
 * 「首个 {@code <mapper} 锚」更精确——{@code mybatis-config.xml} 的
 * {@code <mappers><mapper resource="…"/></mappers>} 不会被误判为 mapper 文件（老锚会）。
 *
 * <h2>失效与正确性</h2>
 *
 * <p>{@code dependsOnFileContent()=true}：文件内容变更即增量重索引，平台管理，无自建失效。
 * <b>正确性闸门在 PSI 侧</b>：{@code MapperXmlLocator.findStatementTag} 校验根标签 namespace 与 id
 * 精确匹配——索引期提取的键即使偶有偏差（头 16KB 窗外、嵌套形态），也只影响「查得到查不到」，不会错跳。
 *
 * <p><b>同一个平台的坑（实测，2026-11-16）</b>：{@code FileBasedIndex.getIndexModificationStamp(NAME, project)}
 * 只随「索引<b>产出内容</b>变化」递增——实测「新增一个 XML 文件」= +1，而「改已存在 mapper 的
 * {@code <sql id>}（namespace/根标签没变 ⇒ 索引键不变）」<b>恒不变</b>。故消费方（清单服务）不能用它
 * 单独当失效代戳，只能与「来源文件的 {@code VirtualFile.modificationStamp}」合用（见 {@link #getIndexStamp}）。
 */
public class MapperNamespaceIndex extends ScalarIndexExtension<String> {

    /** 索引 ID：命名带插件前缀防跨插件撞名（MPJ 的同类索引叫 MybatisCodeHelperIndexer6）。 */
    public static final ID<String, Void> NAME = ID.create("cn.song.mybatisplus.mapperNamespace");

    /** 文件级标记键：本文件根标签是 {@code <mapper>}。 */
    public static final String KEY_MAPPER = "#mapper";

    /** 文件级标记键：本文件是 mybatis-config 形态。 */
    public static final String KEY_CONFIG = "#config";

    private static final String ROOT_MAPPER = "mapper";
    private static final String ROOT_CONFIGURATION = "configuration";

    /** 头扫字节数：mapper 根标签必在文件头部（XML 声明 + DOCTYPE 之后）。 */
    private static final int HEAD_SCAN_BYTES = 16 * 1024;

    /** {@code <mapper} 根标签开锚（{@code <mappers}/{@code <mapperX} 不命中）；属性序列任意，namespace 双/单引号都认。 */
    private static final Pattern MAPPER_TAG_START = Pattern.compile("<mapper[\\s>/]");

    private static final Pattern NS_ATTR = Pattern.compile("namespace\\s*=\\s*([\"'])([^\"']+)\\1");

    /** mybatis-config 形态判定锚（{@code <properties>}/{@code <mappers>} 开标签）。 */
    private static final Pattern PROPERTIES_TAG_START = Pattern.compile("<properties[\\s>/]");
    private static final Pattern MAPPERS_TAG_START = Pattern.compile("<mappers[\\s>/]");

    /** 根元素名开锚（{@code <name}，名字允许 {@code _}/{@code -}/{@code .} 的 XML Name 子集）。 */
    private static final Pattern ELEMENT_NAME = Pattern.compile("^<([A-Za-z_][\\w.-]*)");

    @Override
    public @NotNull ID<String, Void> getName() {
        return NAME;
    }

    @Override
    public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    /** 键面/提取逻辑变化即 bump（v2 = 新增 {@code #mapper}/{@code #config} 两类标记键）。 */
    @Override
    public int getVersion() {
        return 2;
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
     * namespace → 项目内的 mapper XML 文件列表（索引查询，毫秒级；多模块同名全返回，
     * 调用方取第一个并留痕）。dumb/索引未就绪期可能返回不全——调用方（慢趟 provider）有
     * F1' 失败分支（正缓存/dumb 乐观产出）兜底，不会把「还没查到」固化为「真没有」。
     */
    public static List<VirtualFile> getMapperXmlFiles(Project project, String namespace) {
        return List.copyOf(FileBasedIndex.getInstance()
                .getContainingFiles(NAME, namespace, GlobalSearchScope.projectScope(project)));
    }

    /** 项目内全部 mapper XML 文件（根标签 {@code <mapper>}；索引查询，不建 PSI）。 */
    public static List<VirtualFile> getMapperFiles(Project project) {
        return query(project, KEY_MAPPER);
    }

    /** 项目内全部 mybatis-config 文件（根 {@code <configuration>} 且含 {@code <properties>}/{@code <mappers>}）。 */
    public static List<VirtualFile> getConfigFiles(Project project) {
        return query(project, KEY_CONFIG);
    }

    /**
     * 本索引的<b>索引内容代戳</b>（{@code FileBasedIndex.getIndexModificationStamp}，IU-243/262 双平台
     * javap 实证在册）。注意实测语义（见类头）：它只随索引<b>产出</b>变化，<b>不</b>随「不改变索引键的
     * 内容编辑」变化——消费方须与来源文件的 {@code VirtualFile.modificationStamp} 合用。
     */
    public static long getIndexStamp(Project project) {
        return FileBasedIndex.getInstance().getIndexModificationStamp(NAME, project);
    }

    private static List<VirtualFile> query(Project project, String key) {
        return List.copyOf(FileBasedIndex.getInstance()
                .getContainingFiles(NAME, key, GlobalSearchScope.projectScope(project)));
    }

    /**
     * 纯文本判定内核（可纯单测）：本文件产出的全部索引键。
     *
     * <ul>
     * <li>namespace 键：走 {@link #namespaceFromHeadText}（与 v1 一字不差的旧口径）；</li>
     * <li>{@code #mapper}：根元素名 == mapper；</li>
     * <li>{@code #config}：根元素名 == configuration 且全文含 {@code <properties>} 或 {@code <mappers>} 开标签。</li>
     * </ul>
     */
    static Set<String> keysForText(String text) {
        Set<String> keys = new LinkedHashSet<>(4);
        String namespace = namespaceFromHeadText(text);
        if (namespace != null && !namespace.isBlank()) {
            keys.add(namespace);
        }
        String root = rootElementName(text);
        if (ROOT_MAPPER.equals(root)) {
            keys.add(KEY_MAPPER);
        } else if (ROOT_CONFIGURATION.equals(root)) {
            if (PROPERTIES_TAG_START.matcher(text).find() || MAPPERS_TAG_START.matcher(text).find()) {
                keys.add(KEY_CONFIG);
            }
        }
        return keys;
    }

    /**
     * 根元素名（小写）：跳过 XML 声明 {@code <?…?>}、注释 {@code <!--…-->}、DOCTYPE/其它 {@code <!…>}
     * 之后的<b>首个</b>元素开标签名。无元素/畸形 → null。
     *
     * <p>只用于 {@code #mapper}/{@code #config} 的<b>文件级</b>判定；namespace 键仍走
     * {@link #namespaceFromHeadText}（不改既有语义）。HEAD_SCAN_BYTES 之外不再扫（根标签必在头部；
     * 超窗 → 判定失败，只少收不多收）。
     */
    @Nullable
    static String rootElementName(String text) {
        int end = Math.min(HEAD_SCAN_BYTES, text.length());
        int i = 0;
        while (i < end) {
            int lt = text.indexOf('<', i);
            if (lt < 0 || lt >= end) {
                return null;
            }
            if (text.startsWith("<!--", lt)) {
                int e = text.indexOf("-->", lt);
                if (e < 0) {
                    return null;
                }
                i = e + 3;
            } else if (lt + 1 < end && text.charAt(lt + 1) == '?') {
                int e = text.indexOf("?>", lt);
                if (e < 0) {
                    return null;
                }
                i = e + 2;
            } else if (lt + 1 < end && text.charAt(lt + 1) == '!') {
                // DOCTYPE（含内部子集 `[ … ]`）统一按首个 `>` 收尾：MyBatis DTD 的 PUBLIC/SYSTEM
                // 串内不含 `>`，够用；异常形态只会让判定失败（少收），不会错收。
                int e = text.indexOf('>', lt);
                if (e < 0) {
                    return null;
                }
                i = e + 1;
            } else {
                Matcher m = ELEMENT_NAME.matcher(text.substring(lt, Math.min(end, lt + 80)));
                if (!m.find()) {
                    return null;
                }
                return m.group(1).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /**
     * 纯文本判定内核（可纯单测）：{@code <mapper …>} 开标签内的 namespace 属性值；无/空 → null。
     * 索引期喂 {@code FileContent.contentAsText}（全文本）——根标签必在前部，首锚即根。
     */
    @Nullable
    static String namespaceFromHeadText(String head) {
        Matcher tagMatcher = MAPPER_TAG_START.matcher(head);
        if (!tagMatcher.find()) {
            return null;
        }
        int tagStart = tagMatcher.start();
        int tagEnd = head.indexOf('>', tagStart);
        if (tagEnd < 0) {
            return null;
        }
        Matcher nsMatcher = NS_ATTR.matcher(head.substring(tagStart, tagEnd));
        if (!nsMatcher.find()) {
            return null;
        }
        String namespace = nsMatcher.group(2);
        return namespace.isBlank() ? null : namespace;
    }

    /**
     * 键集 → 索引 value map。平台把 value 类型定为 {@code Void}（键即全部信息），
     * HashMap 允许 null 值（照 {@code xml/ibatis/SqlMapNamespaceIndex} 同款写法）。
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
