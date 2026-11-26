# 07 建仓、打包、上架与许可合规

调研对象：`F:\code\Java\Github\other\openidea`（main @ `a7b67347c`，2026-11-25 拉取，工作区版本 `0.1.51`，`wit/` 为 `openidea:plugin@1.16.0`）与登记仓库 `F:\code\Java\Github\other\openidea-plugins`。
已发布基线：`v0.1.51`（2026-11-24 发布）随附 **插件 API 1.15**（release 正文含「Plugin API 1.15」；已发布快照 `sdk/rust/openidea-plugin/tests/api/released/*.wit:1` 为 `openidea:plugin@1.15.0`），`main` 上的 1.16.0 仍在开发（`plugins/README.md:3116`、`docs/zh/plugins/versioning.mdx:8`）。

本册只回答「怎么发、能发什么、不能发什么」，不重复 01 分册的 API 面测绘与 02 分册的官方 mybatis 插件范式。结论按 00-README 的四档口径（现成可做 / 变通可做 / 需上游 / 不可行）标注。

---

## 0. 结论速览

| 问题 | 结论 | 档位 | 证据 |
| --- | --- | --- | --- |
| 能否在新仓从零建一个 OpenIDEA 插件并让市场可装 | 能；`new → build → pack → release 附件 → 登记仓库 PR` 五段闭环，官方工具全自动 | 现成可做 | `crates/ide-host/src/bin/openidea-plugin/main.rs:31-63`、`docs/zh/plugins/publishing/marketplace.mdx:27-77` |
| 脚手架是否给 CI / 测试目录 / web 目录 | 都不给。只生成 5 个文件；CI、`web-src/`、`locales/`、`icons/` 全要自己加 | 现成可做（手工） | `main.rs` 旁 `templates.rs:54-60` |
| `check` 能否替代「装一遍试试」 | 能。`check` 走的就是安装页同一个 `preview_install` + `validate_package`，含建链与权限清单 | 现成可做 | `crates/ide-host/src/plugins/mod.rs:918-963`、`2502-2519` |
| 源码是否进 `.oiplugin` 包 | 不进。只收清单引用到的文件 + `wasm` + 许可证/README | 现成可做 | `main.rs:556-587`、`market.rs:514-523` |
| 能否闭源 | 代码可以不公开，但**不能上官方注册表**（规则要求公开源码仓 + 开源许可）；只能自建索引 / Release / 磁盘分发 | 不可行（对官方市场） | `openidea-plugins/README.md`「Rules」节、`docs/zh/plugins/publishing/policy.mdx:63` |
| 能否做自有更新源 | 能。静态 `index.json` + 任意 HTTPS/文件夹，设置里填索引 URL；但 IDE 只读一个索引，须 `merge` 官方索引 | 现成可做 | `docs/zh/plugins/publishing/self-hosted.mdx:42-51`、`crates/ide-host/src/plugins/market.rs:232-264` |
| JSqlParser 随二进制分发的义务 | 双许可，择 Apache-2.0 即无 LGPL 义务；须附许可证与归属 | 现成可做 | 本册 §5.2 |
| GPL 竞品代码 | `weigram.hotreload` 为 GPL-2.0，任何代码/字符串/资源都不可并入 Apache-2.0 工程 | 不可行 | GitHub license API 实测（本册 §5.5） |
| 预览态 API 风险 | 已发布 1.x 只增不改；破坏性变更收敛到 2.0（WASI 0.3 异步），但文档明写「预览版，main 上未发布内容发布前仍可能变化」 | 有义务（跟版重建） | `versioning.mdx:8`、`plugins/README.md:3112-3149` |

---

## A. 从零建仓到市场可装：完整步骤清单

### A.1 前置环境

| 项 | 要求 | 证据 |
| --- | --- | --- |
| Rust | stable ≥ 1.96（工作区 `rust-version = "1.96"`） | `Cargo.toml:10` |
| wasm target | `wasm32-wasip2`（`build` 发现缺失会自动 `rustup target add`） | `main.rs:339-346` |
| Node / pnpm | Node 22.x、pnpm 11（插件 web 页是独立 pnpm 工程） | `.github/workflows/ci.yml:265-267`、`plugins/build.sh:8-14` |
| 官方工具 | `openidea-plugin`（release 附件 `openidea-plugin-linux-x86_64.gz`，或 `cargo install --locked --git https://github.com/l0ng-ai/openidea ide-host --bin openidea-plugin --tag v0.1.51`） | `openidea-plugins/README.md:35-37`、`docs/zh/plugins/quickstart.mdx:14-22` |
| 目标 API | 按 1.15 起（`engines.openidea = "^1.15"`），SDK 依赖 pin 在 `v0.1.51` | 本册 §A.2 步骤 3 |

工具版本与 API 版本的对应关系是硬约束：`new` 写进 `Cargo.toml` 的 SDK 依赖用**工具自身版本号**作 tag（`main.rs:240-258`，`format!("{{ git = \"{REPOSITORY}\", tag = \"v{}\" }}", env!("CARGO_PKG_VERSION"))`），所以拿到 `openidea-plugin 0.1.51` 生成的就是 `tag = "v0.1.51"`，编出来的组件报 `plugin API 1.15`。用 `main` 编的工具会生成指向未发布 WIT 的依赖，组件在任何已发布 IDE 上都会被 `check` 拒绝（`versioning.mdx:66-71`）。

### A.2 步骤表（可复制命令 + 产物 + 校验点）

