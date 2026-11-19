---
name: pitfall-json-naming-strategy-scope-pojo-vs-objectnode
description: Jackson 的 property-naming-strategy 只影响 POJO 字段序列化，不影响 ObjectNode/Map 的硬编码键——「删掉 SNAKE_CASE 配置就切成驼峰」是错的，必须同时把每一处 put("snake_case") 的字面量改掉；@JsonSerialize(ToStringSerializer) 同理对 ObjectNode 无效
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fd128d0e-d320-4865-9871-208872610cd6
---

openget 做契约命名 snake_case → camelCase 迁移时，曾想「删掉 `spring.jackson.property-naming-strategy: SNAKE_CASE` 一行就完事」，实测证伪。

**机制**：命名策略作用于 **POJO 属性名**（反射拿字段再转换）。而 `ObjectNode.put("total_earned", ...)` 的键是**调用方写死的字符串字面量**，容器不做任何命名变换。所以同一个 ObjectMapper、同一份配置下：
- `GET /me/quota`（POJO `QuotaResp`）→ 配 SNAKE_CASE 时输出 `total_earned`
- `GET /me/ad-quota`（手工 ObjectNode）→ 同一配置下仍是 `watchedToday`

**推论（迁移时必须三件事同时做）**：
1. 删/改命名策略（POJO 路径）
2. **批量替换 ObjectNode 键字面量**（本项目实测 79 处 / 18 文件）
3. 删掉为过渡加的序列化双写层

只做 1 会得到「一半驼峰一半下划线」的更糟状态。

**同族陷阱**：`@JsonSerialize(using = ToStringSerializer.class)`（以及 `@JsonFormat`、`@JsonInclude` 这类字段注解）同样**只对 POJO 生效**，对 ObjectNode 拼装的响应无效——想改 ObjectNode 的值类型只能改 `put(...)` 的实参（如 `put("id", String.valueOf(x))`）。参见 [[pitfall-snowflake-id-js-safe-integer]]。

**另一处观察**：`@JsonInclude(NON_NULL)` 与「全局未配 `default-property-inclusion`」会改变 `data: null` 这类字段是否出现，而前端常按「字段存在性」判空——加注解前先确认全局策略，本项目靠一条守卫测试直接读 `application.yml` 断言策略值。

**How to apply:** 改 JSON 命名/类型前，先分清「POJO 路径」与「ObjectNode/Map 路径」，分别列改动点；验收用**改造前后同一请求的响应键集合递归 diff**，不要用肉眼或单测（单测跑的是同一套序列化，天然看不到差异）。
