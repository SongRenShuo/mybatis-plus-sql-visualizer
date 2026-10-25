package cn.song.mybatisplus.visualizer.config;

import com.intellij.ide.ui.search.SearchableOptionContributor;
import com.intellij.ide.ui.search.SearchableOptionProcessor;
import org.jetbrains.annotations.NotNull;

/**
 * T-SettingsSearchA：把插件设置项喂进 IDE 自带设置搜索索引（Settings 对话框左上搜索框）。
 *
 * <h2>为什么需要运行期 Contributor</h2>
 *
 * <p>平台设置搜索读两类来源：插件 jar 内的 {@code search/searchableOptions.xml}（构建期由
 * {@code buildSearchableOptions} fork IDE 遍历 UI 生成），以及运行期 {@code search.optionContributor}
 * EP 收集（{@code SearchableOptionsRegistrarImpl.doInitialize} 逐个调 {@code contribute(processor)}，
 * 243 字节码实证）。本仓 {@code build.gradle.kts} 关着 {@code buildSearchableOptions}（Windows 中文
 * 环境跑该任务报 {@code "Locale must be default"}，注释有实证），故走 EP 这条路——不动构建，
 * 也就不会碰到 locale 失败。</p>
 *
 * <h2>为什么不需要改 9 个 Configurable</h2>
 *
 * <p>查询侧 {@code filterById} 对每个候选判 {@code instanceof SearchableConfigurable} 再比 {@code getId()}；
 * 本仓设置页全用 {@code instance=} 注册，平台把实例包进 {@code ConfigurableWrapper}，而该类<b>本身
 * 实现 {@code SearchableConfigurable}</b>，{@code getId()} 优先返回 {@code ConfigurableEP.id}（243 字节码实证：
 * {@code myEp.id != null ? myEp.id : ...}）。故 plugin.xml 里已声明的 id 天然满足门二，无需给
 * {@code SectionConfigurable} 等类补 {@code getId()}。</p>
 *
 * <h2>参数语义（对照 243 字节码 LocalVariableTable）</h2>
 *
 * <p>{@code addOptions(text, path, hit, configurableId, configurableDisplayName, applyStemming)}：</p>
 * <ul>
 * <li>{@code text}＝被索引文本（用户搜的词来自这里）；</li>
 * <li>{@code path}＝设置路径字符串，可为 null（平台自带的 {@code PluginSearchableOptionContributor}
 * 就传 null）；本实现传 null，命中后由 configurableId 定位页面；</li>
 * <li>{@code hit}＝命中时展示给用户的说明文本；</li>
 * <li>{@code configurableId}＝EP id，平台用它把命中映射回设置树节点（{@code filterById} 比对 {@code getId()}）；</li>
 * <li>{@code configurableDisplayName}＝搜索结果里显示的所属页面名；</li>
 * <li>{@code applyStemming}＝是否做词干化（false＝保留原词，中文无词干化需求）。</li>
 * </ul>
 *
 * <p>数据源是 {@link PluginSettingsFolderConfigurable#ideSearchEntries()}——与插件设置页内搜索
 * 同一份清单，两套搜索天然同步。</p>
 *
 * <p>移植注记：master 侧为 Kotlin class（隐式 final），EP 实现类按惯例显式 {@code final}。</p>
 */
public final class PluginSettingsSearchableOptionContributor extends SearchableOptionContributor {

    @Override
    public void processOptions(@NotNull SearchableOptionProcessor processor) {
        for (PluginSettingsFolderConfigurable.IdeSearchEntry entry : PluginSettingsFolderConfigurable.ideSearchEntries()) {
            // addOptions 形参 path 可 null（平台自带 Contributor 同形），本实现恒传 null
            processor.addOptions(
                entry.label(),
                null,
                entry.tooltip(),
                entry.configurableId(),
                entry.pageDisplayName(),
                false
            );
        }
    }
}