| # | 动作 | 命令 | 产物 | 校验点 |
| --- | --- | --- | --- | --- |
| 1 | 建目录并生成脚手架 | `openidea-plugin new <repo> --id <publisher>.<name> --name "<显示名>"` | `plugin.toml`、`Cargo.toml`、`src/lib.rs`、`.gitignore`、`README.md` | 命令尾部打印 `created …`，且内部已跑过 `Manifest::load`（`main.rs:195`）；随后 `openidea-plugin check <repo>` 应输出 `ok` |
| 2 | 初始化仓库并锁定依赖 | `git init && git add -A && git commit -m "init"` | 提交含 `Cargo.toml`；建议同时提交 `Cargo.lock` | `openidea-plugin --version` 打印的 API 版本须为 1.15 |
| 3 | 固定 SDK 版本 | 检查 `Cargo.toml` 的 `openidea-plugin = { git = "...", tag = "v0.1.51" }` | 版本钉子 | `grep -n "openidea-plugin" Cargo.toml` |
| 4 | 补目录（见 A.3） | 手工建 `web-src/`、`locales/`、`icons/`、`tests/` | 目录骨架 | `openidea-plugin check .` 仍 `ok` |
| 5 | 写逻辑与单测 | `cargo test` | 模拟宿主下的测试通过 | `MockHost` 无需 IDE（`docs/zh/plugins/testing.mdx:8-36`） |
| 6 | 构建组件 | `openidea-plugin build` | `dist/plugin.wasm`（+ 打印尺寸与 API 版本） | 末尾自动跑 `check`，失败即停（`main.rs:324`） |
| 7 | 本地热更 | `openidea-plugin link .` → 设置 › 插件 → 详情「从磁盘重新加载」 | 向 `$OPENIDEA_PLUGINS_DIR/links.json` 追加路径 | `openidea-plugin links` 能列出；插件出现在「为开发而链接」分组 |
| 8 | 打包（未签名自测） | `openidea-plugin pack .` | `<id>-<version>.oiplugin` | 命令先跑 `check`（`main.rs:559`） |
| 9 | 生成发布者密钥（一次性） | `openidea-plugin keygen <publisher>.key` | 32 字节 seed 的 base64 文件；Unix 下 0600；拒绝覆盖已有文件 | 命令打印公钥（`main.rs:524`） |
| 10 | 签名打包 | `openidea-plugin pack . --key <publisher>.key --publisher <publisher>` | 含 `SIGNATURE.json` 的包 | id 必须以 `<publisher>.` 开头，否则直接拒绝（`main.rs:565-567`） |
| 11 | 记摘要 | `sha256sum <id>-<version>.oiplugin`（Windows：`certutil -hashfile … SHA256`） | 64 位十六进制 | 登记仓库 CI 会重算并比对（`openidea-plugins/README.md:47-52`） |
| 12 | 发 Release 附件 | `gh release create v<version> <id>-<version>.oiplugin` | Release 资产 | 资产大小与本地一致 |
| 13 | 提交登记仓库 PR | 新增 `publishers/<publisher>.toml` 与 `plugins/<publisher>.<name>.toml` | 两个 TOML | CI：`registry.py validate` → 下载包 → `openidea-plugin check` → 验签 → 重建 `index.json`（`.github/workflows/registry.yml:32-78`） |
| 14 | 回读断言 | 见 A.6 | 四处读数 | 全绿才算上架 |

### A.3 目录结构建议

官方仓库自己的布局可直接借用（`plugins/build.sh:1-14`、`plugins/official/*/`）：

| 路径 | 进包？ | 说明 |
| --- | --- | --- |
| `plugin.toml` | 是 | 清单，所有路径相对包目录且不得跳出去（`manifest.rs:570-576`） |
| `Cargo.toml` / `Cargo.lock` | 否 | 源码侧；`Cargo.lock` 建议提交以锁依赖 |
| `src/**` | 否 | 只有 `wasm` 指定的产物进包 |
| `dist/plugin.wasm` | 是 | `wasm = "dist/plugin.wasm"`；官方仓把组件提交进版本库并配 `.sources` 指纹（`plugins/build.sh:20-24`） |
| `web-src/`（pnpm + Vite） | 否 | web 页面源码；官方仓 `web/` 不入库（`plugins/build.sh:8-14`） |
| `web/`（构建产物） | 是（整目录） | 清单里 `web = "web/index.html"`，该页所在目录**整目录**随包安装；上限 4096 文件 / 256 MiB，不允许链接（`plugins/README.md:2138-2140`） |
| `locales/<tag>.json` | 是 | 不需要在清单声明，目录存在即自动收集（`crates/ide-extension/src/registry.rs:288-301`） |
| `icons/icon.svg` / `icon_dark.svg` | 是 | 每个 ≤16 KiB，SVG 会被消毒；用 `icon` 必须 `engines.openidea ≥ ^1.2`（`crates/ide-host/src/plugins/icons.rs:40`、`manifest.rs:344-347`） |
| `skills/<name>/SKILL.md` | 是 | API 1.15 声明式 `[[contributes.skills]]`；单技能 ≤2 MiB、不得有指向包外的软链（`plugins/README.md:481-487`、`crates/ide-host/src/plugins/skills.rs:98-140`） |
| `tests/` | 否 | 集成测试放这里；单测走 `src/lib.rs` 内 `#[cfg(test)]`（模板自带，`templates.rs:164-186`） |
| `agent/*.jar` | 是 | 仅 javaAgent 路线；一插件一个，路径出包即清单拒绝（`plugins/README.md:2498-2543`） |
| `LICENSE` / `NOTICE` / `README.md` | 是 | `pack` 无条件附加这几类文件（`main.rs:573-577`） |

清单字段是**闭合集合**：`Raw` 结构体 `deny_unknown_fields`（`manifest.rs:187-189`），多写一个自造字段（例如把 `engines.openidea` 写成 `engine`）直接解析失败；`engines.openidea` 与老格式 `openidea-api` 同时出现也报错（`manifest.rs:251-256`）。

### A.4 本地热更回路

三段式，全部由工具承担：

1. `openidea-plugin build` → 组件落到清单 `wasm` 指向的位置，并立即 `check`；
2. `openidea-plugin link .` → 往 IDE 插件目录的 `links.json` 写绝对路径（`main.rs:448-486`），插件目录取 `$OPENIDEA_PLUGINS_DIR`，否则应用数据目录下 `ai.l0ng.openidea/plugins`（`main.rs:425-432`）；
3. 设置 › 插件 打开或「重新扫描插件」即重读；细节面板「从磁盘重新加载 / 重新加载插件」动作可以只刷一个插件（`docs/zh/plugins/quickstart.mdx:98-104`）。

注意点：链接是被授权的「按文件夹」开发插件，与已安装版本同 id 时安装会被拒（`mod.rs:977-984`）。远程项目下项目插件（`.openidea/plugins`）不跑（`plugins/README.md:2729`）。

### A.5 CI 模板

