---
name: project-openget-multiclient
description: openget（「码上拿」）四端产品——2026-11-02 五批收官：四端功能 + uni-ui + 埋点全链路 + 插件仓移植 + dy 真实交换 + event_log 清理 + /events e2e + phone 登录 + 189 处全限定名清零 + **H5 验收返工三轮（14 处失效 CSS / 三重标题 / switchTab 静默失败 / PC 两栏布局 / custom 挂全局致返回箭头全失）**；终态 249 测试/PMD 0/Checkstyle 0、e2e 52-0-5、mock 守卫 25、端点 50、迁移 V1~V12、契约 v2.6；剩余只有用户本人环境项（ICP/appid/密钥/真机）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fd128d0e-d320-4865-9871-208872610cd6
---

# openget 开源项目识别下载多端产品

**仓**：`F:\code\Java\Gitee\SongRenShuo\openget`（Gitee 私有，账号级令牌在插件仓 `.local/openget-gitee-token.json`）。权威细节在仓内 `docs/`：契约 `docs/设计/契约/API契约-v2.md`（**当前 v2.6**）、`docs/验收报告/20261102-1358-第四批代码级遗留清零-验收报告.md`（最新）与第三批报告、`docs/规范/后端编码规范-阿里规约落地.md`、`docs/经验总结/插件仓移植/`。

## 技术栈（别按立项文档的旧口径写）

后端 **JDK17 + Maven（mvnw）+ Spring Boot 3.5 + MyBatis-Plus + MySQL8 + Redis**。`$JAVA_HOME` = `C:/Users/USER/.jdks/ms-17.0.19`；**PATH 上的 `java` 是 JDK8，别直接用**。构建：`cd server && ./mvnw -s ../dev-support/maven-settings-aliyun.xml verify`（AGENTS.md 曾长期误写 Gradle KTS/Java21，已修正）。前端 uni-app Vue3（微信/抖音/H5）+ Vue3 Element Plus 管理后台。H5 本地验收：`cd miniapp && npm run dev:h5` → `http://localhost:5173/`，`.env` 的 `VITE_USE_MOCK=true` 时整站走内置夹具可离线验收；manifest.json 的 `name` 决定浏览器标签标题（曾一直是立项代号 openget，已改「码上拿」）。起后端：`REDIS_PASSWORD=123456 SERVER_PORT=8080 ./mvnw … spring-boot:run -Dspring-boot.run.profiles=dev`（**8080 常被旧实例占着**，起前先探）。

## 终态读数（2026-11-02 主控独立复跑）

`mvnw verify` **Tests run: 249 / 0 失败 / 0 错误 / 0 跳过**（第三批 229 → +20）；PMD 强制档 0、Checkstyle 0、BUILD SUCCESS。e2e `E2E_GAP=1` **52 PASS / 0 FAIL / 5 SKIP**（57 case）。mock 契约守卫 **25/25**。端点 **50**，迁移 **V1~V12**，契约 **v2.6**。

## 四批交付梗概

