---
name: pitfall-ipgp-product-releases-cache-stale
description: 升级验证目标 IDE 当天，IPGP 报「Couldn't resolve IntellijIdea download URL for version: '2026.3'」而官方链接已存在——因为它读的是项目内缓存的 product-releases 清单；删 .intellijPlatform/productReleases 即可
metadata:
  node_type: memory
  type: project
  updated: 2026-11-05
  originSessionId: sess_1caa1cd6-ecff-4769-98f4-48bfeeca1eb0
---

2026-11-05 把验证目标从 2026.2.4 升到 2026.3 时踩到，现象有误导性：

```
Could not determine the dependencies of task ':verifyPlugin'.
> Couldn't resolve IntellijIdea download URL for version: '2026.3'
```

而 JetBrains 官方当天已发布（`data.services.jetbrains.com` 返回 `version=2026.3 / build=263.7307.200`，
`downloads.windowsZip.link = https://download.jetbrains.com/idea/idea-2026.3.win.zip` 都在）。

## 根因

IPGP 读的是**项目内缓存**的 product-releases 清单，不是每次现拉：

```
[org.jetbrains.intellij.platform] Reading product releases listing from cache:
  <repo>/.intellijPlatform/productReleases/<hash>.json
```

该目录 `.gitignore` 第 2 行已忽略。缓存若抓取于新版本发布之前，就永远解析不到新版本。
（本轮实测缓存文件 mtime 是发布当天 01:27，而发布信息含 2026-11-05 的新 build。）

## 处置与判据

```bash
rm -rf .intellijPlatform/productReleases     # 或只删对应 hash 的 json
./gradlew :verifyPlugin -PverifyIdes=263     # 会重新拉清单并下载新 IDE
```

判据：日志不再出现 `Couldn't resolve … download URL`；verifier 打出
`Finished 1 of 1 verifications (…): IU-263.7307.200 … Compatible`。

## 复用要点

- 换版本先 `--info` 看它读的是缓存还是网络；报「解析不到下载地址」而官网有 = 先删缓存，别去改版本串。
- 版本串要按官方原样写（2026.3 是两段式，不是 2026.3.0）；目录名（`idea-2026.3-win`）必须等下载后
  看 `caches/*/transforms/*/transformed/` 实际名字再定，别猜。
- **CI 不受影响**：每次 workspace 干净，没有这份缓存。

相关：[[pitfall-ci-powershell-in-workflow-needs-local-dryrun]]