官方仓没有给插件仓用的可复用 workflow，但两段现成脚本可直接抄结构：主仓 `ci.yml` 的 `plugins` job（`ci.yml:222-285`）与登记仓库 `registry.yml`。建议新仓用下面这套（build 与 release 分两个文件或两段）：

```yaml
# .github/workflows/plugin.yml
name: plugin
on:
  pull_request:
  push: { branches: [main], tags: ["v*"] }
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
        with: { targets: wasm32-wasip2, components: "clippy,rustfmt" }
      - uses: pnpm/action-setup@v4
        with: { version: 11 }
      - uses: actions/setup-node@v4
        with: { node-version: 22 }
      # web 页：独立 pnpm 工程，必须用相对路径构建
      - run: pnpm --dir web-src install --frozen-lockfile --ignore-workspace
      - run: pnpm --dir web-src run build          # 产出 web/，Vite 用 base: "./"
      - run: cargo fmt --check
      - run: cargo clippy --locked --all-targets -- -D warnings
      - run: cargo clippy --locked --target wasm32-wasip2 -- -D warnings
      - run: cargo test --locked
      - name: openidea-plugin
        run: |
          tag=v0.1.51
          gh release download "$tag" --repo l0ng-ai/openidea -p openidea-plugin-linux-x86_64.gz -D /tmp
          gunzip -c /tmp/openidea-plugin-linux-x86_64.gz > /usr/local/bin/openidea-plugin
          chmod +x /usr/local/bin/openidea-plugin
          openidea-plugin --version
      - run: openidea-plugin check .
      # 打包按 tag 触发，密钥只在这一段可用
      - if: startsWith(github.ref, 'refs/tags/v')
        env: { SIGNING_KEY: "${{ secrets.PLUGIN_SIGNING_KEY }}" }
        run: |
          set -euo pipefail
          umask 077; printf '%s' "$SIGNING_KEY" > /tmp/publisher.key
          trap 'rm -f /tmp/publisher.key' EXIT
          openidea-plugin pack . --key /tmp/publisher.key --publisher "$PUBLISHER" --out "dist/$ID-$VERSION.oiplugin"
          sha256sum "dist/"*.oiplugin | tee dist/SHA256SUMS
      - if: startsWith(github.ref, 'refs/tags/v')
        run: gh release upload "${GITHUB_REF_NAME}" dist/*.oiplugin dist/SHA256SUMS --clobber
        env: { GH_TOKEN: "${{ secrets.GITHUB_TOKEN }}" }
```

要点（都有实测依据）：

- `pack` 内部先 `check`，但**不校验 `dist/plugin.wasm` 是否由当前源码构建**——官方仓靠 `plugins/build.sh --check` 的 `.sources` 指纹补上这一环（`plugins/build.sh:20-24,108-121`）。新仓如果不自建指纹，CI 里必须每次都 `build` 再 `pack`，禁止复用陈旧产物。
- 密钥只落 runner 临时文件并 `umask 077`，与官方发布流程一致（`.github/workflows/release.yml:136-147`）。
- 依赖许可门：`cargo deny check` + `deny.toml` 白名单（`openidea/deny.toml:11-20` 的允许集合：MIT/Apache-2.0/BSD/ISC/Zlib/0BSD/CC0/Unlicense/MIT-0/MPL-2.0/…），我们的 Rust 侧直接抄这份即可。
- Gitee 镜像不建议塞进同一个 workflow 的必跑段（会引入外网抖动的红灯），放独立的 `schedule` 或手动 job。

### A.6 上架后的回读断言

| 断言 | 命令/位置 | 期望 |
| --- | --- | --- |
| 包本身合格 | `openidea-plugin check <pkg>.oiplugin` | 末行 `ok`，`code:` 行报 `plugin API 1.15` |
| 摘要一致 | `sha256sum` 与 `plugins/<pub>.<name>.toml` 的 `sha256` 逐字相同 | 一致（CI 亦比对，`registry.yml:63-78`） |
| 注册表已收录 | `curl -sL https://github.com/l0ng-ai/openidea-plugins/releases/download/index/index.json` 查 `plugins[].id` | 出现 `<publisher>.<name>` 且 `engine` 含 `^1.15` |
| 已并入官方索引 | `curl -sL https://github.com/l0ng-ai/openidea/releases/latest/download/plugins-index.json` | 一小时内出现（`plugins-index.yml:8-10`，每小时 17 分） |
| IDE 端可见 | 设置 › 插件 › 插件市场 › 可用；或齿轮菜单「重新扫描插件」 | 能看到并安装；安装页列出能力与签名者 |
| 安装后验签 | 插件详情 | 无「未签名」警告；签名者为本发布者 |

---

## B. `openidea-plugin check` 校验项全表

`check` 不是独立实现的一套规则，而是复用安装路径的三个函数：`PreviewInstall`（`mod.rs:918-963`）→ `validate_package`（`mod.rs:2502-2519`）→ `prepare`（`mod.rs:2490-2499`）+ `Manifest::load`（`manifest.rs:307-421`）+ `PluginEngine::check_component`（`runtime.rs:600-619`）。包文件形态时先按安装方式解包（`main.rs:351-358`）。

### B.1 校验项