1. 规范重构 + 契约迁 camelCase + 四端功能验收（189 测试）
2. 抖音端轻形态 + UI 大厂风令牌化 + uni-ui 接入 + 埋点前后端全链路 + stageTimes + 插件仓精华移植（229 测试）
3. **dy 真实交换**（`3b700de`）：域名靠**实弹探针**证实（官方文档是 SPA 读不到）——假 appid 打真实端点拿 `err_no:40015 "bad appid"`，证实域名/路径/包裹层/`data{openid,anonymous_openid,…}` 结构，备选域名 TLS 失败拒用；err_no 映射含两个**文档没写的** 40014/40015（服务端配置问题→50001，不让用户背错）；secret 与完整 code 不进日志
4. **event_log 滚动删除**（`a39f114`）：V11 种 180 天、每日 04:40、先 `SELECT id LIMIT 500` 再按主键删（不走间隙锁、不依赖 `DELETE … LIMIT` 方言）、单次上限 20 万、不开长事务；反向验证把 `<` 改 `>` 后 3 例红且行为翻转
5. **`POST /events` 的 e2e**（`570df7a`）：T-E 组 8 case；**用幂等探针在纯 HTTP 面证明「整批不入库」**——同批合法条目补发，`accepted=1` 说明之前没入库
6. **phone 登录**（`9b78c25`）：V12 把 `user.phone` 扩 32 并把 `uk_user_phone` 降为普通索引（手机号是实名资产，重复建号危害远小于换绑/合并被唯一键卡死，并发兜底改进程内锁）；三把闸=60 秒重发间隔（命中不消耗小时配额）→ 手机号/小时 5 → IP/小时 20；`phoneBound` 仅本端点出现
7. **全限定名清零**（`0aa386e`，用户直接报的问题）：18 文件 190 处 → 0，加 `FqcnInlineGuardTest` 守卫
8. **H5 验收返工**（`3e07d28` + `2059f95`，用户两次报障「布局没重构还是很丑」「tab 点了不跳转」）：
   - 14 处 CSS 声明**整条失效**（令牌替换把 `#fff` 尾巴拼进 `var()` 造出 `var(…, #ffffff)fff`，浏览器静默丢弃）→ 卡片/按钮丢白底，看起来像没做过样式；详见 [[pitfall-css-token-batch-substitution-invalid-declaration]]
   - H5 **三重标题**（原生标题栏 + 顶栏品牌 + 页面大标题）→ 顶栏品牌 <1024 隐藏、页面角标 `#ifndef H5`
   - **`uni.switchTab` 在 H5 静默失败**（tabBar 只对小程序条件编译）→ 9 处调用点统一改 `utils/nav.js` 的 `goTab`（H5 走 reLaunch）；详见 [[pitfall-uniapp-h5-no-tabbar-switchtab-silent-fail]]
   - **我自己造的回归**：把 `navigationStyle: custom` 挂进 `globalStyle` → H5 原生标题栏连返回箭头一起没了，八个二级页全无返回入口（用户第三轮报障）。正确做法是只挂在三个自带 TopNav 的 tab 页上
   - PC 空旷（`cc89540`）→ 改成**两栏**：主列 620（Hero/搜索/从电脑选文件/三步说明/热门搜索）+ 右栏 400「今日热门」（`GET /trending` 同源取前 5，含排名、★星数·语言、秒下标、加载骨架/空态/失败静默），栏距 48、右栏 sticky；容器 760→1120，顶栏内宽同步 1120
   - 热门页时效标（`38baa6b`）→ 两行叠字改**单行胶囊**（秒下=success-bg/深绿、约几分钟=warning-bg/深琥珀），副文案删掉改由语言筛选条下方一行图例解释（桌面表格有「时效」列，图例 ≥1024 隐藏）
   - **教训：令牌化 ≠ 布局重构**——上一轮只换色没动结构，报告用「462 处 `var(--og-*)`」冒充了效果指标；**且「用户报丑」往往先怀疑审美、其实是声明整条失效**，先查 computed style 再谈设计

## 关键决策（已定，别翻烙饼）

- 契约字段一律 lowerCamelCase，id 一律字符串输出。
- **`is_anonymous` 不实现**：客户端本就知道走哪个分支，服务端回传无信息增量，每加一键要三端会签。
- **抖音匿名登录不发新客 10 次额度**：`anonymousCode` 是 5 分钟一次性凭证，拿它做身份=「每 5 分钟一个新用户」。
- **`share_card_open` = 卡片被打开**（`share=1` 冷启动），不是「点了分享」——小程序端没有分享成功回调。
- **UI 选 uni-ui**：保证编译到 mp-toutiao；丑用 `design-system/tokens.css` 覆盖解决。
- **埋点**：客户端仅 3 个事件名，14 个服务端事件随业务落库；`EventLogService.record` 吞异常且**不能放事务内**（`quota_exhausted` 曾被同事务回滚掉）；payload 白名单用有序 `List`（`Set.of` 迭代让 JSON 键序随 JVM 变）。
- **不做跨通道账号合并**：phone 只按 phone find-or-create；`deviceFp` 仅校验不落库（`user.device_fp` 有游客唯一约束）。

