// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ref/MybatisXmlTargets.kt
package cn.song.mybatisplus.visualizer.ui.ref;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportStatementBase;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * T-XmlGap W7：mapper XML 内的引用解析目标集合（不注册任何 inspection/annotator——红线）。
 *
 * <p>解析面（计划 §六 W7 行）：{@code refid} → {@code <sql id>}、{@code resultMap}/{@code extends} →
 * {@code <resultMap id>}、{@code parameterMap} → {@code <parameterMap id>}、{@code namespace} → Java 接口、
 * statement {@code id} → 接口方法、{@code resultType}/{@code type}/{@code ofType}/{@code parameterType}/
 * {@code javaType}/{@code typeHandler} → PsiClass、{@code jdbcType} → JdbcType 枚举常量、
 * {@code property} → 实体字段。</p>
 *
 * <p>依赖红线：生产依赖图<b>零 mybatis</b>（build.gradle.kts 实测），故 {@code jdbcType} 只能按<b>类名</b>
 * 运行时解析（{@code JavaPsiFacade.findClass("org.apache.ibatis.type.JdbcType")}——用户工程自带
 * MyBatis 时才命中），禁止在代码里 {@code import org.apache.ibatis.*}。</p>
 *
 * <p>移植注记：Kotlin object → Java 私有构造器 final 工具类 + static 方法。</p>
 */
public final class MybatisXmlTargets {

    private MybatisXmlTargets() {
    }

    /** 属性值宿主的属性名（{@code refid} / {@code resultMap} …）。 */
    @Nullable
    public static String attributeName(XmlAttributeValue value) {
        return value.getParent() instanceof XmlAttribute ? ((XmlAttribute) value.getParent()).getName() : null;
    }

    /** 属性值宿主所属标签名（select / resultMap / include …）。 */
    @Nullable
    public static String ownerTagName(XmlAttributeValue value) {
        XmlAttribute attr = value.getParent() instanceof XmlAttribute ? (XmlAttribute) value.getParent() : null;
        return attr != null && attr.getParent() instanceof XmlTag
                ? ((XmlTag) attr.getParent()).getName() : null;
    }

    /** 根 {@code <mapper>} 的 namespace（无则 null）。 */
    @Nullable
    public static String rootNamespace(@Nullable PsiFile file) {
        if (!(file instanceof XmlFile) || ((XmlFile) file).getRootTag() == null) return null;
        return ((XmlFile) file).getRootTag().getAttributeValue("namespace");
    }

    /** 根标签名 == mapper（W7 引用的根守卫，与注入链 XmlTag 祖先判定同口径）。 */
    public static boolean isMapperFile(@Nullable PsiFile file) {
        return file instanceof XmlFile && ((XmlFile) file).getRootTag() != null
                && ((XmlFile) file).getRootTag().getName().equals("mapper");
    }