| 层 | 校验项 | 失败表现 | 证据 |
| --- | --- | --- | --- |
| 清单 | TOML 可解析、无未知顶层字段 | `unknown field …` | `manifest.rs:187-189, 248` |
| 清单 | `engines.openidea` 必须存在，且不得与 `openidea-api` 混用 | `missing engines.openidea` / `use engines.openidea, not openidea-api` | `manifest.rs:251-256` |
| 清单 | `id`：`.` 分段，每段 `[a-z0-9][a-z0-9_-]*`，总长 ≤64 | `invalid plugin id` | `manifest.rs:532-541, 564-567` |
| 清单 | `name` 非空；`version` 必须是 semver | `name must not be empty` / `version must be a semantic version` | `manifest.rs:325-330` |
| 清单 | `engines.openidea` 与本机 API 相容（另拒绝 0.1 老格式代码） | `does not match this IDE's plugin API 1.x` | `manifest.rs:331-340, 370-374`（`main.rs` 的 legacy 提示） |
| 清单 | `wasm`/`icon`/`icon-dark` 必须是包内相对路径 | `must be a relative path inside the plugin` | `manifest.rs:341-347, 376-381, 570-576` |
| 清单 | `icon` 需 `^1.2`；`icon-dark` 依赖 `icon` | `is new in plugin API 1.2` | `manifest.rs:344-347, 376-379` |
| 清单 | 能力与 API 门槛：`project.write/fs.write:` ≥1.3；`ide.commands`/`database.sources`/`net.http:`/`ipc:` ≥1.4；`net:ask` ≥1.5；`lsp:` ≥1.9；`build.read` ≥1.10；`clipboard.read` ≥1.11 | `capability … is new in plugin API 1.x` | `manifest.rs:348-369` |
| 清单 | `java.agent` 不得写进 `[capabilities]`（由 `[[contributes.javaAgents]]` 派生） | 明确报错 | `manifest.rs:370-374` |
| 清单 | `activation` 需要 `wasm`；事件去重；各事件 API 门槛（`files-changed` 1.3、`file-closed` 等 1.4、`extensions-changed` 1.5、`websocket-ready` 1.6、`problems-changed` 1.8） | `activation needs wasm` / `duplicate activation event` | `manifest.rs:382-409` |
| 清单 | `categories` 用条目 id 规则；超过已认识的前 3 个只取前 3 并告警 | warning 而非失败 | `manifest.rs:410-412`、`main.rs:389-395` |
| 清单 | `[[depends]]` / `[[extension-points]]`：需 ≥1.5、不得自依赖、不得重复、扩展点必须在自身命名空间 | 对应报错 | `manifest.rs:424-459` |
| 清单 | `contributes.<point>` 必须是表或表数组；point id 合法 | `must be a table or an array of tables` | `manifest.rs:414-419, 556-562` |
| 贡献 | `icon` 文件读得到、≤16 KiB、SVG 可消毒 | 包被拒 | `mod.rs:2490-2499`、`icons.rs:40,108` |
| 贡献 | 每个 `contributes.<point>` 逐个按该点规则校验（主题 JSON、图标主题 JSON、语言定义、settings schema/pages、snippets、mcpTools、fileTemplates、fileIcons、views/customEditors/editorPanels 的 `web`……） | `contributes.<point>[i]: …` | `registry.rs:235-303` |
| 贡献 | 不认识的 point：跳过 + warning（前向兼容）；`depends` 未运行也跳过 + warning | warning | `registry.rs:245-264` |
| 贡献 | `locales/` 目录：文件名必须是语言 tag（`fr`、`zh-CN`），内容必须是 string→string 的 JSON 对象，超条目上限报错 | `not a JSON object of strings` | `registry.rs:288-301`、`ui_points.rs:1160-1195` |
| 贡献 | web 页面目录：≤4096 文件、≤256 MiB、无链接 | 拒绝 | `plugins/README.md:2138-2140` |
| 贡献 | skills：`path` 必须 `<dir>/SKILL.md`、单技能 ≤2 MiB、无越界软链、同名技能不得重复 | 加载告警而非整包失败 | `skills.rs:98-140`、`plugins/README.md:481-487` |
| 代码 | `wasm` 文件必须存在（清单引用了就查） | `is missing (referenced by plugin.toml)` | `mod.rs:2506-2512` |
| 代码 | 组件可编译（wasmtime component） | 编译错误原样抛出 | `mod.rs:2514-2516` |
| 代码 | 组件导入只允许 `openidea:plugin/*` 与 `wasi:*` | `imports interfaces the IDE does not provide: …` | `runtime.rs:602-610` |
| 代码 | 插件接口不得无版本（`openidea:plugin/x` 无 `@`） | `uses the unversioned plugin interface` | `runtime.rs:641-647` |
| 代码 | 组件 API 版本 ≤ 宿主（不同 major 或更新 minor 一律拒） | `built for plugin API 1.x, newer than this IDE's 1.y` | `runtime.rs:611-615, 622-628` |
| 代码 | 真实建链一次（`instantiate_pre` + `PluginIndices::new`），导入函数签名必须逐一匹配、必需导出必须在 | `doesn't match the plugin API of this IDE` | `runtime.rs:616-618` |
| 签名 | 包内有 `SIGNATURE.json` 时先做密码学校验；不成立即拒（无索引时不校验「发布者是谁」） | `refusing <id>: …` | `mod.rs:920-922`、`market.rs:540-550` |
| 输出 | 打印 id/version/name、0.1 提示、代码尺寸 + API 版本、分类、激活事件、贡献计数、每个能力（required/optional + 风险等级）、warnings，最后 `ok` | — | `main.rs:360-413` |

补充：`check` 不校验 SHA-256（那是登记仓库 CI 的事）、不校验「包由该源码构建」（人工审核，`policy.mdx:63-72`）、不校验包体积上限（下载/解包时才拦，`market.rs:31`、`package.rs:37-38`）。

### B.2 我方最容易踩的 5 项

| # | 陷阱 | 现象 | 规避 |
| --- | --- | --- | --- |
| 1 | 用 `main` 编的 SDK（1.16）却声明 `^1.15` | `the component was built for plugin API 1.16, newer than this IDE's 1.15` | SDK 依赖 pin 到 `tag = "v0.1.51"`；`check` 输出里核对 `plugin API 1.15` |
| 2 | 清单多写字段（`engine`、`license-file`、`permissions`） | `unknown field` / `permissions is the 0.1 format` | 字段白名单见 `manifest.mdx:44-61`；解析器是 `deny_unknown_fields` |
| 3 | 用了高门槛能力但没抬 `engines.openidea` | `capability project.write is new in plugin API 1.3: set engines.openidea to "^1.3"` | 我方若用 `project.write`/`fs.write:`/`process:` 组合，至少 `^1.4`；用 `net:ask` 到 `^1.5` |
| 4 | web 页用绝对路径构建（Vite 默认 `base: "/"`） | 页面空白，`check` 不报（官方仓靠 `build.sh` 的 grep 才拦） | `base: "./"`；自建 CI 加一条 `grep -Eq '(src\|href)="/' web/index.html && exit 1`（对照 `plugins/build.sh:76-82`） |
| 5 | 忘了 `build` 就 `pack` | 打进旧组件，签名与摘要都对、功能是旧的 | CI 每次先 `build`；或引入 `.sources` 指纹（`plugins/build.sh:34-52`） |

