# 第三方组件与许可声明（Third-Party Notices）

本文件列出 SQL Visualizer 分发包内包含的第三方组件及其许可。本项目自身以 Apache License 2.0 发布，全文见根目录 [LICENSE](LICENSE)。

分发包（`build/distributions/*.zip`）结构：

- 插件主 jar：`mybatis-plus-sql-visualizer/lib/mybatis-plus-sql-visualizer-<版本>.jar`
- 运行期第三方依赖：`mybatis-plus-sql-visualizer/lib/*.jar`
- 内嵌字节码增强 agent：插件主 jar 内 `agent/mps-sql-agent.jar`

## 1. IntelliJ Platform 图标（Apache License 2.0）

- 位置：`src/main/resources/icons/toolbar/` 下 42 个 SVG（16×16 ExpUI 图标）
- 版权：Copyright 2000-2022 / 2000-2023 / 2000-2024 JetBrains s.r.o. and contributors
- 许可：Apache License 2.0（<https://www.apache.org/licenses/LICENSE-2.0>）
- 说明：复制自 JetBrains IntelliJ Platform，未修改；每个文件头部保留原始版权声明。

## 2. JSqlParser 5.3（Apache License 2.0 / LGPL-2.1 双许可，本项目按 Apache License 2.0 使用）

- 分发：`lib/jsqlparser-5.3.jar`，未修改，jar 内自带上游 LICENSE
- 版权：JSQLParser 项目（2004 年起，<https://github.com/JSQLParser/JSqlParser>）
- 说明：上游 POM 声明 LGPL-2.1 与 Apache-2.0 双许可，本项目按 Apache-2.0 使用。
  其 POM 把 `org.openjdk.jmh:jmh-core` 声明为编译期传递依赖（仅上游基准测试用，
  jar 内无任何类引用该库），本插件已在构建时排除，见 build.gradle.kts。

## 3. ASM 9.4（BSD 3-Clause）

- 分发：`org.ow2.asm:asm` 与 `org.ow2.asm:asm-commons` 经 relocate 到
  `cn.song.mybatisplus.sqlvis.agent.asm` 命名空间后打包进内嵌 `agent/mps-sql-agent.jar`
- 版权：Copyright (c) 2000-2011 INRIA, France Telecom
- 许可全文：

```
ASM: a very small and fast Java bytecode manipulation framework
Copyright (c) 2000-2011 INRIA, France Telecom
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:
1. Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.
3. Neither the name of the copyright holders nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
```

## 4. iBATIS 2 SQL Map DTD（Apache License 2.0）

- 位置：`src/main/resources/dtd/ibatis/sql-map-2.dtd`（25,464 B）、
  `src/main/resources/dtd/ibatis/sql-map-config-2.dtd`（11,403 B），随插件 jar 根分发
- sha256：`sql-map-2.dtd` = `bb8b53f0a6f3a6b015eeb3f3fe69f10b15023d5c33a1ff583b3fe77bc030e09e`；
  `sql-map-config-2.dtd` = `45f03ebb8811027a2f474a4ed0e98fffa4e5a8fdcd9596d09ae5f48b7a2a008d`
- 版权：Copyright 2004 Clinton Begin
- 来源：iBATIS 2.3.4.726（`ibatis-sqlmap-2.3.4.726.jar`）内
  `com/ibatis/sqlmap/engine/builder/xml/` 的原始副本，未修改；文件头保留原始版权与许可声明
- 许可：Apache License 2.0（<https://www.apache.org/licenses/LICENSE-2.0>）
- 说明：以 jar 内副本为准——网站版 `sql-map-config-2.dtd` 的根声明是 `sqlMap*`，
  jar 内是 `sqlMap+`（运行期 `SqlMapClasspathEntityResolver` 用的也是 jar 内副本）。
  插件经 `plugin.xml` 的 `<standardResource>` 把 iBatis 的 8 个 DOCTYPE 标识
  （4 个 systemId + 4 个 publicId）映射到这两份副本，使 iBatis 2 的 XML 在离线状态下可校验。

## 5. 运行期平台依赖（不随包分发）

- IntelliJ Platform / IntelliJ IDEA：由宿主 IDE 提供，不在插件 zip 内。

---

维护约定：每次增减分发包内的第三方组件，同步更新本文件，并在 docs/变更记录.md 登记。打包接线与 jsqlparser 的 jmh-core 排除项由守卫测试 `convention/DistributionComplianceGuardTest` 钉住。
