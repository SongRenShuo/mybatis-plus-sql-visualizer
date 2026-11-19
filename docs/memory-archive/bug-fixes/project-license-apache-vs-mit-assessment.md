---
name: project-license-apache-vs-mit-assessment
description: 20261021 用户问「Apache-2.0 是否适合改 MIT」的评估结论——可改（全史单一人类作者）但建议维持
  Apache-2.0；连带查出两个既有合规缺口（分发包不带 LICENSE/NOTICE、JMH 及其传递依赖泄漏进运行期 lib）与 42 个
  JetBrains 图标须开例外的硬约束；两处缺口已在当轮实施（提交 f37bc1b7，未 push）
metadata:
  node_type: memory
  type: project
  originSessionId: session_a065f94b001a531b4775c299a0892b59
---

2026-10-21 用户问「当前项目是 Apache-2.0 是否适合改成 MIT」。结论：**法律上可改，收益不足，建议维持 Apache-2.0**。用户随后定案「**维持 Apache-2.0，补缺口**」并在当轮完成实施（提交 `f37bc1b7`，master，**未 push**）。两处合规缺口的根因与修法见下方「缺口实施」节。

## 现状事实（本次实测，引用前仍建议复核）

- `LICENSE` 是 Apache-2.0 全文，2026-08-13 随 `4d65a6ee` 加入，此后只被动过行尾重规范化（`0fc6db13`/`28103a14`/`60aeea61`）。
- **全史 3902 笔提交只有 SongRenShuo 一个人类作者**；trailer 里的 `Co-Authored-By: Claude / LongCat / ZCode Agent` 是工具署名，不是权利主体；无外部贡献被合入、CHANGELOG 无外部贡献者。⇒ 唯一权利人可单方面换许可，只需「向前不溯及」（已发行版本永远是 Apache-2.0，新 tag 起改）。
- **零源文件带许可头**（`Licensed under the Apache License` 在 src/sub-projects 下 0 命中）⇒ 真要换没有批量改文件头的工程量。
- 许可字样落点四处：`README.md:243`、`CONTRIBUTING.md:7`、`docs/发行说明/Marketplace发布流程-说明文档.md`（38/232/381 行）、`docs/发行说明/T-ReleaseChecklist-发行检查清单.md:62`；另加 Marketplace License 字段（开源必须填源码链接）、Gitee 仓库元数据、GitHub 镜像、官网关于页。

## 换成 MIT 会撞上的硬约束

1. **42 个 JetBrains 图标**：`src/main/resources` 下 53 个 SVG 中 42 个文件头写着 `Copyright 2000-2022 JetBrains s.r.o. … Apache 2.0 license`（即 `icons/toolbar/` 那批克隆的 expui，见 [[project-toolbar-icon-semantics]]）。全仓同许可时无感，改 MIT 后这些仍只能是 Apache-2.0 ⇒ 要么 LICENSE 留例外段+第三方位说明，要么换成自绘。
2. **分发包内嵌他人代码**：实测 `build/distributions/mybatis-plus-sql-visualizer-2026.10.16.zip` 顶层 5 个 jar——jsqlparser 5.3（POM 为 **LGPL-2.1 / Apache-2.0 双许可**，本仓按 Apache 用）、commons-math3 3.6.1（Apache-2.0，自带 NOTICE）、jmh-core 1.37（Apache-2.0）、jopt-simple 5.0.4（MIT）、主插件 jar；主 jar 内嵌 `agent/mps-sql-agent.jar`，内含 relocate 后的 ASM（BSD-3-Clause）但**只有 MANIFEST，无 ASM 版权与许可文本**。

## 两个既有合规缺口（与换不换许可无关，都是现在就该修的）

1. **分发包里没有 LICENSE**：zip 顶层只有 5 个 jar，主 jar 内也无 LICENSE/NOTICE。Apache §4(a) 要求向接收者提供许可副本，MIT 同样要求副本内含版权与许可声明。修法＝把 LICENSE 打进插件 zip + 补 `THIRD-PARTY-NOTICES`（JetBrains 图标、jsqlparser、ASM）。
2. **JMH 及其传递依赖泄漏进运行期 lib**：构建脚本里**查不到任何 jmh 声明**（`grep -i jmh` 在 build.gradle.kts / buildSrc / libs.versions.toml 全空），但 `jmh-core`+`jopt-simple`+`commons-math3` 三个 jar（约 2.8MB）稳定出现在 `lib/` 下。`docs/验收文档/20260920-…-验收文档.md:179` 与 `20260920-IDE启动开销AB实测报告.md:165` 早已标过「建议单独确认是否为打包泄漏」，到 10 月的包里仍在——清掉顺带给 zip 瘦身。

## 缺口实施（20261021，提交 f37bc1b7，master 未 push）