附加一条易忽略项：`pack` 会无条件附加仓库根 `README.md`（`main.rs:573-577`）；如果仓库根 README 是整仓说明（含内部计划、价格、内部链接），它会随包发给每个用户。

---

## C. 包体构成与体积预算

### C.1 什么进包、怎么进

| 条目 | 进包方式 | 能否分离按需下载 | 证据 |
| --- | --- | --- | --- |
| `plugin.toml` | 固定 | 否 | `package.rs:139` |
| 清单引用文件（主题、图标、语言定义、schema、snippets、fileTemplates、mcpTools schema、web 页、skills、javaAgent jar） | 由各贡献点的 `prepare` 回报 `files` | 否（同一包内） | `registry.rs:280-284`、`market.rs:518-523` |
| wasm 组件 | `manifest.wasm` 单文件 | 否 | `market.rs:522` |
| `locales/*.json` | 目录存在即自动收集（无需声明） | 否 | `registry.rs:288-301` |
| 源码、`Cargo.toml`、`target/`、`web-src/` | 不进 | — | 只收 `files` + `wasm` + 许可证类 |
| 许可证类 | `LICENSE`/`LICENSE.md`/`LICENSE.txt`/`NOTICE`/`NOTICE.md`/`COPYING`/`README.md` 若存在则附加 | — | `main.rs:573-577` |
| `SIGNATURE.json` | 签名时写入（publisher、key、signature） | — | `packaging.mdx:36-46` |

包是一个 zip（`<id>-<version>.oiplugin`），条目按路径排序、Deflate 压缩（`package.rs:134-147`）。摘要 = 对「清单 + 包内文件（路径与内容，排序后）」的 BLAKE3（`package.rs:43-51`），签名消息为 `openidea-plugin/1\n<id>\n<version>\n<digest>`（`package.rs:49-51`）。**摘要算的是解包后的内容，不是 zip 字节**（`market.rs:411-415`）——这决定了镜像可以重新打包，见 §D.2。

### C.2 硬约束

| 约束 | 值 | 出处 |
| --- | --- | --- |
| 单次下载包体积 | 256 MiB | `market.rs:31`；登记仓库同值 `registry.py:40` |
| 解包上限 | 10,000 条目 / 512 MiB | `package.rs:37-38, 200-235` |
| 单个 web 页面目录 | 4096 文件 / 256 MiB，不允许链接 | `plugins/README.md:2138-2140` |
| 插件图标 | ≤16 KiB SVG | `icons.rs:40` |
| 插件线性内存 | 256 MiB | `types.rs:490`、`plugins/README.md:2745` |
| 单次调用 fuel | 5×10⁹ wasm 指令 | `types.rs:485` |
| 命令/激活/停用墙钟 | 5 秒 | `types.rs:486`、`plugins/README.md:2741-2745` |
| 后台任务 | 60 秒（`tasks` 句柄，可续） | `types.rs:487`、`plugins/README.md:2738-2740` |
| 编辑器 provider | 软 50 ms / 硬中断 500 ms | `plugins/README.md:2742-2744` |
| 存储 | 每插件 64 MiB（键值） | `plugins/README.md:360` |
| 私有目录 | 无配额上限记录，需自行控 | `plugins/README.md:405-421`（推断：仅受磁盘限制） |

### C.3 实际体积基线（官方 0.1.51 release 实测）

| 包 | 体积 | 备注 |
| --- | --- | --- |
| `openidea.mybatis-0.4.2.oiplugin` | 111,609 B | 与我方最接近的官方插件（导航 + 补全，纯 Rust） |
| `openidea.csv-editor-0.1.0.oiplugin` | 78,321 B | 组件 210,645 B 未压缩 |
| `openidea.graphviz-0.1.0.oiplugin` | 569,248 B | 官方最大包（含图形库） |
| `openidea.regex-tester-0.1.0.oiplugin` | 331,418 B | |
| `openidea.mybatis` 组件 | 300,337 B | `plugins/official/mybatis/dist/plugin.wasm` |

结论（推断）：我方 SQL 解析 + WXML 索引的组件落在 1–4 MiB 量级是现实目标；web 页（若用来做结果视图）10–20 MiB 量级；两者合计远低于 256 MiB。真正的瓶颈不是包体积而是 **256 MiB 线性内存 + 5 秒命令墙钟 + 5×10⁹ fuel**：大 SQL（万行级）解析必须走 `tasks`（60 秒后台）并把结果落 `storage`/`plugin_dir`，命令路径只做增量与缓存命中。

### C.4 能否分离按需下载

| 资产 | 结论 | 依据 |
| --- | --- | --- |
| wasm 组件 | 不能分离；`wasm` 是单文件且安装即校验 | `mod.rs:2506-2512` |
| web 页 | 不能；整目录随包安装、iframe 只加载自身文件、无网络 | `plugins/README.md:2138-2156` |
| 额外数据（方言表、规则库） | 可以：`net:<host>` + `host::fetch` 下载到 `host::fs::plugin_dir()`（无需 fs 授权），下次启动复用 | `plugins/README.md:366, 405-421` |
| native 二进制 | 可打包但**不能直接用包内路径启动**：`process:` 在 PATH 解析并固定内容哈希，指向包内文件需用户先在设置里选/先加 PATH；官方 container-runner 的做法就是「装插件前先把目录放 PATH」 | `plugins/README.md:374-382`、`plugins/official/container-runner/README.md:1-8` |
| jar（javaAgent） | 可打包并自动注入 `-javaagent`，但只作用于用户 Java 应用的 JVM，不是插件自身进程 | `plugins/README.md:2498-2543` |

所以「native sidecar + 按需下载」在工程上成立的技术路线是：首启由插件代码 `fetch` 到 `plugin_dir` → 用户在设置里批准该可执行文件（哈希钉住）→ `process:` 启动。代价是首次使用多一次授权交互，且文件被替换需重新批准。

---

## D. 双通道发行建议

### D.1 三条通道的定位

