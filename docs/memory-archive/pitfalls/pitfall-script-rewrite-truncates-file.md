---
name: pitfall-script-rewrite-truncates-file
description: 用 python 做「去重/删块」类文件重写时，str.split 会匹配到第一处而非目标处——本仓实测把
  .run/runIde.run.xml 截成 2 行；正则去重块必须锚定序号或用切片索引
metadata:
  node_type: memory
  type: project
  originSessionId: session_0ef9f513-039c-40f5-a751-348fc301d17b
---

2026-10-22 我为了删掉 `.run/runIde.run.xml` 里重复的 `<configuration>` 块，写了这样的去重脚本：

```python
blocks = re.findall(r'  <configuration .*?</configuration>', raw, re.S)
new = raw.split(blocks[1], 1)[0] + "</component>"   # ← 本想「切掉第二块之后」
```

结果文件被截成 `<component ...>` + `</component>` 两行——**因为两块内容逐字节相同，`split(blocks[1], 1)` 匹配到的是第一处**。幸好 `git checkout -- <file>` 一次就还原，没有留下损失。

## 可复用教训

- **「按内容定位切分」在同内容重复时必然错位**。删第 N 个同构块，别用 `split(内容)`，改成按正则 `finditer` 的 `span()` 用**绝对偏移切片**（`raw[:m.start()] + raw[m.end():]`），或用 `count=0` 的 `re.sub` 精确替换一次。
- 重写前先做**形状断言**并让脚本自己拒绝执行（`assert len(blocks)==2 and blocks[0]==blocks[1]`）——本仓要求的「先核验再落盘」在这里同样适用。
- 这类一次性脚本同样受 [[pitfall-tool-echo-vs-disk-state]] 约束：判定以 `git diff` / `git status` 为准，不信回执。

## 本次上下文（.run/runIde.run.xml 的真实状态）

- 该文件在 HEAD 里就有**两份同名同内容**的 `Run IDE with Plugin` 配置块，是 `5a087105`（2026-09-17「补录遗留未提交改动」）带进来的；IDEA 通常按名称去重，实际不影响使用。
- 工作区当时是**三份**（IDEA 反复保存又追加了一份）。判定结论：**不需要提交**——提交只会让重复从 2 份变 3 份。已 `git checkout --` 还原，工作区与 HEAD 一致。
- 要清冗余须单独做「只删一个重复块」的提交（保留一份），并在 dev 同步；用户未要求前不做。

## 同族第二次（2026-10-24，Kotlin 测试文件）：按行号删用例块也会切坏结构

改守卫测试时用 bash 内 python「按行号区间删除一个 `@Test` 块」（`lines[:start] + lines[end:]`），
`end` 取「下一个 `@Test` 或 `}`」——**`}` 先命中类结束**，于是把类尾与相邻用例一并吞掉，
留下重复用例（`Conflicting overloads`）与多余 `}`（`Syntax error: Expecting a top level declaration`）。
连修两轮才靠 `git checkout HEAD -- <file>` 整体还原、再用「行号区间精确替换 + 尾部形状断言」重做。

**补两条纪律**：
- 删块脚本的 `end` 判据**不能只看 `}`**——Kotlin 类体、lambda、when 分支都以 `}` 收尾；改判「下一个同级
  `@Test` 注解行」并先 `assert` 切出的片段里含 `fun \`目标用例名\``。
- 这类文件一旦被切坏，**优先 `git checkout HEAD -- <file>` 整体还原再重做**，别在残骸上二次修补
  （修补脚本本身也会用同样的错误判据）。

相关：[[project-parallel-session-batch-staging]]（提交前只动自己的路径）、[[project-line-ending-policy]]（`.run/*.xml` 属 CRLF 检出、`git checkout` 后无差异即正常）、[[pitfall-tool-echo-vs-disk-state]]（以 `git diff`/编译读数判落盘）。
