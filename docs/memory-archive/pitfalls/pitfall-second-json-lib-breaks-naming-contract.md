---
name: pitfall-second-json-lib-breaks-naming-contract
description: 给 Spring 项目引入第二个 JSON 库（如 Hutool JSONUtil）去接管 HTTP
  序列化会撕毁全局命名契约——Jackson 的 property-naming-strategy=SNAKE_CASE 与 @JsonProperty
  不被新库识别，输出变 camelCase，前端当场全崩
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fd128d0e-d320-4865-9871-208872610cd6
---

openget 后端重构时用户要求「JSON 改用 Hutool」，审计后判定**不能让 Hutool 接管 HTTP 出入参**。

机制：项目 `application.yml` 配 `spring.jackson.property-naming-strategy: SNAKE_CASE`，并靠 `@JsonProperty("is_new")` 逐字段补丁——这套是三端前端/小程序/管理后台的解析契约（`page_size`/`has_more`/`cache_hit`/`readme_html`/`is_new`）。Hutool `JSONUtil` 是自研实现，**既不读 Jackson 注解也不认命名策略**，替换后输出统一变 `camelCase`。

判据与做法：
- 判断「能不能换」看的是**线上字段名是否被非代码消费方依赖**，不是「新库功能够不够」。
- 正确分工：Jackson 守 HTTP 边界（DTO 注解 + MVC 配置），Hutool JSON 只处理内部无契约 JSON（配置值、第三方 API 响应、审计摘要）。
- 若确要全量换，代价 = 全部 DTO 重注 + 手写 `HttpMessageConverter` + 三端联调回归，收益为零。
- 配套守卫：一条断言响应含 `total_earned` 而非 `totalEarned` 的测试，防后人「顺手统一」。

同族风险：任何「全局命名/序列化策略 + 注解补丁」的契约（Jackson、Gson、Proto、Excel 导出列名）在引入替代库时都要先问「谁在消费字段名」。

**20261102 补充（用户追问「阿里规约禁止小驼峰吗」）**：阿里 Java 开发手册**没有**规定 JSON 用驼峰还是下划线——它的命名规约管的是 Java 标识符（类 UpperCamel / 方法与变量 lowerCamel / 常量 UPPER_SNAKE / 布尔禁 `is` 前缀）。唯一沾边的是「布尔禁 is 前缀」，正是本项目 `firstRegister` + `@JsonProperty("is_new")` 的由来。所以**别拿「阿里规约」当命名风格的选型理由**，那管的是代码层，与线上字段名无关。

**本坑的前提是可变的**：它成立的前提是「契约本身靠 Jackson 全局命名策略 + 注解补丁」。若项目把契约迁成 camelCase（openget 正在评估，方案与时间窗见 [[project-openget-multiclient]]），命名策略这一障碍消失，Hutool JSON 接管的硬理由随之弱化——但 `@JsonProperty` 补丁与 LocalDateTime 自定义格式仍需单独处理，届时要重新评估而非直接放行。

**20261102 结局（前提已不成立，仅存历史价值）**：openget 已按版本化四步把契约全量迁到 lowerCamelCase 并定稿 v2（`SNAKE_CASE` 配置、双写层、`@JsonProperty("is_new")` 补丁均已删除，e2e PASS=42）。所以「新库不认命名策略」这条已不再是禁换理由，**真正剩下的理由只有一个**：Hutool JSON 是自己的对象模型，接 HTTP 边界等于手写 `HttpMessageConverter` 且要重注全部 DTO 注解，收益为零。结论仍是不换，但理由要换成这条。

**真正可复用的那层教训**：这次迁移暴露出更普遍的一条——**命名策略只管 POJO，不管 `ObjectNode` 的硬编码键**，光删配置只会得到「一半驼峰一半下划线」。详见 [[pitfall-json-naming-strategy-scope-pojo-vs-objectnode]]。

**Why:** 这类替换在评审里看起来是「统一技术栈的好事」，实际是单方面撕毁对外契约，且本地单测全绿——因为断言也是用同一套序列化跑出来的。
**How to apply:** 引入第二个序列化库前，先 grep 全局命名策略配置与被消费方的字段名硬编码；替换方案必须带一条「字段名不变」的契约守卫测试。