| 通道 | 承担什么 | 谁在用 | 证据 |
| --- | --- | --- | --- |
| GitHub Release（主） | 包原件 + SHA-256 + 变更说明；登记仓库 CI 从这里取包 | 登记仓库 CI、海外用户、自建索引 | `registry.yml:63-78` |
| Gitee（镜像） | 同包镜像，给国内用户直连；也可作为自建索引的下载基址 | 国内用户 | 本册 §D.2 |
| 官方注册表（上架） | 曝光与官方市场分发；进 `plugins-index.json` | 官方市场用户 | `marketplace.mdx:18-23` |

### D.2 自有更新源：支持，且与 update.json 等价

- 支持。市场是**静态**的：一个 `index.json` + 旁边若干包，放任意 Web 服务器或文件夹；设置 › 插件 › 齿轮 ›「插件仓库…」填 URL 即生效（`self-hosted.mdx:6-15, 42-51`）。
- IDE 端每天刷新一次（`MARKET_REFRESH = 24h`，启动后 60 秒首刷），只在配置了市场 URL 时刷（`src-tauri/src/commands/plugin.rs:31,138-153`）。与 JetBrains 的 `update.json` 相比，机制等价（静态清单 + 摘要校验 + 签名），差别是这里有能力授权对话框与验签。
- 限制一：**IDE 只读一个索引**。要让用户同时看到官方/社区插件，必须把官方索引 merge 进来：`openidea-plugin merge market/index.json https://github.com/l0ng-ai/openidea/releases/latest/download/plugins-index.json --keep <我方 publisher>`（`self-hosted.mdx:34-46`）。
- 限制二：没有「关闭自动检查」的开关。设置里只有市场 URL（留空＝恢复默认官方），刷新线程只判断「有没有 URL」（`market.rs:232-247`、`plugin.rs:146-153`）。更新本身是**提示后由用户点「全部更新」**，不是静默替换（`marketplace.mdx:105-107`）。
- 镜像可行性：因为索引里的 `digest` 是解包内容的 BLAKE3，Gitee 上重新压缩的同内容包也能通过校验（`market.rs:411-415, 431-444`）；但 `plugins/<pub>.<name>.toml` 里的 `sha256` 是**包文件字节**的摘要，镜像若重打包必须另记一份（`registry.py:190-193` 校验的是 hex 格式与 CI 重算比对，`registry.yml:99-100`）。

### D.3 与现有 JetBrains 发行链并存

| 维度 | JetBrains 插件（现行） | OpenIDEA 插件（新仓） | 建议 |
| --- | --- | --- | --- |
| 版本号 | 日历版 `2026.11.25` / `-beta.N`（`gradle.properties:3`、`update/update.json`） | semver `MAJOR.MINOR.PATCH`（`manifest.rs:328` 强制） | 两条独立版本线，不强行对齐；在 `docs/变更记录.md` 增一列「OpenIDEA 版本」做映射 |
| 变更说明 | `CHANGELOG.md` 的 `[Unreleased]` + `update.json.changeNotes` | `plugin.toml` 的 `change-notes`（显示在「新功能」）+ 登记仓库 PR 描述 | 单一事实源仍是 `CHANGELOG.md`；发版脚本按平台切一节，生成 `change-notes`，禁止手写两份 |
| 分发 | Marketplace + 自建 `update.json` | 官方注册表 + 自建 `index.json` | 复用 `.local/github-token.json` 与 `dev-support/push-all.ps1` 的双远端习惯；Gitee 侧继续 `releases/download/<tag>/…` |
| 构建 | Gradle / Kotlin | cargo / wasm32-wasip2 | 各自独立 CI；不要把 cargo 塞进 Gradle 任务图（沙盒与缓存都不同源） |
| 命名 | 显示名 `MyBatis SQL Visualizer`（`CHANGELOG.md:24` 更名记录） | id 必须 `<publisher>.<name>`，全小写 | id 与 JetBrains 的 `xmlId` 无关，可另取；市场里不得与 `openidea.mybatis` / `qtvbwfn.mybatis-duo` / `jiusi.mybatis-log` 撞名 |

同域竞品实测（登记仓库 + release 资产）：`openidea.mybatis`（官方 0.4.2）、`qtvbwfn.mybatis-duo`、`jiusi.mybatis-log`、`ritsu.sqltoy-navigator`。命名需给出可区分的定语（如 `…-visualizer`），并避免「MyBatis」单独作为主体词。

---

## E. 合规结论表

### E.1 逐项结论

| 事项 | 可以做 | 有义务 | 不能做 | 档位 |
| --- | --- | --- | --- | --- |
| 自有 Kotlin 代码迁到 Rust | 是。主仓 `LICENSE` 为 Apache-2.0，代码自有版权；移植后仍可 Apache-2.0 或另择 | 保留原版权与许可声明；若某文件复制自 IntelliJ 平台（`THIRD-PARTY-NOTICES.md:16` 明示「复制自 JetBrains IntelliJ Platform，未修改，保留原始版权声明」），移植时不得抹除其归属 | 把平台文件当自有代码改头换面 | 现成可做 |
| JSqlParser 5.3（现有 Java 引擎） | 是。**LGPL-2.1 / Apache-2.0 双许可，可择一按 Apache-2.0 使用**（见 §E.2） | 附许可证全文 + NOTICE + 注明所选分支；`THIRD-PARTY-NOTICES.md` 同批登记 | 不附许可就随 native 二进制分发；或默认落回 LGPL 却不做「可替换库」安排 | 现成可做 |
| `sqlparser-rs`（Rust 侧替代） | 是。Apache-2.0（ASF `apache/datafusion-sqlparser-rs`），crates.io `sqlparser 0.64.0`，累计下载 9,469 万 | 保留 LICENSE/NOTICE 归属；`deny.toml` 白名单加项 | — | 现成可做 |
| MyBatis 名称/商标 | 描述性使用（「for MyBatis / MyBatis-Plus 项目」）可以 | 加免责声明「非 MyBatis、MyBatis-Plus 官方出品」；不得暗示背书 | 用 MyBatis 官方/MyBatis-Plus 官方 logo、自称官方、用其名发布公告 | 现成可做（注意措辞） |
| MyBatis-Plus（Baomidou） | 同上。代码 Apache-2.0；官方仅登记过 LOGO 使用许可，未公开文字商标注册信息 | 同上 | 用其名/LOGO 做背书式宣传 | 变通可做（待商标检索确认） |
| GPL 竞品 `weigram.hotreload` | 只能读文档/行为，学思路 | — | 复制任何代码、字符串、图标、配置片段到 Apache-2.0 工程 | 不可行 |
| OpenIDEA 自身（Apache-2.0） | 引用其 SDK、抄 CI 脚本、抄 deny.toml | 保留归属 | 把其官方插件当自家产品改个名（`openidea.*` 前缀只属官方密钥） | 现成可做 |
| 闭源 | 代码可闭源，可走自建索引 / Release / 磁盘安装 | 自建市场不受官方审核，但沙箱、授权、验签仍生效 | **不能上官方注册表**：规则要求公开源码仓 + 开源许可 + 包由该源码构建 | 不可行（官方市场） |
| 压缩/混淆产物 | 允许 Vite 等构建器的产物（由源码构建） | README 说明 `web/` 是构建输出、源码在 `web-src/` | 「非由该源码构建的混淆或压缩代码」 | 有义务（举证责任） |
| 高风险能力声明 | `process:`、`fs.*`、`ipc:`、`ide.commands` 等可申请 | README 必须逐项说明为什么需要（`policy.mdx:63`）；安装页会按风险等级展示 | 声明了却不用（审核会问）；或把 `java.agent` 写进 `[capabilities]` | 有义务 |

