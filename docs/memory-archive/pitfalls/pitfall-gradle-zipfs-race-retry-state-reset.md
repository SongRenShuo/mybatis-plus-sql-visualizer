---
name: pitfall-gradle-zipfs-race-retry-state-reset
description: CI 偶发 ClosedFileSystemException（配置期解析 :compileClasspath 读 243 的 database-plugin.jar）——七条重跑/清缓存路线全证伪；jar 本身完好（字节数与本机一致）；只剩「清项目侧 .intellijPlatform 缓存」与「换干净 workspace」两条待验
metadata:
  node_type: memory
  type: project
  updated: 2026-11-06
  originSessionId: sess_1caa1cd6-ecff-4769-98f4-48bfeeca1eb0
---

GitHub Actions 上约 2/7 条腿会踩到（2026-11-05 深夜随腿数增多而变常见），形态固定：

```
Unable to read descriptor [plugin.xml] from [.../caches/9.7.0/transforms/<hash>/transformed/ideaIU-2024.3.7.1-win/plugins/DatabaseTools/lib/database-plugin.jar]
java.nio.file.ClosedFileSystemException
  at jdk.zipfs/jdk.nio.zipfs.ZipFileSystem.ensureOpen(…)
  at com.jetbrains.plugin.structure.fs.FsHandlerFileSystemProvider.checkAccess(…)
  at org.gradle.internal.classpath.declarations.NioFileInterceptors.intercept_exists(…)
  at com.jetbrains.plugin.structure.intellij.resources.JarsResourceResolver.resolveResource(…)
→ 随后报 "Could not find bundled plugin with ID: 'com.intellij.database'"
（失败点在**配置期**：Could not determine the dependencies of task ':compileMcpStubJava'）
```

## 五条路线全证伪（别再走）

| 处置 | 结果 |
|---|---|
| 原地重跑（同 workspace） | ❌ 第二次 4 秒同样失败 |
| `--stop` 换新 daemon | ❌ 11 秒同样失败 |
| `--stop` + `--max-workers=1` | ❌ run#17 |
| `--stop` + 删 `transforms/*/transformed/idea*` | ❌ run#23（1m39s）+ run#24 两条腿（1m32/1m41s） |
| `--stop` + 删整个 `transforms` + 删 `modules-2/files-2.1/{com.jetbrains.intellij.idea,idea}` | ❌ run#24：**1m32s 就再次失败，且没有触发重新下载**（1GB 发行版不可能一分半下完）→ 坏的不是磁盘字节 |
| `--no-configuration-cache`（换执行路径） | ❌ run#25 第三级：54s 同错 |
| 再加 `-Dorg.gradle.vfs.watch=false` | ❌ run#25 第四级：16s 同错 |

## 结论（run#25 之后）

两条关键事实：①这个 jar **完好**（run#25 诊断：147,727,903 字节，与本机字节数完全一致，mtime 正常）；
②同一 workspace 里重跑多少次都同样失败，连关配置缓存、关 VFS 监听都不变——所以既不是文件坏，
也不是 JVM 状态，而是**这份 workspace 上的确定性失败序**。

现在最像的因：IPGP 把 IDE 的布局索引（product-info / bundled plugin 描述符路径）缓存在**项目目录**
`.intellijPlatform/layoutIndex/*.json` 与 `extracted-plugins/`。前面每一级都在清 GRADLE_USER_HOME，
方向错了。第三、四级改为清**项目侧**：
- 三级：删 `.intellijPlatform/layoutIndex` + `extracted-plugins` + `transforms/*/transformed/idea*`，再跑
- 四级：`git clean -xdf -e .local`（**仅 CI**，本机跳过以免抹掉别人的工作树）后重跑

**操作层面最重要的一条**：手动「Re-run failed jobs」会分配全新 workspace，比脚本级任何重跑都可靠——
遇到这形态先点重跑，别在同一个 workspace 里反复试。

若四级仍红：别再加级别。下一步是让 IDE 不再是 zip FS——IPGP 支持 `intellijPlatform.localPath`
指向**解包好的普通目录**，CI 预先把发行版解成目录即可从根上绕开（代价：改 workflow 与缓存键）。

相关：[[pitfall-ci-powershell-in-workflow-needs-local-dryrun]]、[[pitfall-ipgp-product-releases-cache-stale]]
