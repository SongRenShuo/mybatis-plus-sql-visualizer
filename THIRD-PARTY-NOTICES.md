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

## 5. PostgreSQL JDBC Driver 42.7.1（BSD 2-Clause）

- 位置：随 OpenIDEA 侧引擎 jar（`sub-projects/engine-jar` 构建产物）自包含分发，用于 T5-5 真连库 EXPLAIN
- 版权：Copyright (c) 1996-2024, PostgreSQL Global Development Group；Copyright (c) 1994, The Regents of the University of California
- 来源：<https://jdbc.postgresql.org/>（Maven Central `org.postgresql:postgresql:42.7.1`），未修改
- 许可：BSD 2-Clause（PostgreSQL License），全文如下

```text
PostgreSQL Database Management System
(formerly known as Postgres, then as Postgres95)

Portions Copyright (c) 1996-2024, PostgreSQL Global Development Group
Portions Copyright (c) 1994, The Regents of the University of California

Permission to use, copy, modify, and distribute this software and its
documentation for any purpose, without fee, and without a written agreement
is hereby granted, provided that the above copyright notice and this
paragraph and the following two paragraphs appear in all copies.

IN NO EVENT SHALL THE UNIVERSITY OF CALIFORNIA BE LIABLE TO ANY PARTY FOR
DIRECT, INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING
LOST PROFITS, ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS
DOCUMENTATION, EVEN IF THE UNIVERSITY OF CALIFORNIA HAS BEEN ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.

THE UNIVERSITY OF CALIFORNIA SPECIFICALLY DISCLAIMS ANY WARRANTIES,
INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY
AND FITNESS FOR A PARTICULAR PURPOSE. THE SOFTWARE PROVIDED HEREUNDER IS
ON AN "AS IS" BASIS, AND THE UNIVERSITY OF CALIFORNIA HAS NO OBLIGATIONS TO
PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR MODIFICATIONS.
```

## 6. Microsoft JDBC Driver for SQL Server 12.4.2（MIT）

- 位置：同上，随引擎 jar 自包含分发，用于 T5-5 真连库 EXPLAIN（`SET SHOWPLAN_XML ON`）
- 版权：Copyright (c) Microsoft Corporation
- 来源：<https://github.com/microsoft/mssql-jdbc>（Maven Central `com.microsoft.sqlserver:mssql-jdbc:12.4.2.jre11`），未修改
- 许可：MIT，全文如下

```text
MIT License

Copyright (c) Microsoft Corporation.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 7. 运行期平台依赖（不随包分发）

- IntelliJ Platform / IntelliJ IDEA：由宿主 IDE 提供，不在插件 zip 内。
- openidea-plugin SDK：链接期依赖，不随包分发。

---

维护约定：每次增减分发包内的第三方组件，同步更新本文件，并在 docs/变更记录.md 登记。打包接线与 jsqlparser 的 jmh-core 排除项由守卫测试 `convention/DistributionComplianceGuardTest` 钉住。
