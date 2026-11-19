---
name: pitfall-inplace-plugin-update-classloader-residue
description: 就地覆盖安装插件后旧 classloader 卸载残留会刷「Light service … classloader is being
  unloaded」十万级 WARN；判据是起点早于 jar 落地、未替换的日志段计数为 0；处置＝重启，别去 src/main 找调用点
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fe142790-fdcd-414a-8e54-f5d6baf781ae
---

2026-11-07 在日常 IDEA 2026.3（`IU-263.7307.200`）用「Install Plugin from Disk」把 `2026.11.7` 换成 `2026.11.8-beta.1` 之后，`idea.log` 被 `Light service cn.song.mybatisplus.visualizer.config.PluginSettings not loaded because classloader is being unloaded` 刷屏：当前日志文件 16634 条，当天所有滚动日志合计 10 万+。

**时间线判据（逐毫秒可对）**：

```
16:15:11,913 平台 SEVERE 落到本插件
16:15:12,112 SqlLogManager dispose            ← 旧实例卸载
16:15:12,391 classloader is being unloaded 开始刷
16:15:15,95  新 jar 写入 plugins/.../lib/
16:15:16,11  新版本加载完成
```

刷屏**起点比文件替换早 3 秒**——平台是先卸载旧插件、再落新包、再加载，所以起点正是旧 classloader 被 dispose 的时刻。旧版本留在 UI 上的对象（预览页签、被 `replaceAction` 接管过的控制台工具按钮）每次重绘成批回问那颗已死的 classloader：批次多为 11 的整数倍（11/22/33/55/77/88/110/242/286），卸载瞬间另有整段爆发（49/60/158）。**具体调用方未取证**，它在旧 classloader 里，新版本代码中不存在这条路径。

**Why**：这类 WARN 极易被当成插件缺陷去查——会一路追「谁在轮询 PluginSettings」，但调用方已经随旧 classloader 卸载，新版本怎么改都消不掉，纯耗轮次。

**How to apply**：
1. 判据先看两条：①起点是否早于插件目录替换；②**未发生替换的日志段**（同日更早的 `idea.N.log`）该串计数是否为 0。本轮 `idea.11.log`/`idea.12.log` 均为 0。
2. 处置只有一条：**重启 IDE**。不要为此改 `src/main`。
3. 反向的验收纪律：就地换包的那个会话里做功能验收，结论一律标「会话态可疑」，只用来取证现象，不用来判定修复达标；报告里要注明「本轮是重启后的干净会话」。
4. 与真缺陷混在一份日志时的分辨法：平台版本决定性的异常（如 263 的 `NoSuchMethodException`）与这堆残留同时出现时，前者独立成立，不要因为「日志很脏」而怀疑或推翻它。

已写进仓内 `docs/LESSONS.md` 的 **L20**（2026-11-07，主仓工作树未提交）。相关：[[project-consolebind263-20261107]]（同一次会话里并存的真缺陷）、[[project-dynamic-unload-barriers]]（热卸载能力面）、[[pitfall-sandbox-log-accumulates-and-default-path]]（同族：日志跨会话累积、必须按启动行切分）。
