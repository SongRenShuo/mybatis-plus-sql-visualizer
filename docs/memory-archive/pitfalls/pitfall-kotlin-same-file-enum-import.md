---
name: pitfall-kotlin-same-file-enum-import
description: Kotlin 同文件枚举成员 import 不可靠——`import SomeEnum.MEMBER` 在枚举声明于同一文件时编译仍报 Unresolved reference（使用点报错、import 行本身不报）；解法=删 import、使用点全限定 `SomeEnum.MEMBER`
metadata:
  node_type: memory
  type: project
  originSessionId: sess_17670dd0-19a6-4d50-8202-a1f87d1b8e5a
---

2026-10-06 T-SqlToJavaPromote M1 语料库实测：`ReverseRoundTripCorpus.kt` 内声明 `enum class RoundTripExpectation` 并在同文件 object 里裸用成员（`ROUND_TRIP`/`PARSE_OK`…），补 `import RoundTripExpectation.PARSE_OK` 等四行后 `compileTestKotlin` **仍报全部使用点 `Unresolved reference 'PARSE_OK'`**（import 行自身不报错，极具迷惑性——会误判成「import 没写对」反复改 import）。

**解法（一次过）**：删掉 import 行，使用点全部改全限定 `RoundTripExpectation.XXX`。

**Why:** 同文件顶层枚举的成员 import 在本仓 Kotlin 编译器版本下解析失败，且报错落在使用点而非 import 行，容易烧轮次在 import 语法上打转。

**How to apply:** 语料/常量表文件里要大量引用枚举成员时，直接写全限定名（或把枚举拆到独立文件）；看到「import 在、使用点 Unresolved」先删 import 改全限定，别改 import 写法。