    /** 同一文件内按 {@code id} 找标签的 {@code id} 属性值元素（跳转目标）。 */
    @Nullable
    public static XmlAttributeValue findIdAttributeInFile(@Nullable PsiFile file, String tagName, String id) {
        if (!(file instanceof XmlFile)) return null;
        XmlTag root = ((XmlFile) file).getRootTag();
        if (root == null) return null;
        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(root, XmlTag.class)) {
            if (tag.getName().equals(tagName) && java.util.Objects.equals(tag.getAttributeValue("id"), id)) {
                return tag.getAttribute("id") != null ? tag.getAttribute("id").getValueElement() : null;
            }
        }
        return null;
    }

    /**
     * 解析 {@code ns.id} / 同文件 {@code id} 形态的标识引用 → 目标 {@code id} 属性值元素。
     *
     * @param contextFile 引用所在文件（无 {@code ns.} 前缀时在同文件里找）
     */
    @Nullable
    public static XmlAttributeValue resolveIdReference(
            Project project, @Nullable PsiFile contextFile, String tagName, String rawRefId) {
        String refId = rawRefId.trim();
        if (refId.isEmpty()) return null;
        int dot = refId.lastIndexOf('.');
        if (dot > 0) {
            String ns = refId.substring(0, dot);
            String id = refId.substring(dot + 1);
            XmlFile target = findMapperFileByNamespace(project, ns);
            if (target == null) return null;
            return findIdAttributeInFile(target, tagName, id);
        }
        return findIdAttributeInFile(contextFile, tagName, refId);
    }

    /**
     * 按 namespace 找 mapper XML 文件（索引 + 根元素预筛，文件清单单槽 TTL 缓存 +
     * <b>namespace→文件结果级 memo</b>，见 [namespaceSlot]）。
     *
     * <p>F96（T-PerfSweep26）：只有文件清单缓存时，同一 namespace 的重复 resolve 每次都要
     * 逐文件 {@code PsiManager.findFile} + {@code rootTag.namespace} 比对；查找用法/重命名会批量触发，
     * 成本相乘。结果级 memo 以<b>文件清单代</b>为戳（清单重建即失效），负结果一并缓存。
     */
    @Nullable
    public static XmlFile findMapperFileByNamespace(Project project, String namespace) {
        if (namespace.isEmpty()) return null;
        Listing listing = mapperXmlListing(project);
        NamespaceSlot slot = namespaceSlot;
        if (slot != null
                && slot.project.get() == project
                && slot.listingGeneration == listing.generation
                && slot.namespace.equals(namespace)) {
            return slot.file != null && slot.file.isValid() ? slot.file : null;
        }
        namespaceScanCount.incrementAndGet();
        XmlFile found = null;
        for (VirtualFile vf : listing.files) {
            if (!vf.isValid()) continue;
            PsiFile pf = PsiManager.getInstance(project).findFile(vf);
            if (!(pf instanceof XmlFile)) continue;
            XmlFile xmlFile = (XmlFile) pf;
            if (xmlFile.getRootTag() != null && namespace.equals(xmlFile.getRootTag().getAttributeValue("namespace"))) {
                found = xmlFile;
                break;
            }
        }
        namespaceSlot = new NamespaceSlot(new WeakReference<>(project), listing.generation, namespace, found);
        return found;
    }

    /**
     * 解析类名（{@code resultType} / {@code parameterType} / {@code typeHandler} …）→ PsiClass。
     *
     * <p>三条路线：全限定名直接 findClass；短名先走 namespace 接口所在文件的 import 表；再退到
     * 短名索引（dumb 态索引不可用 → 返回 null，不报红——引用本身是 soft）。</p>
     */
    @Nullable
    public static PsiClass resolveClass(@Nullable Project project, XmlAttributeValue context, String rawName) {
        String name = rawName.trim();
        int lt = name.indexOf('<');
        if (lt >= 0) name = name.substring(0, lt);
        int lb = name.indexOf('[');
        if (lb >= 0) name = name.substring(0, lb);
        if (project == null || name.isEmpty()) return null;
        classResolveCount.incrementAndGet();
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        int lastDot = name.lastIndexOf('.');
        String shortName = lastDot >= 0 ? name.substring(lastDot + 1) : name;

        if (name.contains(".")) {
            PsiClass byFqn = facade.findClass(name, scope);
            if (byFqn != null) return byFqn;
        }
        // namespace 接口所在文件的 import 表（短名/别名最常见来源）
        String ns = rootNamespace(context.getContainingFile());
        if (ns != null) {
            PsiClass nsClass = facade.findClass(ns, scope);
            PsiFile nsFile = nsClass != null ? nsClass.getContainingFile() : null;
            if (nsFile instanceof PsiJavaFile) {
                PsiImportStatementBase[] imports = ((PsiJavaFile) nsFile).getImportList() != null
                        ? ((PsiJavaFile) nsFile).getImportList().getImportStatements()
                        : new PsiImportStatementBase[0];
                for (PsiImportStatementBase imp : imports) {
                    // 新平台 API：qualifiedName 已由 importReference 承担（PsiImportStatement 同名方法已移除）
                    if (imp.getImportReference() == null) continue;
                    String qn = imp.getImportReference().getQualifiedName();
                    if (qn == null) continue;
                    if (qn.substring(qn.lastIndexOf('.') + 1).equals(shortName)) {
                        PsiClass byImport = facade.findClass(qn, scope);
                        if (byImport != null) return byImport;
                    }
                }
            }
            // 同包（namespace 接口包内）
            String qualified = nsClass != null ? nsClass.getQualifiedName() : null;
            if (qualified != null) {
                int pkgDot = qualified.lastIndexOf('.');
                String pkg = pkgDot >= 0 ? qualified.substring(0, pkgDot) : "";
                if (!pkg.isEmpty()) {
                    PsiClass samePackage = facade.findClass(pkg + "." + shortName, scope);
                    if (samePackage != null) return samePackage;
                }
            }
        }
        if (DumbService.isDumb(project)) return null;
        PsiClass[] classes = PsiShortNamesCache.getInstance(project).getClassesByName(shortName, scope);
        return classes.length > 0 ? classes[0] : null;
    }

    /** {@code jdbcType="VARCHAR"} → {@code org.apache.ibatis.type.JdbcType} 的枚举常量字段。 */
    @Nullable
    public static PsiField resolveJdbcTypeField(@Nullable Project project, String rawName) {
        String name = rawName.trim();
        if (project == null || name.isEmpty()) return null;
        PsiClass enumClass = JavaPsiFacade.getInstance(project)
                .findClass(JDBC_TYPE_FQN, GlobalSearchScope.allScope(project));
        if (enumClass == null) return null;
        return enumClass.findFieldByName(name, false);
    }

    /** statement {@code id} → namespace 接口的同名方法。 */
    @Nullable
    public static PsiMethod resolveInterfaceMethod(@Nullable Project project, @Nullable String namespace, String methodName) {
        String name = methodName.trim();
        if (project == null || namespace == null || namespace.isEmpty() || name.isEmpty()) return null;
        PsiClass iface = JavaPsiFacade.getInstance(project)
                .findClass(namespace, GlobalSearchScope.allScope(project));
        if (iface == null) return null;
        PsiMethod[] methods = iface.findMethodsByName(name, true);
        return methods.length > 0 ? methods[0] : null;
    }

    /** {@code property="a.b"} / {@code <arg property>} 的<b>首段</b>字段解析（深层链后续可扩展）。 */
    @Nullable
    public static PsiField resolvePropertyField(@Nullable Project project, @Nullable PsiClass holderClass, String rawProperty) {
        if (project == null || holderClass == null) return null;
        String first = firstSegment(rawProperty);
        if (first.isEmpty()) return null;
        PsiClass cls = holderClass;
        int depth = 0;
        while (cls != null && depth++ < MAX_PROPERTY_DEPTH) {
            PsiField field = cls.findFieldByName(first, true);
            if (field != null) return field;
            cls = cls.getSuperClass();
        }
        return null;
    }

    /** 属性链解析：逐段下钻，返回末段字段（能解析多少算多少）。 */
    @Nullable
    public static PsiField resolvePropertyChain(@Nullable Project project, @Nullable PsiClass holderClass, String rawProperty) {
        if (project == null || holderClass == null) return null;
        List<String> segments = new ArrayList<>();
        for (String seg : rawProperty.trim().split("\\.")) {
            if (!seg.isBlank()) segments.add(seg);
        }
        if (segments.isEmpty()) return null;
        PsiClass cls = holderClass;
        PsiField last = null;
        for (String seg : segments) {
            PsiField field = resolveFieldInHierarchy(cls, beforeBracket(seg));
            if (field == null) {
                return last;
            }
            last = field;
            cls = field.getType() instanceof PsiClassType ? ((PsiClassType) field.getType()).resolve() : null;
        }
        return last;
    }

    @Nullable
    private static PsiField resolveFieldInHierarchy(@Nullable PsiClass start, String name) {
        PsiClass cls = start;
        int depth = 0;
        while (cls != null && depth++ < MAX_PROPERTY_DEPTH) {
            PsiField field = cls.findFieldByName(name, true);
            if (field != null) return field;
            cls = cls.getSuperClass();
        }
        return null;
    }

    /** 首段标识符（剥 {@code .} 之后与 {@code [} 下标段）。 */
    private static String firstSegment(String rawProperty) {
        String s = rawProperty.trim();
        int dot = s.indexOf('.');
        if (dot >= 0) s = s.substring(0, dot);
        int lb = s.indexOf('[');
        if (lb >= 0) s = s.substring(0, lb);
        return s;
    }

    /** 剥 {@code [} 下标段。 */
    private static String beforeBracket(String segment) {
        int lb = segment.indexOf('[');
        return lb >= 0 ? segment.substring(0, lb) : segment;
    }

    private static final String JDBC_TYPE_FQN = "org.apache.ibatis.type.JdbcType";
    private static final int MAX_PROPERTY_DEPTH = 16;

    // ------------------------------------------------------------------
    // mapper XML 文件清单（单槽 TTL 缓存；模式同 log/MapperXmlLocator，禁引监听器）
    // ------------------------------------------------------------------

    private static final class FileListSlot {
        final WeakReference<Project> project;
        final List<VirtualFile> files;
        final long atMs;
        final long generation;

        FileListSlot(WeakReference<Project> project, List<VirtualFile> files, long atMs, long generation) {
            this.project = project;
            this.files = files;
            this.atMs = atMs;
            this.generation = generation;
        }
    }

    /**
     * F96（T-PerfSweep26）：namespace → mapper 文件的<b>单槽结果级 memo</b>。
     *
     * <p>失效戳=文件清单代 [FileListSlot] 的 {@code generation}（清单重建即换代，含新增/删除 mapper XML 后
     * TTL 过期重扫）；文件对象自身 invalid（被删/移动）时回落重查。单槽容量=最近一个 namespace
     * ——引用解析天然按 refid 顺序访问、局部性强，且槽内只持一个 XmlFile 强引用（PSI 强引用
     * 生命周期与项目一致，不额外钉住文件）。
     */
    private static final class NamespaceSlot {
        final WeakReference<Project> project;
        final long listingGeneration;
        final String namespace;
        @Nullable final XmlFile file;

        NamespaceSlot(WeakReference<Project> project, long listingGeneration, String namespace, @Nullable XmlFile file) {
            this.project = project;
            this.listingGeneration = listingGeneration;
            this.namespace = namespace;
            this.file = file;
        }
    }

    /** 文件清单 + 其代（代在每次重扫时递增；namespace memo 以此为失效戳）。 */
    private static final class Listing {
        final List<VirtualFile> files;
        final long generation;

        Listing(List<VirtualFile> files, long generation) {
            this.files = files;
            this.generation = generation;
        }
    }

    /** TTL 上界（毫秒）：新增 mapper XML 后至多一个窗口内解析不到（过期即重扫）。 */
    public static volatile long fileListTtlMs = 5_000L;

    public static volatile LongSupplier clockMs = System::currentTimeMillis;

    public static final AtomicInteger indexScanCount = new AtomicInteger(0);

    /** F96 测试缝：namespace→文件实查次数（memo 命中不计数）。 */
    public static final AtomicInteger namespaceScanCount = new AtomicInteger(0);

    /** F97 测试缝：{@code resolveClass} 实际执行次数（批内 memo 命中不计数）。 */
    public static final AtomicInteger classResolveCount = new AtomicInteger(0);

    private static volatile FileListSlot fileListSlot = null;

    private static volatile NamespaceSlot namespaceSlot = null;

    private static long listingGenerationCounter = 0L;

    public static void clearFileListCacheForTest() {
        fileListSlot = null;
        namespaceSlot = null;
    }

    private static Listing mapperXmlListing(Project project) {
        long now = clockMs.getAsLong();
        FileListSlot slot = fileListSlot;
        if (slot != null) {
            boolean allValid = true;
            for (VirtualFile vf : slot.files) {
                if (!vf.isValid()) {
                    allValid = false;
                    break;
                }
            }
            if (slot.project.get() == project && now - slot.atMs <= fileListTtlMs && allValid) {
                return new Listing(slot.files, slot.generation);
            }
        }
        indexScanCount.incrementAndGet();
        List<VirtualFile> files;
        try {
            com.intellij.openapi.fileTypes.FileType xmlFileType =
                    com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getStdFileType("XML");
            List<VirtualFile> found = new ArrayList<>();
            for (VirtualFile vf : FileTypeIndex.getFiles(xmlFileType, GlobalSearchScope.projectScope(project))) {
                PsiFile pf = PsiManager.getInstance(project).findFile(vf);
                if (!(pf instanceof XmlFile)) continue;
                XmlFile xmlFile = (XmlFile) pf;
                if (xmlFile.getRootTag() != null && xmlFile.getRootTag().getName().equals("mapper")) {
                    found.add(vf);
                }
            }
            files = found;
        } catch (Throwable t) {
            files = List.of();
        }
        long generation = ++listingGenerationCounter;
        fileListSlot = new FileListSlot(new WeakReference<>(project), files, now, generation);
        return new Listing(files, generation);
    }

    /** 测试缝：PsiElement 归属的 mapper 文件（供用例断言解析落点）。 */
    @Nullable
    public static PsiFile ownerFileOf(PsiElement element) {
        return element.getContainingFile();
    }
}
