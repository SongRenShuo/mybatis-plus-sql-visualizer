---
name: pitfall-idea-build-skips-maven-resource-filtering
description: IDEA 自己的 Build 不走 maven-resources-plugin 过滤——application.yml 里的
  @profiles.active@ 会被原样拷进 target/classes，裸 @ 是 YAML 非法起始字符，应用直接起不来；占位符必须带引号
metadata:
  node_type: memory
  type: project
  originSessionId: session_ed208fad1e5bbee65786fb80020e8654
---

# IDEA Build 不做 Maven 资源过滤 → yml 占位符把应用打死

2026-10-30 用户从 IDEA 启动 `mybatis-plus-join-demo` 直接失败：

```
org.yaml.snakeyaml.scanner.ScannerException: found character '@' that cannot start any token
 in 'reader', line 9, column 13:  active: @profiles.active@
```

## 根因

demo 仓的 Maven profile 机制（`<profiles>` 里 mysql/dm/pg/gaussdb 各设 `profiles.active` 属性 + `application.yml` 写 `active: @profiles.active@`，靠 spring-boot-starter-parent 的 `@..@` 资源过滤替换）**只在走 Maven 构建时生效**。IDEA 自己的 Build 不调 `maven-resources-plugin`，把占位符原样拷进 `target/classes`（实测 `target/classes/application.yml` 里就是裸 `@profiles.active@`，mtime 与 IDEA 构建时刻一致）。裸 `@` 是 YAML 非法起始字符 ⇒ Spring 解析阶段就崩，跟数据源无关。

## 修法

占位符加双引号（两个模块都改，demo 仓提交 `146800e`）：

```yaml
spring:
  profiles:
    active: "@profiles.active@"
```

- Maven 过滤后仍是 `active: "mysql"` / `"gaussdb"` / `"dm"`，行为不变
- 未过滤时退化成合法的 YAML 字符串（一个不存在的 profile 名），应用照常以 `application.yml` 的基础配置启动，不再崩

## 从 IDEA 跑非默认 profile 的两条可靠路径

Maven 面板那个勾选框**只对 Maven 构建生效**，对 IDEA 自己的 Build 无效：

1. `mvnd -P<profile> spring-boot:run`
2. Run Configuration 的「Active profiles」里直接填 profile 名（不依赖资源过滤）

## 验证手法

把未过滤的源文件拷进 `target/classes` 模拟 IDEA Build，再 `java -cp "target/classes;$(cat target/cp.txt)" <MainClass>` 直启（`cp.txt` 由 `mvnd dependency:build-classpath -Dmdep.outputFile=target/cp.txt` 生成）——比等 IDE 复现快得多。

## 反向陷阱：过滤后的旧值会留在 target/classes（2026-11-01 实证）

加引号后不会再崩，但**会静默继承上一次 Maven 构建的 profile**：S2-8 真机批里 demo 应用被当成「只有 MySQL 数据源」，实际 `target/classes/application.yml` 里 `profiles.active` 是 `"gaussdb"`（mtime 11-01 04:13，来自前几天的 GaussDB 用例），应用真连 `103.36.220.245:25882/xc3_test`。于是捕获条目角标 `[GaussDB]` 被误判成插件标注错误，查了半天源码（角标取 `JdbcUrlDialects.badgeName(event.url)`，只按 `jdbc:<子协议>` 精确查表，`jdbc:mysql` 根本映不出 GaussDB）。

**判据**：判「应用连的是哪个库」只看 `target/classes/application.yml` 的**实测值**，不看 `src/main/resources` 的 base 段（源码里只有占位符）。同一屏上「IDE 侧对 Java 文件映射的 SQL 方言」与「应用侧真实连接」是两个事实，不能互相当反证。

相关：[[reference-gaussdb-demo-fixture-pg-ddl]]（这套 profile 就是为高斯加的）
