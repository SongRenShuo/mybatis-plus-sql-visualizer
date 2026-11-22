---
name: pitfall-batch-source-rewrite-regex-scope
description: 用脚本批量改源码时正则的「作用域」会连坐三样东西——字符串字面量、静态成员访问、以及别人未提交的改动；另外全限定名内联本身会绕过按简单类名匹配的静态分析规则
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fd128d0e-d320-4865-9871-208872610cd6
---

2026-11-02 openget 仓把 18 个文件 / 190 处全限定名内联（`new java.util.ArrayList<>()`、`org.springframework.test.web.servlet.result.MockMvcResultMatchers.*`）批量归一化成 import + 简单类名，同一个脚本连踩三个坑，每个都「看起来改对了」。

## 坑一：正则命中字符串字面量，语义直接反转

`Class.forName("cn.jvtools.openget.common.ContractNaming")` 被改成 `Class.forName("ContractNaming")`——而那条断言的本意正是「这个类必须不存在」。改完编译照样过、字符串里的类名没人看，只有跑测试才会发现断言逻辑反了。

**判据**：改完必须 `git diff` 抽样看「字符串里有没有被改过」，不能只看脚本自报的替换数。
**修法**：先把 `"(?:[^"\\]|\\.)*"` 切成段，只对段外的代码做替换；行尾 `//` 注释同理要剥掉。

## 坑二：静态成员访问被当成类名

`java.util.concurrent.TimeUnit.MILLISECONDS` 的末段也是「大写开头」，正则把它整串当类名，生成出 `import java.util.concurrent.TimeUnit.MILLISECONDS;` 这种非法导入（javac 报「符号: 类 MILLISECONDS / 位置: 类 java.util.concurrent.TimeUnit」）。

**修法**：末段匹配全大写（`[A-Z][A-Z0-9_]+`）**且**前一段是驼峰类型（`[A-Z][a-z]…`）时，按「类.常量」处理——import 前一段、保留 `Class.MEMBER`。反过来末段全大写但前一段是小写包名（`java.net.URL`）就仍是类，直接 import。

## 坑三：`git checkout --` 连带丢掉别人未提交的改动

为回退脚本污染，对「脚本动过的文件清单」整批 `git checkout --`，把同批次子代理**未提交**的接线改动（5 个文件）一起冲掉了。子代理连续掉线时这些改动只存在于工作树，没有 reflog 可救。

**修法**：回退前列清每个文件是谁的；只回退自己改的。已经丢了就从**测试用例反推**——本轮 `PhoneLoginIntegrationTest` 的 10 条断言就是完整行为规格，照着重写接线后一次全绿。

## 附带发现：全限定名写法会绕过按简单类名匹配的静态分析

- p3c 的 `CollectionInitShouldAssignCapacityRule`（集合初始化要指定容量）**只匹配 `new HashMap<>()`**，写 `new java.util.HashMap<>()` 就能躲过去——归一化之后 PMD 才红。
- 契约命名守卫扫 `.set("snake_case")` 也一样：它按字面量扫，全限定写法不影响命中，但**豁免清单**是按文件路径的——`DyOpenApiClient` 拼的是抖音上游协议（`anonymous_code` 是对方字段名），漏登记就一直红。

**结论**：批量归一化不只是「好读」，它会让原本被写法掩盖的静态规则重新生效；改完必须跑全量门禁（含 PMD/Checkstyle/源码扫描守卫），只看编译绿等于没验。

相关：[[pitfall-script-rewrite-truncates-file]]（str.split 匹配错位置）、[[pitfall-fffd-repair-positional]]（中文写入乱码的修法）、[[project-openget-multiclient]]。