用户拍板「维持 Apache-2.0，补缺口；单独确认 JMH 是否为打包泄漏；LICENSE 打进 zip + 加 THIRD-PARTY-NOTICES」。已全部落地：

- **JMH 泄漏根因确认并修复**：`grep -i jmh` 构建脚本查无声明（此前怀疑是脚本残留）→ 实测泄漏源是 **JSqlParser 5.3 的 POM 把 `org.openjdk.jmh:jmh-core` 声明成编译期传递依赖**（仅其基准测试用——jar 内逐类核对 **0** 个类引用 `org/openjdk/jmh`）。修法＝`build.gradle.kts` 对 jsqlparser 显式 `exclude(group = "org.openjdk.jmh", module = "jmh-core")`。zip 体积 8,969,163 → 7,105,380 字节（−20.8%）。**这是「传递依赖拖入基准/测试库」的打包泄漏形态，判据=解析 runtimeClasspath 看传递链 + jar 内逐类核对是否真引用。**
- **新增根目录 `THIRD-PARTY-NOTICES.md`**：覆盖随包全部第三方——JetBrains expui 图标 42 枚（Apache-2.0，文件头保留原始版权）、JSqlParser 5.3（LGPL-2.1/Apache-2.0 双许可按 Apache 用）、ASM 9.4（BSD-3，附许可全文，relocate 进 agent jar）。附「每次增减第三方组件同步更新」维护约定。
- **LICENSE 与 NOTICES 双路打进分发**：① 插件主 jar 的 `META-INF/`（`processResources` 加 `into("META-INF")`，单点来源避免副本漂移）；② buildPlugin zip 的插件目录同级。
- **🆕 打包坑（实测）**：`BuildPluginTask`（Zip 子类）的**根 dest 已是 `archiveBaseName`（插件目录名）**，若再 `into(pluginDirName)` 会把文件落进 `插件目录/插件目录/` 双层嵌套（首版实测踩中）。正解＝不写 `into`，`from(...)` 直接继承任务根 dest。
- **守卫 `convention/DistributionComplianceGuardTest`**：3 条内联断言（根目录 LICENSE/NOTICES 在场、NOTICES 覆盖 JetBrains/JSqlParser/ASM/INRIA 关键词、build.gradle.kts 含 jmh-core exclude），零平台引用进 testFast 快桶，已登记元守卫。**反向验证＝临时摘 exclude → 守卫恰 1 红（第 41 行断言），恢复复绿。**
- 读数：守卫 3/0/0/0、元守卫 8/0/0/0；jsqlparser 排除后实测定向 SqlAstParserTest 6/0/0/0 + RollbackSqlGeneratorTest 49/0/0/0（排除依赖后解析器仍正常）。**本次改了依赖图 ⇒ 发版前仍须全量 testShards + verifyGate（构建校验只在发版前跑，当轮未跑）。**
- README/CONTRIBUTING 许可证口径保持 Apache-2.0 未动；`docs/变更记录.md` 已登记。仓库另有两份非我创建的计划文档（Jdk8RedisAgentStartupCrash、PresetMethodScope）未动。

## 许可取舍的实质差别（供下次被问时直接复用）

- **专利授权是 Apache 唯一的实质优势**：§3 给下游明确的专利许可与诉讼终止条款；MIT 对此完全沉默。本项目做 JDBC 驱动包装、字节码织入、SQL 解析改写，正落在专利密集地带。用户没申请专利，但文本层面对用户是明示的安全承诺，改 MIT 就没。
- **商标**：Apache §6 明确不授予商标权，对「SQL Visualizer」是道篱笆；MIT 只字未提，fork 拿名字分发在文本层面更难挑错。
- **NOTICE/变更声明义务** Apache 更重，这是多数人偏好 MIT 的真实原因——但只对「要把代码嵌进自家产品」的人有意义，IDE 插件无人如此，优势在本项目≈0。
- **生态一致性**：MyBatis-Plus、MyBatisX、IntelliJ Platform 本身都是 Apache-2.0，仓库里 42 个图标也是。维持 Apache＝一个许可一套解释；改 MIT 变成「MIT + Apache 例外」混合体。
- 兼容性不构成理由：两许可互相兼容，也都进得了 GPLv3 项目。

## 什么情况下才值得改

只一种：打算把某模块（如 XML/OGNL 渲染引擎、HQL 转换器）抽成通用库对外发布，希望零摩擦被采用。即便那样也应**只给该模块单独 MIT、主插件留 Apache-2.0**，比整仓切换省事且不用给图标开例外。

关联 [[project-competitor-docs-open-source-exposure]]（该档「早前轮次」节有许可分档与 agent 子项目 GPL 同源自查：六路比对未发现代码级同源）、[[project-release-workflow]]（发行 8 处同步清单，换许可须并入）、[[project-toolbar-icon-semantics]]。
