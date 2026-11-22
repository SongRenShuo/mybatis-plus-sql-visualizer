---
name: pitfall-tier-probe-via-fallback-read
description: 用「分层读取的返回值」反推「命中哪一层」恒为真——因为分层读取本身就会回落；本仓实测把「项目级兜底」冒称成「模块级命中」（20261024 沙盒验收抓出）。正确判据＝直接读该层原键是否非空／改让读取函数连「命中层级」一起返回
metadata:
  node_type: memory
  type: project
  originSessionId: session_b380d49669e298cff6e310c72afca927
---

# 「读出来有值」不能证明「命中的是本层」

## 现场（2026-10-24，T-ModuleScopedLastUsed W3）

指示行灰字要在「模块级命中」与「项目级兜底」之间换文案限定词。首版判据：

```kotlin
val scoped = selector.rememberedDataSourceName(proj, moduleKey)   // 模块级为空时会回落项目级
val projectLevel = selector.rememberedDataSourceName(proj, null)
scoped != null && scoped == projectLevel       // ← 恒为真
```

因为 `lastUsedRunner(proj, moduleKey)` **在模块级为空时会回落项目级**，两个返回值必然相等
（写模块级时也会写项目级，值本就相同）。于是「纯项目级兜底」被误判成「模块级命中」。

**真机取证**：demo 工程 `.idea/workspace.xml` 里只有 `mpsql.visualizer.last.runner`，
**没有任何** `…runner.<moduleKey>` 键，灰字却显示「数据源（**本模块**上次使用）」。

## 根因抽象

**分层/多级读取函数把「回落」藏进了返回值里，返回值只表达「最终拿到了什么」，
不表达「在第几层拿到的」。** 任何用返回值反推层级的判据都恒真。

同族场景（都要当心）：
- 多级配置（项目 > 用户 > 默认）—— 读出来有值 ≠ 用的是项目级那份
- 多级缓存（本地 > 远端）—— 命中 ≠ 命中的是本地
- 回退链（首选源 > 兜底源）—— 取到源 ≠ 首选源可用

## 修法（两种，按能否改读取函数选）

1. **能改读取函数**（更好）：让读取结果连命中层级一起返回，例如
   `LastUsedMemory(value, tier)` 或 `resolve(...): Resolution(index, fellBack: Boolean)`。
   调用方不需要二次猜测。
2. **不能改读取函数**（本例选这条，因读取函数是既有公开面）：**直接读该层的原键**：

```kotlin
val keys = DataSourceSelector.LastUsedKeys
val id = pc.getValue(keys.dataSourceIdKey(moduleKey))
val name = pc.getValue(keys.displayNameKey(moduleKey))
!id.isNullOrBlank() || !name.isNullOrBlank()
```

键构造器抽成纯函数（`LastUsedKeys`）后，这个判据与生产读写走同一套键，不会漂移。

## 护栏

两条成对用例：`仅有项目级记忆时不得冒称本模块` / `有模块级记忆时显示本模块文案`。
反向验证：把判据还原成缺陷版 → **恰 1 红**（已实测）。注意首版反向验证还踩了个坑：
测试夹具 `plantLastUsedMemoryForTest` 只写项目级，但上一条用例写入的**模块级键串到本条**
（`setUp/tearDown` 只清项目级），导致 `isModule` 假真——补 `forgetAllLastUsed` 后才干净。
详见 [[pitfall-fake-green-test-assertions]] ㊹。

相关：[[project-module-scoped-last-used-and-log-bgt]]（本坑的宿主任务）。
