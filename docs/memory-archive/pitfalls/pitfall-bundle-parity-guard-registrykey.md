---
name: pitfall-bundle-parity-guard-registrykey
description: BundleParityGuardTest 把 plugin.xml 里所有 key=/text=/description= 中带点的值当 bundle 键——新增 <registryKey> 必撞；已在守卫里整元素排除该族
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4043a4bb-4569-4ef9-8e28-84e10cd22b20
---

2026-11-13 CI `fast (2)` 唯一红点：`BundleParityGuardTest.pluginXml与optionalXml引用的key必须存在于bundle`（`i18n/BundleParityGuardTest.kt:140`）。触发物是 T-StartupReadLockFreeze 新加的：

```xml
<registryKey key="sqlvis.mbg.prefetch" defaultValue="true" description="Prefetch MBG … previews keep working."/>
```

守卫的规则是「`(key|text|description)="…"` 且值**含点** ⇒ 必须是 bundle 键」，而：

- `registryKey` 的 `key` 按平台约定是 `a.b.c` 形态的**注册表标识符**，不是 bundle 键；
- 它的 `description` 是 Registry UI 的纯英文说明，平台**不做** `%key%` 替换。

**已落地的修法**（`2f56f8eb7`）：扫描前整元素剔除 `<registryKey …/>`，注释写明理由与首用来源，不扩面。定向 `BundleParityGuardTest` 5/0/0/0、`GuardSelfTestCoverageMetaGuardTest` 8/0/0/0。

**How to apply**：以后给 plugin.xml 加 `key=`/`text=`/`description=` 且值里带句点（含英文句号）时，要么值确实是双语 bundle 键，要么像 registryKey 这样属于「属性不是 bundle 引用」的族——加族就同批给守卫加一条有注释的排除，别让 CI 反过来教。写 registryKey 的 `description` 用纯英文短句即可。

关联：[[project-startup-read-lock-freeze-20261113]]、[[project-i18n]]。
