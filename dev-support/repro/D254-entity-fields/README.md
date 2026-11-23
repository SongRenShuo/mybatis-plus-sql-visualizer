# D254 实体字段识别复现夹具（T-EntityFieldStaticHidden）

一个最小 Maven 工程，专门复现用户反馈的两个现象：

1. **静态字段被当成表列**——`serialVersionUID`、`public static final String TABLE_NAME`、`private static final String LOG_TAG`、子类 `VERSION_TAG`；
2. **父子类同名字段识别错**——`UserEntity` 用字段隐藏（Java 字段同名不算重写）覆盖 `BaseEntity` 的 `createTime` / `version` / `deleted`，注解与列名该按子类那份算。

夹具与插件单测（`EntityFieldCollectorTest` / `EntityResolverInheritanceFieldTest` / `JpaEntityResolverInheritanceFieldTest` / `EntityResolverInheritanceCacheHeavyTest`）覆盖同一组语义，真机复验用它。

## 怎么打开

用 IDE 直接打开本目录（`dev-support/repro/D254-entity-fields`）作为一个独立的 Maven 工程，等依赖下载完（只需要 `mybatis-plus-core:3.5.17`，与插件内置版本一致）。

不连数据库也能验证——插件预览只做 PSI 解析，不需要数据源。想连库执行再跑一遍 `src/main/resources/schema.sql`（H2/MySQL 均可，表结构与本次语义对齐）。

## 五个触发点与预期

在 `UserService` 里把光标放进方法体，用插件的预览入口（gutter 图标 / `Alt+Enter` / 右键菜单）。

### 1. `listAll()` → SELECT 全列展开

- **新版（D254 之后）**：列名依次为 `id, created_at, version, deleted, username, nickname`
  （`id` 是 `@TableId` 置顶；`version` / `deleted` 因为子类重写那份没有注解，只是普通业务列）。
  关键判据：
  - `created_at` 只出现**一次**，且**没有** `create_time`；
  - **没有** `serial_version_uid` / `table_name` / `log_tag` / `version_tag`。
- **旧版现象**：`serial_version_uid`、`table_name`、`log_tag`、`version_tag` 都会变成列；`create_time` 与 `created_at` 各出现一次。

### 2. `create()` → INSERT 列清单 / 批量面板

- **新版**：列集合与触发 1 一致，无静态列、`created_at` 不重复。
- **旧版**：INSERT 里混入 `table_name`、`log_tag`、`version_tag` 等不存在的列，参数面板还会多出同名重复行。

### 3. `findByUsername()` → Wrapper 预览

- **新版**：条件里**没有** `deleted = 0`（子类重写字段不带 `@TableLogic`，真机同样不追加）。
- **旧版**：因为父类字段排在前面，`@TableLogic` 被识别成生效，自动追加 `AND deleted = 0`。

### 4. `listByXml()` → XML 语句预览 / 补全

- **新版**：`resultMap` 的 `column` / `property` 候选与 `#{item.|}` 的属性候选里**没有**静态字段
  （`serialVersionUID`、`TABLE_NAME`、`LOG_TAG`、`VERSION_TAG`）。
- **旧版**：静态字段会出现在候选里。
- 顺带可验：`@java.lang.Integer@MAX_VALUE` 这类 `@类@静态成员` 补全**仍然可用**（那是 MyBatis 的静态引用语法，故意保留）。

### 5. `update()` → UPDATE 预览

- **新版**：不追加 `version = version + 1` / `AND version = ?`（子类重写 `version` 后不带 `@Version`）；`deleted` 也不参与逻辑删除条件。
- **旧版**：两条乐观锁片段都会出现。

## 反向对照（确认「不是简单地把父类字段全丢掉」）

临时把 `UserEntity` 里 `createTime` / `version` / `deleted` 三个重写字段删掉（或改名），重新预览：

- `createTime` 应恢复父类语义——列名 `create_time`，并带 `fill=INSERT`（审计列，出现在批量面板的 fill 模拟区）；
- `version` 应恢复乐观锁语义（UPDATE 追加 `version + 1` 与 `AND version = ?`）；
- `deleted` 应恢复逻辑删除语义（SELECT/UPDATE 追加 `deleted = 0`）。

这一对照专门防「把父类字段整体丢弃」式的误修。

## 一套最快的判据

打开 `UserService.listAll()` 预览，把生成的 SQL 复制出来，检查三件事即可：

1. `grep -c created_at` = 1，`grep -c create_time` = 0；
2. 不存在 `table_name` / `log_tag` / `version_tag` / `serial_version_uid`；
3. `findByUsername()` 的 WHERE 里没有 `deleted = 0`。
