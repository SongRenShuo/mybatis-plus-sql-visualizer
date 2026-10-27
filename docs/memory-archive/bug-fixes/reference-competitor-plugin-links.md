---
name: reference-competitor-plugin-links
description: 竞品插件链接总表（2026-09-11 经 Marketplace API 逐一核验可达）——README「参考与致谢」定稿 6 项的
  id/页面/源码仓库，附 Marketplace API 核实链接工作流与 MyBatis Log Free 仓库纠错
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_5583cb72-c450-46d1-8620-9c64eb925af3
---

# 竞品插件链接总表（2026-09-11 核验）

README.md「参考与致谢」节（2026-09-11 新增，位于「变更记录」与「友情插件推荐」之间）定稿 6 项主要借鉴对象，链接全部经实测核验：

| 插件 | Marketplace | 源码仓库 |
|------|-------------|----------|
| MyBatisX | plugin/10119 | github.com/baomidou/MybatisX |
| MyBatisCodeHelperPro | plugin/9837 | 无（闭源） |
| MyBatis Log Free | plugin/17898 | github.com/starxg/mybatis-log-plugin-free |
| Mybatis Log Ultra | plugin/26550 | 无公开登记 |
| JTracker | plugin/24694 | 无公开登记 |
| Cool Request | plugin/23555，官网 www.coolrequest.dev | github.com/houxinlin/cool-request（**GPL-2.0-or-later**） |

> **许可证核实（2026-10-27 实测）**：抓 `raw.githubusercontent.com/houxinlin/cool-request/main/LICENSE` 得 GNU GPL **Version 2, June 1991**，正文含「either version 2 of the License, or (at your option) any later version」⇒ 准确表述是 **GPL-2.0-or-later**。此前本表记作 GPL-3.0 有误。
> 另有两点须记住：①该插件还有一版**商业仓**（`houxinlin/coolrequest-vip`），其根 LICENSE 同为 GPL v2，但**该仓现已在 GitHub 404（转为不可公开访问）**——引用它不再具备「公开源码署名式引用」前提，任何引用都应降级为「交互形态归纳」，不记文件级定位；②**GPL 与本仓 Apache-2.0 不兼容**，只能读、不能复制/移植代码或资源（含 SVG 路径描摹）。

**纠错**：早期计划文档（20260822-1606/1608）把 MyBatis Log Free 源码记为 `github.com/Stkai/intellij-mybatis-log-plugin`——Marketplace 官方登记实为 **starxg/mybatis-log-plugin-free**（Stkai 仓库是另一个插件）。引用竞品仓库一律以 Marketplace API 登记为准，不信计划文档。

## 引用第三方源码的许可判据（2026-10-27 实测各仓 LICENSE）

本仓为 **Apache-2.0**。引用第三方源码前先查这张表：**Apache-2.0 的可以读、可以借鉴思路、署名引用其公开代码位置；GPL 系的只能读，不能复制/移植代码与资源**（含 SVG 路径描摹），因为 GPL 与本仓许可证不兼容。

| 项目 | 许可证 | 可否引用其源码位置 |
|------|--------|------------------|
| baomidou/mybatis-plus | Apache-2.0 | 可以 |
| baomidou/MybatisX | Apache-2.0 | 可以 |
| t8y2/dbx | Apache-2.0 | 可以 |
| houxinlin/cool-request（开源版） | GPL-2.0-or-later | **不可**，只读 |
| starxg/mybatis-log-plugin-free | GPL-2.0-or-later | **不可**，只读 |
| houxinlin/coolrequest-vip（商业版） | GPL v2（本地副本），**仓库现已 404/不可公开访问** | **不可**，且已失去「公开源码」前提 |
| MyBatisCodeHelperPro / Mybatis Log Ultra / zMyBatis / JTracker | 闭源（无 sourceCodeUrl 登记） | **不可**，不得记录实现细节 |

**另两条硬约束**：
1. **仓库转私有即失去引用前提**——即使当初是公开的 GPL 仓，一旦不可公开访问，留存的文件级定位（路径 + 行号）就等同于记录非公开实现细节，应降级为「交互/能力形态归纳」。`coolrequest-vip` 就是这种情形。
2. **凭据与个人信息不入库**——引用第三方代码时只记「在哪读了什么结论」，不记对方的账号、邮箱、测试库地址、密码存放位置。

## Marketplace API 核实链接工作流

- `curl -x http://127.0.0.1:7890 https://plugins.jetbrains.com/api/plugins/{id}` → `name`/`vendor`/`urls.sourceCodeUrl`/`urls.homepageUrl`（sourceCodeUrl 即官方登记的源码仓库，权威）；`/api/plugins/{id}/links` 端点返回空数组，别用。
- 不知道 id 时 `api/searchPlugins?search=<名>&max=5`（如搜出 Mybatis Log Ultra=26550、vendor yalonglee）。
- 其余核验过的 id（未进 README 但调研报告在用）：MyBatisCodeHelperPro Marketplace Edition=14522、MyBatis Log=13905、Free MyBatis Tool=18617、MyBatisPlus=12670、MySQL Explain=13192（repo newhoo/mysql-explain）、Mybatis-Flex-Helper=22165、JPA Buddy=15075、MyBatisSQLPreviewer=28245、MyBatis SQL Analyzer=32766。BatsLog 上游 = github.com/PerccyKing/batslog。
- GitHub 直连在本机网络下不通（curl 000），验证仓库可达用直连超时后改走代理或只信 API 登记。

相关 CoolRequest agent 分析（内部资料库）、[[project-m56-agent-bytecode]]；README 精简口径见 [[feedback-doc-lists-keep-main-only]]。
