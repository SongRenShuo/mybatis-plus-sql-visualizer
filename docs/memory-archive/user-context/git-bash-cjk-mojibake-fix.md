---
name: git-bash-cjk-mojibake-fix
description: Git Bash 中文乱码根因（控制台代码页 936 vs UTF-8 输出）与三层处置：git 编码项、~/.bashrc chcp
  65001、输出侧判据纪律
metadata:
  node_type: memory
  type: project
  originSessionId: sess_2b5062e9-21ee-418e-83b4-1ca64f07a665
---

2026-10-10 实测根因：**Windows 控制台代码页 936（GBK），而 Git Bash 与各工具输出的是 UTF-8 字节** → 消费端按 GBK 解码即乱码（`中文` → `涓�鏂囧`）。**文件本身无损**（同日用 `git hash-object --path` 比对：带过滤器哈希 == 索引哈希）。

三层处置（均已落地）：

1. **git 显示层（全局配置，已设）**：`core.quotepath=false`——CJK 路径不再被转义成八进制串（此前 `git add <中文路径>` 报 `did not match any files` 的元凶，也是 `git checkout-index` 匹配不上文件的原因）；另设 `i18n.logOutputEncoding=utf-8`、`i18n.commitEncoding=utf-8`、`gui.encoding=utf-8`。回退：`git config --global --unset <键>`。
2. **终端层（机器配置，已建）**：新建 `~/.bashrc`（`chcp.com 65001 >/dev/null 2>&1`）+ `~/.bash_profile`（source .bashrc——mintty 以登录 shell 启动，默认不读 .bashrc）。两个文件此前都不存在，删除即回退。
3. **输出侧纪律（做验证时用）**：不要把中文文档 `cat`/`sed`/`awk` 打到 stdout 再肉眼核，改用 ①`grep -c` / `grep -a -o` 只取计数或 ASCII 片段；②哈希/字节判据（`git hash-object --path <f> <f>`、`od -c`、`git ls-files --eol`）；③Read 工具直接读文件；④python `io.open(..., encoding='utf-8')` 只读解析。**另：`grep` 遇到含非 UTF-8 字节的日志会按二进制处理而静默返回空**（本轮实测两次），此时加 `-a` 强制文本模式。

**同会话内两种工具输出不一致 ≠ 文件坏了（20261029 实测）**：同一个 UTF-8 文件（`docs/索引/开发计划.md`）在 `grep -n` 输出里中文正常，隔几条命令后 `sed -n '1,10p'` 却整段乱码——是控制台代码页/工具链在会话里抖动，**文件无损**。判据仍走第 3 条纪律（Read 工具或 python `io.open(..., encoding='utf-8')`），别据此去「修」文件。另：`javac` 不指定 `-encoding UTF-8` 时按 GBK 读源，带中文注释的 `.java` 直接报「编码GBK的不可映射字符」编译失败（本机 JDK8 实测）——写探针一律带该参数。

**Why:** 乱码发生在「显示/解码」层，不在数据层；把两者混为一谈会去改文件——危险且无用。

**How to apply:** 中文相关的提交信息与路径一律走 `git commit -F <文件>` / `git add --pathspec-from-file=<清单>`，不经 shell 传参；验证一律用计数/哈希判据而非肉眼看输出。关联 [[project-line-ending-policy]]、[[project-doc-conventions]]。
