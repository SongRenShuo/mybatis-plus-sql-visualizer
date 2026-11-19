---
name: pitfall-fffd-repair-positional
description: 中文文档写入后出现 U+FFFD 乱码时 Edit 工具匹配不上、heredoc 里的转义字面量会被吞——只能用「位置法」从后往前替换并跨调用回读校验
metadata:
  node_type: memory
  type: project
  originSessionId: session_a5cf3aa3-fe1e-4c1f-b9c6-9af6c299300c
---

20261026 一次会话内三犯（报告 405 行文件 9 处、计划文件 8 处、记忆文件 1 处）：用 Write 工具写长中文文档后，文件里出现 **U+FFFD**（替换字符），通常是 2~3 个连续成一处。

**两个坑**：

1. **Edit 工具匹配不上**——`old_string` 里不含那个 U+FFFD 就永远 MISS（`String to replace not found`）；把该字符本身写进 `old_string` 也不可靠（输出侧会被吞）。
2. **heredoc 里的转义字面量会被吞**——`python - <<'PYEOF'` 里写 `F='\uFFFD'` 再拼进 old_string，实际传进去的不是那个字符（实测 8 条 fix 全 MISS，且 MISS 回显里字符数也不对）。

**正解＝位置法**（一次成功）：

```python
import io, re
s = io.open(path, encoding='utf-8').read()
runs = [(m.start(), m.end()) for m in re.finditer('\uFFFD+', s)]  # 取每个 run 的区间
reps = ['\u5e93', '\uff09']                                        # 替换字一律用 \uXXXX 转义，别写中文
assert len(runs) == len(reps)
for (a, b), r in zip(reversed(runs), reversed(reps)):              # 从后往前，位置不漂
    s = s[:a] + r + s[b:]
io.open(path, 'w', encoding='utf-8', newline='').write(s)
```

先跑一遍只打印 `repr(s[a-30:b+30])` 定位每处该补什么字，再跑替换。

**新增观测（2026-11-02）**：U+FFFD 也会出现在**对话回复正文**而不只是文件里——上一轮回复中「同一现场」的「一」显示成 `同���现场`。机制可辨：一个汉字（UTF-8 三字节 `E4 B8 80`）逐字节解码失败 → 落成**三个**连续 U+FFFD，所以看到「3 个替换符顶一个汉字」就是字节级解码失败，不是原文本被写坏。判据与处置：用 `grep -c $'\xef\xbf\xbd' <file>` 实测落盘文件（本次计划册与索引均 0 处，说明只污染了显示链路），产物干净就不必「修」，重述那句即可；回复正文里的乱码无法事后修补，只能靠用户指位置重述。

**必做**：替换后**跨调用回读校验**（另起一次 Bash 读回 `s.count('\uFFFD')` 与关键短语在场）——AGENTS.md 已记「Bash 内 python 写文件可能不落盘」，同进程回读成功不作数。

相关：[[git-bash-cjk-mojibake-fix]]（控制台代码页 936 致输出侧乱码，与本条不同根因）。