### E.2 JSqlParser 的确切事实与门禁

主仓实际依赖 `com.github.jsqlparser:jsqlparser:5.3`（`build.gradle.kts:141`）。其发布 POM（`F:/gradle-home/caches/modules-2/files-2.1/com.github.jsqlparser/jsqlparser/5.3/c2e7c15f…/jsqlparser-5.3.pom`）的 `<licenses>` 段同时列出：

- `GNU Library or Lesser General Public License (LGPL) V2.1`
- `The Apache Software License, Version 2.0`

且同 POM 中 license-maven-plugin 配置 `licenseName = dual_lgpl_ap2`（同一文件）。主仓 `THIRD-PARTY-NOTICES.md:18-22` 已按「双许可、按 Apache-2.0 使用」登记。

结论口径（不要写成单边说法）：

1. JSqlParser 5.3 是 **LGPL-2.1 / Apache-2.0 双许可**，可择一按 **Apache-2.0** 使用。
2. 若在 Rust 侧换 `sqlparser-rs`（Apache-2.0）：许可零障碍，两边都能落 Apache-2.0，但仍需在 NOTICE/第三方清单保留归属。
3. 若把现有 Java 引擎做成 native sidecar 随包分发：按 Apache-2.0 分支走，**不承担 LGPL 的「可替换库」义务**；但发行物必须附 NOTICE 与许可证全文，并注明所选分支。
4. 门禁建议（写进 CI 与本册的检查表）：`依赖许可白名单 + 双许可组件的分支选择必须显式记录`——例如在 `THIRD-PARTY-NOTICES.md` 里为每个双许可组件写一行「原始许可 A/B，本项目选 B，理由」，并加一个守卫测试，防止换版本时默认落回 LGPL。

### E.3 「能不能闭源、能到什么程度」

| 程度 | 可行性 | 依据 |
| --- | --- | --- |
| 源码全部闭源 + 上官方注册表 | 不行 | `openidea-plugins/README.md`「Rules」：public source repository under an open-source licence，包由该源码构建 |
| 源码闭源 + 自建索引/Release/磁盘分发 | 可以 | `self-hosted.mdx:6-15`；自建市场不经官方审核（`policy.mdx:73`） |
| 开源仓库 + 部分能力闭源（如 native sidecar 商业版） | 规则上可行但会被审：包必须由公开源码构建；若 sidecar 二进制随包分发而源码不公开，等于「未经源码构建」，审核会拒 | `policy.mdx:63`（第三层局限也写明 CI 不做可复现构建，靠人工判断） |
| 开源仓库 + 开源许可 + 商业支持收费 | 可以 | 开源许可不禁止收费 |

实践结论：要在官方市场出现，就按 Apache-2.0（或 MIT）开源整仓；闭源只走自有渠道。以我方现状（JetBrains 侧本来开源、Apache-2.0）建议直接开源，减少两个渠道的文档/法务分叉。

### E.4 process:java 的先例（对「插件执行 SQL」的合规与体验评估）

实测 `izzyjere.jpa-powertools` 的 `plugin.toml`（`https://raw.githubusercontent.com/izzyjere/jpa-powertools-oi/main/plugin.toml`）：

- `license = "MIT"`；
- `optional = ["project.write", "clipboard", "process:java", "net:repo1.maven.org"]`；
- 注释原文：「`process:java`: the JDBC bridge (Java 11+) that reads database metadata」；「`net:repo1.maven.org`: download JDBC drivers (checksums verified)」。

评估：

| 维度 | 结论 | 依据 |
| --- | --- | --- |
| 审核先例 | 已有上架先例，说明「插件 spawn 一个 java 小进程读数据库元数据」不被规则禁止 | 登记仓库 `publishers/izzyjere.toml` + `plugins/izzyjere.jpa-powertools.toml` 已收录（0.2.0） |
| 能力风险 | `process:java` 属 high（等于本机执行代码），用户需在安装时批准；程序路径与内容哈希被钉住，换 JDK 小版本要重新批准 | `plugins/README.md:363, 374-382` |
| 体验 | 安装页会明示「可运行程序」；首次启动若 `java` 不在 PATH 则失败，需用户手动指向 | `docs/zh/plugins/testing.mdx:79` |
| 对己方含义 | 「JDBC 桥」可复用同一范式做方言元数据/执行；但每台机器要装 Java 11+，且每次 JDK 升级触发重授权，支持成本要计入 | 推断 + `plugins/README.md:374-382` |

同时注意宿主侧的既成事实：`l0ng-ai/openidea#1300`（`feat(mybatis): mapper navigation, generation, @Param support and SQL log conversion`，作者 `qtvbwfn`）经 GitHub API 实测为 **closed、未合并**（`merged=false`，`closed_at=2026-11-12T06:57:57Z`）；同一作者随后发布了 `qtvbwfn.mybatis-duo` 插件。含义：上架规则要求开源，但**并不意味着宿主会接受领域特性进主仓**——领域功能一律以插件形态交付，这也意味着我方不必也不应指望「提 PR 改宿主」。

