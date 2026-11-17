// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/SqlMapperChangeLocalityDetector.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.codeInsight.daemon.ChangeLocalityDetector;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * T-XmlGap W6：mapper XML 语句文本编辑时<b>放大重算脏区</b>（竞品同型设计的收窄版）。
 *
 * <p>竞品把 SQL 文本编辑的脏区放大到<b>整文件</b>（内部调研报告 §2.3）；本实现收窄到<b>所属语句标签</b>：
 * 目的只是让该语句的注入与高亮及时重算（动态标签/{@code #{}} 改动会改变注入区间），把整文件拖进重算
 * 对大 mapper 是纯浪费——故取「语句标签」为界，收益/代价比更合理（该取舍记入计划 §实施记录）。</p>
 *
 * <p>只在 mapper XML 内、且元素是语句标签下的 XmlText/XmlAttributeValue 时返回脏区；其余返回 null
 * （交回平台默认语义，零行为变化）。</p>
 */
public class SqlMapperChangeLocalityDetector implements ChangeLocalityDetector {

    @Override
    @Nullable
    public PsiElement getChangeHighlightingDirtyScopeFor(@NotNull PsiElement changedElement) {
        if (!(changedElement.getContainingFile() instanceof XmlFile)) return null;
        XmlFile file = (XmlFile) changedElement.getContainingFile();
        if (file.getRootTag() == null || !file.getRootTag().getName().equals("mapper")) return null;
        XmlText text;
        if (changedElement instanceof XmlText) {
            text = (XmlText) changedElement;
        } else if (changedElement.getParent() instanceof XmlText) {
            text = (XmlText) changedElement.getParent();
        } else {
            return null;
        }
        if (!(text.getParent() instanceof XmlTag)) return null;
        XmlTag tag = (XmlTag) text.getParent();
        XmlTag cur = tag;
        int depth = 0;
        while (cur != null && depth++ < MAX_ANCESTOR_DEPTH) {
            if (STATEMENT_TAGS.contains(cur.getName())) return cur;
            cur = cur.getParentTag();
        }
        return null;
    }

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete", "sql");

    private static final int MAX_ANCESTOR_DEPTH = 24;
}
