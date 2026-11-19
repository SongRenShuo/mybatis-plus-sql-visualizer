---
name: pitfall-batch-history-rewrite-windows
description: Windows 上批量清历史的三条实测坑——filter-repo fast-import 截断崩、崩溃残包 CRC 坏会噎住
  gc/pack-objects、残包对象可能真的读不出来只能从镜像备份重做
metadata:
  node_type: memory
  type: project
  originSessionId: sess_aca5ae95-37d5-43d9-88ce-ee5b335cc6f7
---

2026-10-19 在 Windows + NTFS（F: 盘，69G 可用）上对本仓做「删一个 1.3GB blob 的整史重写」，三条实测坑：

1. **`git filter-repo` 在 fast-import 阶段崩**：`cannot truncate pack to skip duplicate: Invalid argument` → `BrokenPipeError`，进程死在第 300 多个提交。下载单文件脚本即可用（`curl https://raw.githubusercontent.com/newren/git-filter-repo/main/git-filter-repo`，`python git-filter-repo.py --version`），但实际能跑通的是内置 `git filter-branch --index-filter 'git rm --cached --ignore-unmatch <path>' --tag-name-filter cat -- --all`——index-filter 不读 blob 内容，天然绕开超大对象的截断路径。另注意 filter-repo 没有 `--no-reset` 参数（会报 unrecognized arguments）。
2. **崩溃留下的残包会毒化后续所有打包操作**：`verify-pack` 报 `pack is corrupted (SHA1 mismatch)`，`git gc` 报 `bad packed object CRC for <sha>` + `failed to run repack`（退出码却是 0，判据要读 stderr 不能只看退出码），`git pack-objects --no-reuse-delta` 同样被同一个坏对象噎住直接吐出 `error:` 当包名。**`git unpack-objects -r` 会在尾部校验失败时整体放弃**，一个对象都没解出来——别以为「解包一次就能救回」。
3. **坏包里的对象可能真的读不出来**：本例 276 个对象里 275 个可 `cat-file -t`，唯独那个 CRC 坏的对象不行。此时「删掉坏包」会让 7 个旧 tag 指向的提交一起消失（实测：删后 `git rev-parse <tag>` 直接 unknown revision），**唯一安全路线是从 `--mirror` 备份重做一遍改写**，让新历史不依赖这些对象。

**Why:** 误以为「坏包里的对象都能单独读出来，所以解包/重打包就行」会走到删包丢对象那一步；而「gc 退出码 0」又让失败静默通过。

4. **改完历史只是第一步，文档里的旧短 SHA 要单独全量替换**（2026-10-19 用户要求）：`docs/`、变更记录、`docs/memory-archive`、项目记忆目录里散着上万处 7 位旧 SHA（本仓实测 12,566 处 / 1,227 文件），不替换会持续误导后续检索。做法＝按提交元数据（作者/提交时间/标题）建旧→新映射，**只替换能唯一命中旧提交前缀的 16 进制 token**（日期串与校验和不命中；旧仓 `/` 新仓 `/`），7 位就换 7 位、40 位换 40 位；替换前先整目录备份记忆、先 dry-run 计数。实测**零歧义**（没有 7 位 token 同时命中两个旧提交），所以不需要保守跳过。同理还有 `feature/kotlin-to-java` 的 `.port-sync` 锚点必须手工换成新 SHA。

**How to apply:** 批量重写前先 `git clone --mirror` 到仓外目录（本例 180M，与重写后历史无关，可单独删除）；重写在**镜像副本里再跑一遍**作为兜底（干净副本上 filter-branch 约 1000~4300s 量级，3843 提交）；判断残包是否致命：`git cat-file -e` 逐个探对象可达性，全过才谈得上重打包。收尾判据不是 `gc` 退出码，而是 `git rev-list --objects --all | grep <blob>` 为 0 且 `git fsck` 无缺对象。相关：[[project-history-rewrite-20261019]]。