---

## F. 风险清单

| # | 风险 | 影响 | 现状/缓解 | 证据 |
| --- | --- | --- | --- | --- |
| 1 | 预览态 API | 已发布 1.x 只增不改（类型/签名冻结、导出接口不得加函数），破坏性变更集中在 2.0（WASI 0.3 异步，宿主适配层保 1.x 运行）；但文档明写 `main` 上未发布内容发布前可变 | 按 1.15 构建；跟踪 `plugins/README.md` 的 API 段；升级 SDK tag 需回归 | `versioning.mdx:8, 72-81`、`plugins/README.md:3112-3149` |
| 2 | SDK tag 漂移 | 用新 tag 重建会抬组件 API，旧 IDE 装不上；不重建又可能错过宿主修复 | `engines.openidea` 声明「覆盖所用功能的最低版本」；每个 release 保留多 `[[versions]]`（每 API 需求留最新一版） | `versioning.mdx:36-44`、`self-hosted.mdx:17-24` |
| 3 | 签名密钥管理 | 密钥泄露＝他人可以我方身份发布；丢失＝无法背书新钥，用户须手工确认换钥 | 私钥只在 CI secret 与本地加密存储；轮换时旧钥 `endorse` 新钥；吊销后用户确认 | `policy.mdx:34-45`、`main.rs:688-725` |
| 4 | 下架规则（30 天） | 新 OpenIDEA release 下 `check` 失败，30 天无修复版即下架；已装副本仍能用但不再更新 | 建立「新 release 出现 → 拉最新工具跑 `check`」的例行任务（登记仓库每天跑，会开 issue） | `policy.mdx:65`、`registry.yml:111-122` |
| 5 | 注册表审核时长 | 新发布者/新插件人工审源码与能力；版本更新也需维护者合并，无 SLA | 首次上架预留缓冲；版本更新保持小步、README 能力说明同步更新 | `policy.mdx:60-66`（无 SLA，标 待验证（推断）） |
| 6 | 名称与商标 | 「MyBatis」在名里易被判暗示官方；与我方 JetBrains 显示名不同步会造成品牌割裂 | 加免责声明；id 取 `<publisher>.<name>` 且与市场现有四条 mybatis 系区分；LOGO 不用官方 | §E.1、§D.3 |
| 7 | 闭源诉求与开架规则冲突 | 若坚持闭源，只能自建渠道，失去官方市场曝光与 `plugins-index.json` 合并 | 建议开源整仓；商业价值放支持/服务 | §E.3 |
| 8 | 单索引约束 | 用户一旦填了我方索引 URL，就看不到官方/社区插件，除非我方索引里 merge 了官方 | 发布脚本固定 `merge --keep <publisher>`；文档写清 | `self-hosted.mdx:46-47` |
| 9 | 体积/时限误判 | 大 SQL 解析撞 5 秒/256 MiB；web 页打包成几十 MB 拖慢安装 | 重活进 `tasks`；web 页体积预算与 CI 体积上限 | §C.2、§C.3 |
| 10 | 远程项目差异 | 插件代码跑在 `openidea-server` 侧；`java.agent` 在远程被跳过；项目插件暂不支持远程 | 功能分级：远程可用/不可用要在 README 写明 | `plugins/README.md:2717-2730, 2541-2543` |
| 11 | 压缩产物被审 | Vite 产物是 minified，规则要求「不得含未经源码构建的混淆/压缩代码」 | README 明确构建链与命令；保留 `web-src/` 与 lockfile | §E.1 |

---

## 未解决/需上游确认

1. **1.15 与 1.16 的最终边界**：`wit/` 已是 1.16.0，已发布快照是 1.15.0。需要上游确认 1.16 是否只含「新增」（`vcs_repository_at`、`pick_file`、`frames`、`completion-list-provider`、`set-markdown`）而无既有类型变更——若含，则我方按 1.15 构建的组件在 1.16 IDE 上仍需可加载（规则上应当可以，但未见发布说明逐条承诺）。待验证（推断）。
2. **登记仓库是否接受 Gitee 作为源码仓/包源**：`registry.py` 只校验 `https` 协议（`registry.py:176,190`），但 `owners` 明确是 GitHub 账号（`registry.py:126`），审核流程也在 GitHub PR 上。结论：包源可以放 Gitee（只要 CDN 可达并校验 SHA-256），但**源码仓与 owner 身份仍是 GitHub 口径**；首次上架前建议用一个测试 publisher 走一遍确认。
3. **审核时长与拒因清单**：无公开 SLA、无公开拒因统计。需要实测一次首轮提交获取真实周期。
4. **插件包内 native 二进制能否直接执行**：现有文档只给出「PATH 解析 + 内容哈希」与「设置里手动选」两条路，没有「从包内路径启动」的接口。若我方要随包带 sqlite/postgres 客户端二进制，需确认是否必须让用户手工加 PATH；或改用「首启下载到 `plugin_dir`」的方案（可行但体验待验证）。
5. **`web/` 整目录进包后能否懒加载**：web 页在 iframe 内无网络、只读自身文件（`plugins/README.md:2141-2145`），所以大资产无法分包下载。若结果视图需要大量资源，需要评估「插件代码 fetch + 私有目录」能否被 web 页引用——当前未见接口，标 待验证（推断）。
6. **`database.query` 缺口**（与 04 分册交叉）：插件 API 无执行 SQL 函数，`jpa-powertools` 用 `process:java` 绕过。需要上游确认是否有计划开放 `database.query`（否则我方所有执行类能力都要靠 JDBC 桥，支持成本高）。
7. **商标检索**：「MyBatis-Plus」文字商标在中国商标网的注册情况未查（公开检索未找到注册号）。正式命名前建议做一次第 9/42 类检索，或直接改用不含商标的差异化名称。
8. **`license` 字段与仓库许可的一致性**：清单 `license` 为可选且不被校验（`manifest.rs:200` 仅解析），登记仓库规则要求仓库有开源许可但不比对清单字段；是否需要统一口径（避免「清单写 MIT、仓库是 Apache-2.0」）建议在治理规范里固定。
