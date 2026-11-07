# T-HotSwapUnloadSafety：热替换在 243 被平台否决，与归因/文档修复（2026-11-06）

> 来源＝用户报障「内测更新渠道有问题，还是提示重启」，随后补充「但是我 262 的时候就可以正常热更新的」。
> 定位结论：**渠道没问题、262 的热替换也没退化**；243（2024.3.7.1）上平台按设计不允许卸载本插件。
> 附带两个我们自己的缺陷：KDoc 声称存在一条从未写过的守卫，以及拒绝原因在日志里被吞成「platform gave no reason」。

## 一、证据链（真机日志 + 平台字节 + 时间线）

**1）真机日志（243 沙盒，`log_runIdeHotSwap/idea.log` 2026-11-06 18:11）**

```
18:11:03  update manifest url overridden by system property: http://localhost:8919/update.json
18:11:04  downloadAndInstall: downloading http://localhost:8919/mybatis-plus-sql-visualizer-2026.11.6-beta.3.zip
18:11:04  downloaded (7500243 bytes) → zip valid, calling install
18:11:05  #c.i.i.p.DynamicPlugins - Plugin … is not unload-safe because of extension to
          non-dynamic EP com.intellij.sql.dialect in optional dependency on com.intellij.database
18:11:05  dynamic install not applicable — falling back to restart script（family=legacy）
```

本地渠道三段全通（清单覆盖生效 → 真实包 7,500,243 字节 → zip 校验通过），卡在平台否决卸载。

**2）平台字节（`dev-support/probe-platform-ep-dynamism.py`，2026-11-06 实测）**

| 平台 | `com.intellij.sql.dialect` 声明 | 本插件可热卸载 |
|---|---|---|
| 243 IU-2024.3.7.1（本地解包直读） | 2 处声明，**0 处带 `dynamic`** | ❌ 只能重启 |
| 253 IU-253.33514.17 | 1 处，`dynamic="true"` | ✅ |
| 262 2026.2 最新发行 | 1 处，`dynamic="true"` | ✅（**与用户真机「262 能正常热更新」一致**） |
| 263 IU-263.7307.200 | 1 处，`dynamic="true"` | ✅ |

取证方式：对官方发行 zip 走 HTTP Range 只抽 `plugins/DatabaseTools/lib/database-plugin.jar`（1.6GB 整包不落），本机 243 直接读 IPGP 解包目录。

**3）时间线（能力是后来退化的，不是 beta.2 修复没生效）**

```
git log -S "sql.dialect" -- src/main/resources/META-INF/database-optional.xml
6199a2a7 2026-10-14 feat(dialect): T-DmDialect —— 注册达梦 SQL 方言
47e7693f 2026-10-29 feat(dialect): 券06 注册 GaussDB 平台 SQL 方言
d8582b12 2026-10-29 feat(dialect): T-XcDialects3 券08 注册 HighGo/Vastbase
```

仓库里 2026-09-11「免重启热替换全链实测通过」发生在 10-14 之前；自那以后 243 上必然回退重启。
253/262/263 因为平台把该 EP 标成了 dynamic，不受影响。

## 二、我们自己的两个缺陷（本次修）

1. **KDoc 声称的守卫不存在**：`DynamicInstallSupport` 类注释写「本插件注册的 EP 全部落在平台
   `dynamic="true"` 点位上，由 `DynamicEpComplianceGuardTest` 守卫」——`grep -rn "class DynamicEpComplianceGuardTest" src/` **零命中**。这条「已合规」结论从未被机器验过，而事实相反（243 上非动态）。
2. **拒绝原因被吞**：boolean 族（`allowLoadUnloadWithoutRestart` / `checkCanUnloadWithoutRestart`）只返回真假，
   我们写 `gate.reason ?: "platform gave no reason"`，而平台上一行就点名了确切 EP —— 定位时白绕一轮。

## 三、改动范围

**做**

- 新增 `PlatformEpDynamism`：非动态 EP 事实表（EP 名 / 从哪个 baseline 起 dynamic / 取证出处 / 声明文件），
  并给出「当前平台为何不可热卸载」的确定文案。
- `DynamicInstallSupport`：拒绝时用上面的事实表补位（不再写「platform gave no reason」）；KDoc 改与事实一致。
- 新增真守卫 `DynamicEpComplianceGuardTest`：登记表 ↔ 描述文件双向一致、取证必填、非动态 EP 的扩展只允许落在
  可选依赖描述文件（主 `plugin.xml` 无条件加载，Community 会炸）。
- 新增 `PlatformEpDynamismTest`（纯逻辑：版本分界、文案点名、登记自洽）。
- 新增取证脚本 `dev-support/probe-platform-ep-dynamism.py`（Range 抽包，可复跑）。
- 文档：本册、验收文档已知边界、LESSONS 一条。

**不做**

- 不动 `sql.dialect` 的注册形态（撤掉会让 DM/GaussDB/HighGo/Vastbase 在 Database 侧不进可选清单、
  预览降级；收益只是让 243 也能热替换，代价与收益不匹配）。
- 不改 UI 文案（`settings.update.installed` 仍是「更新完成，重启 IDE 后生效」）——243 上这就是正确结果；
  若要加「为什么」，需要把原因穿到 `InstallResult`，属独立改动。
- 不动 `update/update.json`（用户专属）。

## 四、验收

| 项 | 判据 |
|---|---|
| 定向 | `PlatformEpDynamismTest` + `DynamicEpComplianceGuardTest` + 既有 `DynamicInstallSupportTest` 全绿 |
| 反向 1 | 从 `PlatformEpDynamism.EXTENSIONS` 摘掉 `SQL_DIALECT` → 守卫「登记的非动态 EP 必须真的在声明文件里出现」必红（实际是反向：摘掉后守卫断言列表为空会先红在「不能为空」那条，需按实现核对） |
| 反向 2 | 把 `dynamicSinceBaseline` 改成 243 → `PlatformEpDynamismTest` 的「分界必须高于 since-build」必红 |
| 反向 3 | 把某个 `<sql.dialect>` 从 `database-optional.xml` 搬到 `plugin.xml` → 守卫「只允许落在可选依赖描述文件」必红 |
| 平台事实 | `probe-platform-ep-dynamism.py` 读数：243 非 dynamic（2/2 处）、262 dynamic（1/1 处），与用户真机一致 |

## 五、已知边界（写进用户文档）

- **243（2024.3.x）上更新插件只能重启生效**：`com.intellij.sql.dialect` 在该平台非动态，平台不允许卸载本插件。
  这不是插件缺陷，也不是渠道问题。
- **253 / 262 / 263 上可免重启**（平台已把它标 dynamic）。验热替换请在这些平台上做；
  243 沙盒里验必然看到「重启」。
- beta.2 的 T-DynUpdate263 修复仍然有效且必要（263 上探针要解析能力族），与本次结论不冲突。

## 六、待验证前提

- P1：`ApplicationInfo.getInstance().build.baselineVersion` 在 243/253/262/263 上都读得到且单调（构建期常量表用 baseline 主版本比较）。若某平台读不到，文案回落到「grep idea.log」分支。
- P2：253/262/263 上「本插件可热卸载」目前只有 EP 声明这一条正面证据；用户 262 真机确认是强证据，263 仍需一次真机（验收文档 B2-3）。
- P3：`DynamicEpComplianceGuardTest` 的 XML 解析用正则而非 DOM——若将来描述文件写法变化（如属性跨行/命名空间前缀），解析可能失真；守卫自身断言「解析到非空集合」兜底。

## 七、实施记录

<!-- 待回填：提交哈希与读数 -->
