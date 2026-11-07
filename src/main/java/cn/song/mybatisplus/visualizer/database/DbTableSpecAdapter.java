package cn.song.mybatisplus.visualizer.database;

import cn.song.mybatisplus.visualizer.generator.CrudColumn;
import cn.song.mybatisplus.visualizer.generator.CrudTableSpec;
import cn.song.mybatisplus.visualizer.generator.DbTypeQuoteHint;
import cn.song.mybatisplus.visualizer.psi.PsiDialect;
import cn.song.mybatisplus.visualizer.sql.IdentifierCaseSource;
import com.intellij.database.model.DasColumn;
import com.intellij.database.model.DasObject;
import com.intellij.database.model.DasTable;
import com.intellij.database.model.DasTableKey;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.psi.DbTable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * DbTable → {@link CrudTableSpec} 适配器（T-CrudSql-03）。
 *
 * <p>自省元数据来源（2024.3.7.1 jar javap 实证）：
 * <ul>
 *  <li>列：{@code DasObject.getDasChildren(ObjectKind.COLUMN)} + {@code filterIsInstance<DasColumn>}（T-MarketInternal：
 *      原 {@code getDbChildren(Class, kind)} scheduled-for-removal，其方法体即 {@code getDasChildren(kind).filter(clazz)}）；</li>
 *  <li>主键：{@code getDasChildren(ObjectKind.KEY)} → {@code filterIsInstance<DasTableKey>} → {@code isPrimary()} →
 *      {@code getColumnsRef().names()}（模型类为 DasTableKey，无 DasKey 类）；</li>
 *  <li>自增：{@code DasTable.getColumnAttrs(col)} 含 {@link DasColumn.Attribute#AUTO_GENERATED}；</li>
 *  <li>类型/注释：{@code DasTypedObject.getDasType().toDataType().typeName}（T-MarketInternal：原
 *      {@code getDataType()} scheduled-for-removal，其方法体即 {@code getDasType().toDataType()}；类型名原文，
 *      引号提示走 {@link DbTypeQuoteHint} 启发）、{@code DasObject.getComment()}。</li>
 * </ul>
 *
 * <p>本类与 {@link DbTable} 类引用只经 database-optional.xml 的 action 到达——database 插件不在场时
 * 不实例化（Community 优雅降级，与 JoinVirtualFkProvider 同一加载面）；全部直引编译期类型，
 * 无反射入口，跨版本兼容由 verifyPlugin 三版本门禁兜底。
 */
public final class DbTableSpecAdapter {

    /** Kotlin object 单例等价。 */
    public static final DbTableSpecAdapter INSTANCE = new DbTableSpecAdapter();

    private DbTableSpecAdapter() {
    }

    /** 自省表 → 统一表描述。逻辑删除/乐观锁在数据库侧无元数据，恒 null。 */
    public static CrudTableSpec toSpec(DbTable table) {
        DasTable das = table.getDasObject();
        Set<String> primaryKeys = new LinkedHashSet<>();
        for (DasObject child : das.getDasChildren(ObjectKind.KEY)) {
            if (!(child instanceof DasTableKey)) continue;
            DasTableKey key = (DasTableKey) child;
            if (key.isPrimary()) {
                // MultiRef.names() 返回 Iterable<String>（javap 实证），逐个加入
                for (String name : key.getColumnsRef().names()) {
                    primaryKeys.add(name);
                }
            }
        }
        List<CrudColumn> columns = new ArrayList<>();
        for (DasObject child : das.getDasChildren(ObjectKind.COLUMN)) {
            if (!(child instanceof DasColumn)) continue;
            DasColumn col = (DasColumn) child;
            String comment = col.getComment();
            columns.add(new CrudColumn(
                    col.getName(),
                    comment != null && !comment.isBlank() ? comment : null,
                    primaryKeys.contains(col.getName()),
                    das.getColumnAttrs(col).contains(DasColumn.Attribute.AUTO_GENERATED),
                    DbTypeQuoteHint.decide(col.getDasType().toDataType().typeName),
                    // T-DateLiteralBackfill：日期族列标记（A4a 跨层依赖——isTemporal 与 CrudColumn
                    // temporal 尾参属 A4c generator×3 增量，落位后本行转绿；照 master 终态先译调用方）
                    DbTypeQuoteHint.isTemporal(col.getDasType().toDataType().typeName)));
        }
        return new CrudTableSpec(
                das.getName(),
                columns,
                null,
                null,
                // 2026-09-23 真机走查发现并修：此处原为 `currentDialect()`（**不传 project**），
                // 而 `DialectRegistry.effective(null)` 在 auto 分支里因 `project ?: return byName(manualName)`
                // 直接落到全局设置 → `PluginSettings.DEFAULT_DIALECT = "MySQL"` ⇒ **无论表属于哪个库，
                // 一律按 MySQL 渲染**。真机实证（本机 SQL Server 2022 容器）：右键 `dbo.[user]` 生成 CRUD SQL
                // 得到 `INSERT INTO `user` (…)`（反引号），而该形态在 SQL Server 上直接
                // `Msg 102 … Incorrect syntax near '`'`；同时因方言不是 SqlServerDialect，
                // 勾选自增列时也不会过 `renderIdentityWrapper`（O-52 的 CRUD 侧判据因此在真机上不成立）。
                // 修法＝把表所属项目传进解析链，与插件其余路径同源（项目数据源 URL 推断方言）。
                PsiDialect.currentDialect(table.getProject()),
                // O-42：本类是目录来源的**唯一**入口——`das.name` / `col.name` 直取自省元数据，
                // 即字典里的真实大小写（真库实测：`all_tables.table_name` 对带引号创建的表返回
                // 小写 `issue_new`，未归一）。标记后按方言折叠方向族引用（O-51）：
                // UPPER 族（Oracle）「含小写字母即加引号」消除裸写折叠为大写导致的 ORA-00942；
                // LOWER 族（PG / KingBase）「含大写字母即加引号」消除裸写折叠为小写导致的
                // `relation ... does not exist`。
                IdentifierCaseSource.CATALOG);
    }
}
