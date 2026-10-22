---
name: t-competitive-round4
description: 第四轮竞品横向对比已交付（20260915-0123 报告）——N-12/N-13/r2 合流全在 t-fourplans 集成链待落
  master、v2026.9.14 市场缺位（225 下载 0 评论势头拐点）、「竞品 MCP 全空」失效（CodeHelperPro
  工具线三连发+validate_mybatis_mapper）、DBX 增量吸收 #14~#18、新建议 N-15/N-16 + O-12~O-14
metadata:
  node_type: memory
  type: project
  originSessionId: sess_2a8b19f2-ad6a-45ac-8278-7b36ee29ed55
---

第四轮竞品横向对比 2026-09-15 交付，主报告 `docs/调研报告/20260915-0123-竞品横向对比与功能建议-第四轮调研报告.md`（单文件，DBX 增量并入未单开专项）。四个并行子任务：本插件增量落地核查 / Marketplace 数据刷新 / DBX 增量（F:\code\Database\dbx 克隆 fetch）/ 竞品动向。

关键事实（后续会话直接引用，勿重查）：
- **上轮建议落地**：N-12 MCP 一期（T-McpToolset 49b7575，三只读工具 sqlv_render_sql/sqlv_schema_context/sqlv_explain 注册平台 mcpServer.mcpToolset EP，243 明确不支持，mcpStub 桩源集，+40 测试）与 N-13 预览四件套（ef05f45，(new)列/降级横幅/${table}快捷/多语句确认）**全在 t-fourplans-20260914 集成链（c7643f2 领先 master 75 提交，4207/0/6），未落 master**；r2 合流 3fdccd8 亦然；t-market-internal（2567812，262 Internal 6→0，4081 基线）未被集成链包含且计数交叉，归属待决断。O-9 无界写判定仍零实现（RollbackSql F2 评级≠写风险判定），O-10/O-11 未做，N-14 零实现（维持 P3）。
- **市场（2026-09-15）**：17 插件隔日零发版零新上架；本插件 225 下载（+2/日，上轮 +14/日，势头衰减七倍）、0 评论、"sql visualizer" 搜索第 1；**v2026.9.14 在 Marketplace 公开 API 无踪迹**（updates 7 条全 approved 最新 2026.9.11，hasUnapprovedUpdate=false 无待审）= 本地已发行未上传；MyBatisX 待审版压审 8 天+（开源仓库 2024-08 已死，内容盲区，过审 48h 复查触发器）。
- **格局修正**：上轮「竞品 MCP 全空」失效——CodeHelperPro 自 3.5.7（07-02）连发 MCP 工具线，3.6.5（09-09 付费版）validate_mybatis_mapper = Mapper XML/接口静态契约校验+动态标签置信度分级；位差=它做校验、我们做渲染（agent 消费 SQL 的主链）。obroom until 预注册 273.* 且吸收老 MyBatis Log Plugin（10065）下架流量。Analyzer 克隆零新提交降季检。
- **DBX 增量**（一天 60 提交 353 文件，19,451 star）：上轮吸收清单 #1~#13 核心机制零 diff 无失效；新增吸收 #14 MCP structuredContent 必须对象（server.rs:949，Issue #7548 返工——**直接适用核查我们三只读工具返回形态**）、#15 DDL/提交后元数据失效且失败也要失效（object_cache.rs:41，Oracle 隐式提交）、#16 字面量内分号 quote 栈+括号深度状态机、#17 批量等值谓词 IN 合并（data_grid_sql.rs:1789）、#18 跨库读 scope「读面扩大与写授权解耦」（ai_cli_agent.rs:83-86）。插件生态平台化（工具桥/插件市场）不吸收。
- **新建议编号承接**：N-15 发布与冷启动执行闭合（P0 市场：上市场+N-11 遗留+首评）、N-16 MCP 合规核查与差异化守位（P0：structuredContent 核查+位差叙事+MyBatisX 触发器）、O-12 DDL 后元数据失效、O-13 分号状态机、O-14 IN 合并；O-9/O-10/O-11 滚动挂账。8.1 合流清账不占编号（集成链落 master+market-internal 归属+工作树 TEMP(t-crudsql-gif) 块还原）。均未立项。
- 平台接口变化沉淀：Marketplace 详情端点不再返回 rating（改 /rating 票型分布加权）、comments 返回顶层数组靠 Link 头推总数、searchPlugins max≤20。

相关：[[t-competitive-round3]]（DBX 五路深挖基线与 N-11~N-14/O-9~O-11 原案）、[[t-fourplans-batch]]（集成链构成与集成清单）。
