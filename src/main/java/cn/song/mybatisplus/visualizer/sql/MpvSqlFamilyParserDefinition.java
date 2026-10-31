// T-SyncK2JMaster175 afd3d09 自 master sql/SqlFamilyParserDefinitions.kt（新增）移植
// （master 单 .kt 文件多类；Java 侧 lang.parserDefinition EP 注册 FQCN 为顶层类
//  （database-optional.xml：cn.song.mybatisplus.visualizer.sql.DmParserDefinition 等），
//  故拆 5 个顶层文件：本类 + 四个方言子类 + 嵌套文件节点类型）
package cn.song.mybatisplus.visualizer.sql;

import com.intellij.lang.Language;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.sql.dialects.SqlLanguageDialect;
import com.intellij.sql.dialects.base.SqlElementFactoryBase;
import com.intellij.sql.dialects.base.SqlParserDefinitionBase;
import com.intellij.sql.dialects.sql92.Sql92ParserDefinition;

/**
 * T-PreviewPsiDialect：四个自注册方言的 {@code lang.parserDefinition}。
 *
 * <h2>为什么「只注册 sql.dialect」不够</h2>
 *
 * <p>平台 {@code SqlParserDefinitionBase.createFile} 里有一句
 * {@code new SqlCodeFragmentImpl(viewProvider, getFileNodeType().getLanguage(), project)}
 * 并把取到的语言强转 {@link SqlLanguageDialect}（字节码 {@code checkcast}，2024.3 制品 javap 实证；
 * 262 真机栈同样落在 {@code SqlParserDefinitionBase.createFile:90}）。</p>
 *
 * <p>我们的 DM / GaussDB / HighGo / Vastbase 原先只注册了 {@code <sql.dialect>}，<b>没有为该语言注册
 * ParserDefinition</b>。平台 {@code LanguageParserDefinitions.forLanguage} 查不到就沿语言链上溯，落到基语言
 * {@code SQL} 的那份（{@code database-plugin.jar} 的 {@code plugin.xml:1253} 是
 * {@code <lang.parserDefinition language="SQL" implementationClass="SqlParserDefinitionBase$Sql"/>}），
 * 而它的 file node type 绑定的是基类 {@code SqlLanguage} —— 强转 {@code SqlLanguageDialect} 必然
 * {@code ClassCastException}，PSI 创建失败，{@code PsiManager.findFile} 返回 null，预览预热降级成内存 PSI
 * （表/列补全不可用，并在 idea.log 里留下点名本插件的 SEVERE）。</p>
 *
 * <p>平台内置方言都是<b>成对</b>注册的：{@code SQL}→{@code SqlParserDefinitionBase$Sql}、
 * {@code GenericSQL}→{@code GenericParserDefinition}、{@code SQL92}→{@code Sql92ParserDefinition}。本文件补齐我们这四对的
 * 后一半。</p>
 *
 * <h2>形态</h2>
 *
 * <p>照平台 {@code GenericParserDefinition}（{@code final}，不可继承，逐字节对齐其最小覆写集）：</p>
 * <ul>
 *  <li>静态 {@code SqlFileElementType(常量名, 本方言语言)} —— <b>这是修复的关键点</b>：file node type 必须绑定
 *      方言语言，{@code createFile} 里的强转才有正确类型；</li>
 *  <li>lexer / parser / elementFactory 一律委托 {@link Sql92ParserDefinition}（我们的方言
 *      {@code createTokensHelper()} 本来就委托 {@code Sql92Dialect}，语义一致）；</li>
 *  <li>不新开错误高亮（方言仍 {@code supportsErrorHighlighting=false}）。</li>
 * </ul>
 *
 * <p>已知边界：文件按 SQL92 语法解析，GaussDB / 瀚高 / 海量 的 PG 系特有语法在 PSI 层可能与真库有出入，
 * 影响面限于补全与高亮，不影响插件自己的渲染（渲染走 {@code sql/Dialect} 的字面口径）。
 * 将来若要方言专属解析，把委托对象换掉即可，注册面不用再动。</p>
 */
public class MpvSqlFamilyParserDefinition extends SqlParserDefinitionBase {

    private final SqlLanguageDialect dialect;

    /**
     * 文件根元素类型。<b>刻意用核心 {@code util} 里的 {@code IFileElementType}</b>，不用
     * {@code com.intellij.sql.psi.stubs.SqlFileElementType}：后者只在 2024.3 基线存在，253 / 262 已移除该包
     * （{@code verifyGate} 实测报「Constructor … references an unresolved class
     * com.intellij.sql.psi.stubs.SqlFileElementType. This can lead to NoSuchClassError at runtime」，
     * 243 Compatible / 253 与 262 各 1 problem）。平台 {@code SqlParserDefinitionBase.createFile} 只用
     * {@code getFileNodeType().getLanguage()}，核心类型足够；预览临时文件是瞬时 PSI、不参与 SQL 存根索引。
     */
    private final IFileElementType fileElementType;

    /** Kotlin {@code by lazy} 的 DCL 承载（平台只读一次并缓存——SqlParserDefinitionBase.getElementFactory 内有 volatile 缓存）。 */
    private volatile Sql92ParserDefinition sql92;

    protected MpvSqlFamilyParserDefinition(SqlLanguageDialect dialect, String fileTypeName) {
        this.dialect = dialect;
        this.fileElementType = new MpvSqlFileElementType(fileTypeName, dialect);
    }

    private Sql92ParserDefinition sql92() {
        Sql92ParserDefinition local = sql92;
        if (local == null) {
            synchronized (this) {
                local = sql92;
                if (local == null) {
                    local = new Sql92ParserDefinition();
                    sql92 = local;
                }
            }
        }
        return local;
    }

    @Override
    public IFileElementType getFileNodeType() {
        return fileElementType;
    }

    @Override
    protected SqlElementFactoryBase createElementFactory() {
        return sql92().getElementFactory();
    }

    @Override
    public Lexer createLexer(Project project) {
        return sql92().createLexer(project);
    }

    @Override
    public PsiParser createParser(Project project) {
        return sql92().createParser(project);
    }

    /**
     * 绑定本方言语言的 {@code IFileElementType}——<b>用核心 {@code IElementType(String, Language)} 直接实现</b>
     * （{@code IElementType} 是抽象类，{@code IFileElementType} 是纯标记接口），不碰 SQL 侧的
     * {@code com.intellij.sql.psi.stubs.SqlFileElementType}：那个类只在 2024.3 基线存在，253 / 262 已删除，
     * 静态引用会被 {@code verifyGate} 判为「unresolved class → NoSuchClassError at runtime」
     * （实测 243 Compatible / 253 与 262 各 1 problem）。
     *
     * <p>也不用 {@code StubFileElementType}：它要另实现 StubSerializer 四件套（serialize / deserialize /
     * indexStub / getExternalId），而预览临时文件是<b>瞬时 PSI</b>——不建存根、不进索引，不需要这些能力。</p>
     */
    private static final class MpvSqlFileElementType extends IFileElementType {
        MpvSqlFileElementType(String debugName, Language language) {
            super(debugName, language);
        }
    }
}
