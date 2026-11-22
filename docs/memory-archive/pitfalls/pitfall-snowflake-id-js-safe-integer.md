---
name: pitfall-snowflake-id-js-safe-integer
description: 雪花 ID（19 位，超 2^53−1）直接以 JSON 数字下发，浏览器 JSON.parse 会静默丢精度——前端拿它回查详情必然 404；阿里手册强制「超大整数一律 String 返回」，且只能改 put() 实参（序列化注解对 ObjectNode 无效）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fd128d0e-d320-4865-9871-208872610cd6
---

openget 管理后台做真机 e2e 时实测到：`complaint_ticket.id` 与 `admin_audit_log.id` 用 MyBatis-Plus `IdType.ASSIGN_ID`（雪花，19 位），实际值 `2116981689158430721` 超过 JavaScript `Number.MAX_SAFE_INTEGER`（2^53−1 ≈ 9.007e15）。浏览器 `JSON.parse` 会**静默**把它变成 `2116981689158430700`，前端用这个 id 回查工单详情、提交处理动作**必然 404**——接口 HTTP 200、`code:0`，看起来一切正常。

**这正是《阿里巴巴 Java 开发手册》的强制条款**：「对于需要使用超大整数的场景，服务端一律使用 String 字符串类型返回，禁止使用 Long 类型」。

**影响面判定法**（不是「用了雪花就危险」，要看输出形态）：
| 形态 | 是否危险 | 例 |
|---|---|---|
| 直接输出数字 | **危险** | `node.put("id", ticket.getId())` |
| 拼了字符串前缀 | 安全 | `put("id", "dj_" + id)` / `"it_" + id` |
| 自增主键（`AUTO`） | 安全 | 值小 |

openget 实测：`ComplaintTicket`/`AdminAuditLog`/`AdminUser`/`RepoBlocklist` 是 `ASSIGN_ID` 且直接出数字（危险）；`IdentifyTask`（`it_` 前缀）、`DownloadJob`（`dj_` 前缀）已安全；`QuotaLedger`/`AdRewardLog`/`Invite` 是 `AUTO` 安全。

**修法**：`put("id", String.valueOf(...))`（`ObjectNode.put(String,String)` 输出 JSON 字符串）。
**注意**：`@JsonSerialize(using = ToStringSerializer.class)` **只对 POJO 序列化生效，对 `ObjectNode` 拼装的响应无效**——不能只加注解就以为修好了（见 [[pitfall-json-naming-strategy-scope-pojo-vs-objectnode]]）。
配套：契约字段类型改 `string`、e2e 与三端类型同步、加一条「响应中 id 均为 string」的守卫。

**How to apply:** 后端只要用雪花/分布式 ID，就默认「对外 id 一律字符串」；验收不能只看「看起来是字符串」，要拿这个字符串 id **真做一次详情查询和写操作并成功**。
