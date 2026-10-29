---
name: project-history-rewrite-20261019
description: 2026-10-19 已收口：删 1.3GB java_pid14000.hprof 的全史重写、强推、27 个 Gitee 发行版 target_commitish 更新、文档/记忆旧 SHA 全量替换
metadata:
  node_type: memory
  type: project
  originSessionId: sess_edf144a6-16e4-4ad2-8194-48ea13d0b736
---

2026-10-19 用户决定「单独清理」仓库里的 1.3GB `java_pid14000.hprof`（2026-08-21 误提交、6 秒后删除；仍在全部分支与 27 个 tag 的历史里，必须重写整条历史）。目标文件 `git cat-file -s` 实测 1,317,038,777 字节，远端 Gitee 一直在报「有 1 个文件大于 50MB」。

**执行事实**：先做 `git clone --mirror` 全量备份（180M），再跑 `git filter-branch --index-filter 'git rm --cached --ignore-unmatch java_pid14000.hprof' --tag-name-filter cat -- --all`（EXIT=0，3843 个提交全改写，27 tag 全换 SHA）。

**三个坑**（详见 [[pitfall-batch-history-rewrite-windows]]）：
1. `git filter-repo` 的 fast-import 在 Windows 报 `cannot truncate pack to skip duplicate: Invalid argument`，只能退回 filter-branch；
2. 崩溃留下 `pack-cf1042a6*.pack` 残包（SHA1 mismatch、`f2742ae3` 单对象 CRC 坏，`gc`/`pack-objects` 全被噎住）——该对象内容读不出来，不能简单删包，只能从镜像备份重做一遍干净改写；
3. 用户中途误 `git pull` 把未重写的 `origin/master` 合回 master（`071195d8`），旧历史与大 blob 立刻重新可达——`git reset --hard <改写 tip>` 撤销，三棵树同哈希、`git diff` 为空证明零内容损失。

**终态（2026-10-19 已完成）**：
- 本地 `.git` 184M→52M；hprof blob `git cat-file -e` 失败、`rev-list --objects --all` 0 命中；`git fsck --full` 无错。
- 远端 3 分支与 27 tag 强推完成；终态 tip：master `6974aa9d`、dev `087c336d`、feature/kotlin-to-java `e959e057`；四个 sql-insight 本地分支为 `9c43efec`/`8abf93b6`/`800f32b9`/`be841dec`（本地未推）。
- 27 个 Gitee 发行版全部 PATCH 成功，回读 `target_commitish` 已落新 tag、`created_at`/name/body 一字未变（2026.10.13 与 2026.10.16 正文各含 1 处旧 SHA，已按映射替换正文并二次 PATCH）。**Gitee PATCH 必须同时带 `tag_name`+`name`+`body`**，只传 `target_commitish` 会 400。
- 全新镜像克隆验证：hprof 0 命中、体积 55M、远端 `.port-sync` 锚点已是 `39473a6b`。
- 文档/记忆旧 SHA 全量替换：主集合 12,548 处/1,215 文件；dev 7,116/684；k2j 7,629/680；四个 insight 树各约 7,000/687；均已提交，master/dev/k2j 已推送。剩余未替换 token 只对应 SHA 未变的早期提交（GPG 签名提交之前）。
- 并发事故：同步 ref 时误把 `t-sql-insight` 从并发提交 `656bc1e9` 回退到 `684ad517`，已 `git update-ref ... 656bc1e9 684ad517` 恢复，工作树/对象无损失。
- 备份保留：`.git-backups/mpsv-pre-hprof-20261019.git`（改前全量）、`rewrite.git`（干净改写）、`memory-backup-pre-remap`、`global-memory-backup-pre-remap`、`corrupt-pack-cf1042.*`；确认稳定后可删。

**GPG 签名损失（用户 2026-10-19 追问「add LICENSE. 要不要重建」→ 结论：不用）**：全仓 3843 个提交里**只有 1 个带签名**＝`19ba8164 add LICENSE.`，签名人是 `Gitee <noreply@gitee.com>`（Gitee 网页端代签的平台签名，不是用户个人 GPG 密钥），作用仅证明「网页提交」。重写后它变成 `4d65a6ee`，tree 同为 `77f49af0…`、作者/邮箱/时间/说明完全一致，唯一差异是少了 `gpgsig` 头。**签名本来就必然失效**（父提交变了、哈希变了，旧签名验证不过；留着只会显示 bad signature，剥掉才是对的），且无人依赖：`master` 的 `protected` 字段为 false（强推成功即实证）、build/CI 无 `gpg`/`verify-commit` 校验、27 个发行版看 tag 树不看提交签名。**重建提交是反效果**——再改一次历史（后代 SHA 又全变）、再强推、再更新一轮发行版，且拿不回平台签名。原始带签名提交完整保留在改前镜像备份 `mpsv-pre-hprof-20261019.git` 的 `19ba8164`。

**Why:** 并行会话/手滑可能把未重写历史「救活」（一次误 pull 就够）；Gitee 发行版即使 tag 被强推也不会自动换 `target_commitish`，不逐个改就是一堆指向幽灵提交的发行版；而签名丢失看着吓人实则零影响，别为它二次清史。

**How to apply:** 后续任何 pull/fetch 前先确认远端 tag 已是重写后的 SHA。**不要再删 `refs/original` 之外的历史引用前不 gc**——引用还在时 blob 仍可达。相关：[[pitfall-filter-branch-rewrite-detection]]、[[project-release-workflow]]（tag 前移不删发行版、须回读 target_commitish）、[[project-competitor-docs-open-source-exposure]]。