## 剩余待办（都卡外部条件）

1. **只能用户本人**：jvtools.cn ICP 备案；微信小程序注册；抖音开放平台注册（appid 注入 `openget.dy.app-id/secret` 即切真实交换，`dyBound=true` 才可达）；七牛密钥注入 `.local/qiniu-keys.env`。
2. **e2e T1.4/T1.5**：phone 端点已实现，但 mock 短信通道下 e2e 取码方案（固定码 or 环境变量）未裁决，两条仍 SKIP。
3. **真机待验**：剪贴板直传、`shareFileMessage` 上限、抖音复制链接下 50MB、README 三类夹具、`uni-search-bar` 的 `margin:-20rpx` 负边距机型对齐。
4. **真实短信通道**：只留 `SmsSender` 接口与 TODO，接运营商时新增实现并把 `openget.sms.provider` 指向它。

## 跨轮次可复用的坑（本仓实测）

1. **多代理共用工作树会污染 `target/`**：实测「189 个用例只 discovery 到 35 个且不报错」。跑权威读数前确认无其他 maven，并 `rm -rf target/classes target/test-classes`。
2. **Flyway 迁移一经任何实例应用即冻结**；并行多代理时迁移号必须**预分配**（本轮 A 无迁移 / B=V11 / D=V12）。
3. **`@ConditionalOnBean(StringRedisTemplate)` 评估先于 RedisAutoConfiguration** → 用 `@Profile("!test")`。
4. **本地 Redis 密码是 123456**（不是 111111）；起 dev 服务 `REDIS_PASSWORD=123456 SERVER_PORT=8080 … -Dspring-boot.run.profiles=dev`。
5. **相对路径 `@import` 必须实跑构建**：`../../../` 与 `../../` 之差让三端全红才发现。
6. **Bash `| tail` 既吞退出码又把后台日志缓冲到结束**；再叠一层 `grep` 过滤会把 javac 的「符号: 类 X」明细行也滤掉——看过程读 surefire 报告与原始日志文件。
7. **surefire 多类用逗号**：`-Dtest='A+B+C'` 不认，会「No tests」→ BUILD FAILURE 且日志里连 `Tests run` 行都没有。
8. **Gitee**：`git commit -F`（反引号陷阱）；推 `dev-support/push-gitee.ps1`；判同步用 `git ls-remote`。
9. **文档读不到正文（SPA）时用线上端点实弹探针取结构证据**，查不到的留 TODO 标来源与日期，不编造——抖音 jscode2session 域名与两个错误码就是这么拿到的。
10. **共享工作树里 `git checkout --` 会连带丢掉同批次子代理未提交的改动**（本轮误伤 5 个在途文件，靠测试用例当规格重新实现才补回）——回退前逐文件确认归属。
11. **heredoc 写 python 脚本会吞反斜杠**（`[^"\\]` 变 `[^"\]` 直接 SyntaxError）——跨调用写文件一律用 Write 工具。

**Why:** 这些坑多属「编译绿、单测绿、构建绿」但功能或契约已坏的类型，门禁发现不了。
**How to apply:** 续作前先读 `docs/验收报告/` 最新一份与 `docs/经验总结/插件仓移植/仅取片段-通用失败模式.md`；改响应先看契约 v2.6 并以代码为准核对（文档曾长期漂移）。相关：[[project-overview]]、[[feedback-chinese-commit-messages]]、[[pitfall-fffd-repair-positional]]、[[pitfall-batch-source-rewrite-regex-scope]]、[[pitfall-test-unique-data-by-clock-tail]]、[[pitfall-mp-abbreviation-column-mapping]]、[[pitfall-uniapp-component-swap-dom-and-state]]。
