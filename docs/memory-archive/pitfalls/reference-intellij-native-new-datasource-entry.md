---
name: reference-intellij-native-new-datasource-entry
description: 从插件里打开 IDE 原生「新建数据源」流的平台入口（javap 243 database-plugin.jar 实证）——驱动组工厂 + DataSourceManagerDialog 调用链，附带两条枚举/取名事实
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_216d62d0-f936-4e25-8d90-a3355e3af177
---

# 插件里调「IDE 原生新建数据源」的平台入口（243 字节码实证）

## 取字节码的方法（复用）

243 完整发行版在 `F:/gradle-home/caches/9.7.0/transforms/<hash>/transformed/ideaIU-2024.3.7.1-win`，Database 插件 jar 在 `plugins/DatabaseTools/lib/{database-plugin,database-openapi,jdbc-console}.jar`（`LocalDataSource`/`DataSourceStorage`/`DbImplUtil` 都在 `database-plugin.jar`）。

两个坑：**必须 `cd` 到 distro 目录**再用相对路径的 classpath，并且 `MSYS2_ARG_CONV_EXCL='*'`——否则 Git Bash 会把 `;` 分隔的 classpath 当路径转换，`javap` 退出 0 但零输出；javap 用 `C:/Users/USER/.jdks/corretto-21.0.12.1/bin/javap.exe`。本条只对 243 断言过，262/263 未单独验证。

## 新建数据源的调用链

```
DatabaseViewActions.getCreateDataSourceAction(
    DataSourceManager<?> manager, Project project, String title, boolean includeSeparator,
    Consumer<DbDataSource> consumer)                                    // 静态工厂
  → manager.getCreateDataSourceAction(consumer')                        // DataSourceManager 抽象方法
      // LocalDataSourceManager 实现：new LocalDataSourceManager$NewDataSourceActionGroup(驱动子项).setPopup(true)
      //   group 模板图标 = DatabaseIcons.Dbms，文案 = DatabaseBundle "default.datasource.name"
      //   子项（LocalDataSourceManager$2）actionPerformed → driver.createDataSource(name) → consumer'
      //   getCreateDataSourceActions 会按 DatabaseDriversLRU 排序驱动
  → 选中驱动后 onDataSourceCreated(DbPsiFacade, DataSourceManager, RawDataSource, Consumer)
      → ((AbstractDataSource) raw).setGroupName(title)
      → DbPsiFacadeImpl.createDataSourceWrapperElement(raw, manager)
      → DataSourceManagerDialog.showDialog(facade, dsWrapper, null, consumer, runnable)
```

即：**选驱动 → 直接弹原生「数据源和驱动程序」属性对话框**，与数据库工具窗「+」同一条菜单、同一个对话框。`includeSeparator=true` 时工厂会把驱动组再包进一个带 `Separator` 的 `DefaultActionGroup`。

## 相关签名（同一次 javap）

- `DataSourceManager`（抽象类）：`getManagers(Project)`、`byDataSource(Project, Class<? extends T>)`、`byDataSource(Project, T)`、`getCreateDataSourceAction(Consumer<? super T>)`、`createEmpty()`、`getDataSources()`。
- `LocalDataSourceManager extends AbstractDataSourceManager extends BasicDataSourceManager extends DataSourceManager<LocalDataSource>`，且 `BasicDataSourceManager implements DataSourceManagerUi<T>`（`createDataSourceEditor(T)`）；`LocalDataSourceManager.getInstance(Project)`，`createEmpty()` = `new LocalDataSource()`。
- `DataSourceManagerDialog`（`view/ui`）：四个 `showDialog(...)` 重载，最常用 `showDialog(Project, Object selected, DatabaseCredentials)`（第二个参数就是对话框里被选中的对象）。
- `DbDataSource` 是接口（`com.intellij.database.psi`），有 `getDelegate()`/`getDelegateDataSource(): RawDataSource`（桥接还剩一个 `default` 返回 Object 的重载）。

## 两条枚举/取名事实（同一批 javap，容易踩）

1. **`DbImplUtil.getAllDataSourcesForExec(PsiFile)` 返回 `JBIterable<DbDataSource>`**——是 PSI 层对象，**不是** `LocalDataSource`。把它的元素当 `LocalDataSource` 反射取名必抛 `IllegalArgumentException`，被 `runCatching` 吞掉就变成字面量 `"Unknown"` 混进 UI 列表（成因与修法见 [[project-ds-list-add-source-20261111]]）。
2. `DataSourceStorage.getDataSources()` / `getOwnDataSources()` 返回 `List<LocalDataSource>`；`getDataSourceById(String)` 也在该类上（`getStorage(Project)` 取实例）。

## 落地纪律

- 整链反射（`Class.forName` + `getMethod` 拼参数类型），Community 无 Database 插件时静默跳过而不是抛错——本仓既有约定同 `DataSourceSettings`/`ExecDialectRouting`。
- `ActionGroup` 默认的 `actionPerformed` **什么都不弹**（本仓 `ChooseDataSourceTakeover` 已实测两轮），要弹驱动列表得用 `JBPopupFactory.createActionGroupPopup` 或以子菜单形态挂到自己的 popup 上；这条也是可能的降级触发点。
