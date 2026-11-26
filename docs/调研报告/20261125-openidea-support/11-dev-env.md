# 11 开发环境审计与搭建（Rust / WASM 组件）

审计时间：2026-11-25 13:44 ~ 15:00（北京时间）。
主机：Windows 10.0.26200 x64，Shell 为 Git Bash。
目标：确认本机能否从零开发 OpenIDEA 插件（Rust 编到 `wasm32-wasip2` 组件），补齐缺口后给出一次真实编译读数。
约束：只写 User 作用域环境变量、不改 Machine；不卸载、不升级、不覆盖既有 Rust / Node / JDK / Gradle / OpenIDEA；不改动 NVM 的 PATH；不动 `F:\code\Java\Gitee` 下仓库源码（本文件除外）。

最终口径（用户拍板）：**Rust 留在默认家目录 `C:\Users\USER\.rustup`，只用现成的 1.98.1**。曾试装的 1.100.0（`D:\HuanJing\rust`）已按此决定回退，仅保留其编出的 `openidea-plugin` 可执行文件。

---

## 0. 结论速览

| 能力项 | 要求 | 结论 | 判定 |
| --- | --- | --- | --- |
| Rust stable | ≥ 1.96 | 默认家目录 `C:\Users\USER\.rustup` 的 **1.98.1**，未被任何环境变量劫持 | 满足 |
| clippy / rustfmt | 必须 | clippy 0.1.98、rustfmt 1.9.0-stable | 满足 |
| wasm32-wasip2 | 必须 | 已装到默认 1.98.1 toolchain；实测产出 component 二进制 | 满足 |
| Node | 22.x ≥ 22.22.2 | NVM（`D:\HuanJing\nvm`）下的 22.22.3，仓根 `.nvmdrc` 锁定 | 满足 |
| pnpm | 11 | 11.29.0，装在 nvm 22.22.3 的全局前缀内 | 满足 |
| git | 任意 | 2.45.1.windows.1 | 满足 |
| MSVC 生成工具 | 仅宿主原生构建需要 | VS2022 BuildTools 14.44.35207 + Win SDK 10.0.26100.0 | 满足 |
| openidea-plugin CLI | 建议 | `openidea-plugin 0.1.51 (plugin API 1.15.0)`，在 `C:\Users\USER\.cargo\bin` | 满足 |

一句话：**Rust 完全回到原状（默认家目录 1.98.1）并把 `wasm32-wasip2` 补齐，1.98.1 实测能直接产出 wasm 组件；Node/pnpm 走 NVM；openidea-plugin 已就位。**

---

## 1. 盘点读数（施工前原始状态）

| 项 | 要求 | 实际 | 判定 |
| --- | --- | --- | --- |
| `rustc --version` | ≥ 1.96 | `rustc 1.98.1 (48a229cea 2026-09-01)` | 满足 |
| `cargo --version` | 随 rustc | `cargo 1.98.1 (797e8a9bc 2026-08-05)` | 满足 |
| `rustup --version` | 任意 | `rustup 1.29.3 (edbc831ce 2026-11-02)`，`rustup home: C:\Users\USER\.rustup` | 满足 |
| `rustup target list --installed` | 含 wasm32-wasip2 | 仅 `x86_64-pc-windows-msvc` | **缺**（后补） |
| `rustup component list --installed` | 含 clippy / rustfmt | `cargo`、`clippy`、`rust-std`、`rustc`、`rustfmt` | 满足 |
| `cargo clippy --version` | — | `clippy 0.1.98` | 满足 |
| `cargo fmt --version` | — | `rustfmt 1.9.0-stable` | 满足 |
| `node -v` | 22.x ≥ 22.22.2 | `v22.10.0` | **版本过低** |
| `npm -v` | 随 Node | `10.9.0` | 满足 |
| `pnpm -v` | 11 | `10.34.5` | **版本过低** |
| `git --version` | 任意 | `2.45.1.windows.1` | 满足 |
| `python --version` | 非必需 | `3.10.1` | 满足 |
| `where cl` | 仅 native | 未找到（未进 vcvars，属预期） | 不适用 |
| `where link` | — | 仅 `C:\Program Files\Git\usr\bin\link.exe`（Git 自带 Unix `link`，非 MSVC 链接器） | 不适用 |
| `vswhere -latest -products *` | — | `C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools` | 满足 |
| VC 工具集 / Windows SDK | 仅 native | `VC/Tools/MSVC/14.44.35207`、`Windows Kits\10\Lib\10.0.26100.0` | 满足 |
| `where openidea-plugin` | 建议 | 未找到 | **缺**（后补） |

**Rust 侧结论：1.98.1 满足 ≥1.96 的要求，默认家目录即可用，唯一缺口是 `wasm32-wasip2` target。**

### 1.1 Node 侧的实际家底（NVM）

| 项 | 读数 |
| --- | --- |
| nvm-windows | `nvm version` = 1.2.2，`nvm root` = `D:\HuanJing\nvm` |
| 施工前已装版本 | `22.10.0`、`22.17.1`、`24.16.0`、`v24.0.0`；无 22.22.x |
| 当前链接 | `D:\HuanJing\nodejs` → `D:\HuanJing\nvm\v24.0.0` |
| `node` 实际解析 | `C:\Users\USER\.nvmd\bin\node.exe`（NVM Desktop shim）→ `v22.10.0` |
| `settings.txt` | `root: D:\HuanJing\nvm`、`path: D:\HuanJing\nodejs`、node/npm 镜像走 npmmirror |

### 1.2 磁盘

| 时点 | C 剩 | D 剩 |
| --- | --- | --- |
| 施工前 | 42.3 GB | 36.8 GB |
| 回退后（现状） | 41.1 GB | 33.6 GB |

---

## 2. 差距与处理记录

### 2.1 差距清单

| # | 差距 | 要求 | 施工前 | 最终处理 |
| --- | --- | --- | --- | --- |
| 1 | 无 `wasm32-wasip2` target | 必须 | 仅 msvc target | 在默认 1.98.1 toolchain 上 `rustup target add` |
| 2 | Node 过低 | ≥22.22.2 | 22.10.0 | 经 NVM 装 22.22.3，仓根 `.nvmdrc` 锁定 |
| 3 | pnpm 过低 | 11 | 10.34.5 | 11.29.0 装进 nvm 22.22.3 全局前缀 |
| 4 | 无 openidea-plugin | 建议 | 无 | `cargo install --git ... --tag v0.1.51`，产物搬到 `~/.cargo\bin` |

### 2.2 选源

| 用途 | 选源 | 实测 |
| --- | --- | --- |
| Rust 组件分发 | `RUSTUP_DIST_SERVER=https://rsproxy.cn`（rustup 默认走官方，加代理亦可） | `channel-rust-stable.toml` 200 / 0.54 s |
| npm 包（pnpm） | `--registry=https://registry.npmmirror.com` | 国内直连 |
| Node 22.22.3 | nvm 自带镜像（`settings.txt` 已配 npmmirror） | 下载 + 解压约 12 s |
| GitHub（CLI 源码） | 直连 + `HTTPS_PROXY=http://127.0.0.1:7890` | 本地代理 |

### 2.3 安装与回退记录

**Rust（曾装 1.100.0，已回退）**

曾按早期方案把 Rust stable 1.100.0 装到 `D:\HuanJing\rust\{rustup-home,cargo-home}`（`rustup-init.exe -y --default-toolchain stable --profile default --component clippy,rustfmt --no-modify-path`，源 rsproxy），并写了用户级 `RUSTUP_HOME`/`CARGO_HOME` 与 PATH 前缀。

用户决定回到默认家目录后，执行回退（脚本 `D:\HuanJing\rust\rollback-rust-env.ps1`）：

```powershell
[Environment]::SetEnvironmentVariable('RUSTUP_HOME', $null, 'User')
[Environment]::SetEnvironmentVariable('CARGO_HOME',  $null, 'User')
# 从 User PATH 移除 D:\HuanJing\rust\cargo-home\bin（读原值→去重→保留其余）
```

回退后在默认家目录补能力：

```bash
rustup target add wasm32-wasip2          # 下载 rust-std，约 30 MB
rustup component add clippy rustfmt      # 已存在，输出 "is up to date"
```

`D:\HuanJing\rust` **未删除**（见 §7 冗余说明），仅把它的 CLI 产物复制出来继续用。

**Node / pnpm（走 NVM）**

```bash
cd "D:/HuanJing/nvm" && nvm install 22.22.3      # 施工前版本库无 22.22.x，故补装
# 仓根放 .nvmdrc（内容一行 22.22.3），NVM Desktop 进目录自动切
cd <含 .nvmdrc 的目录> && npm config get prefix   # -> D:\HuanJing\nvm\22.22.3
npm install -g pnpm@11 --registry=https://registry.npmmirror.com
```

早期曾解压独立 Node 22.23.5 到 `D:\HuanJing\node`，改口径后已从用户 PATH 移除并整目录删除，不作为交付物。

**openidea-plugin**

```bash
export HTTPS_PROXY="http://127.0.0.1:7890"; export HTTP_PROXY="http://127.0.0.1:7890"
export CARGO_NET_GIT_FETCH_WITH_CLI=true
cargo install --locked --git https://github.com/l0ng-ai/openidea --tag v0.1.51 \
  ide-host --bin openidea-plugin
# 回退后把产物搬到默认 cargo bin：
cp D:/HuanJing/rust/cargo-home/bin/openidea-plugin.exe C:/Users/USER/.cargo/bin/
```

---

## 3. 环境变量与 PATH 变更清单

**最终状态：不存在任何 Rust 相关的用户级环境变量。** 曾写入的三项均已撤销，Machine 作用域自始至终零改动。

| 变量 | 曾经的值（已撤销） | 作用域 | 现状 | 撤销命令 |
| --- | --- | --- | --- | --- |
| `RUSTUP_HOME` | `D:\HuanJing\rust\rustup-home` | User | 未设置 | `[Environment]::SetEnvironmentVariable('RUSTUP_HOME', $null, 'User')` |
| `CARGO_HOME` | `D:\HuanJing\rust\cargo-home` | User | 未设置 | `[Environment]::SetEnvironmentVariable('CARGO_HOME', $null, 'User')` |
| `PATH` | 前插过 `D:\HuanJing\rust\cargo-home\bin;` | User | 已移除该项，其余 1300 余字符原样保留、顺序恢复 | 见下方片段 |

PATH 撤销（移除 cargo bin，保留其余）——已执行：

```powershell
$p = [Environment]::GetEnvironmentVariable('PATH','User')
$keep = @()
foreach ($e in ($p -split ';')) {
    if ([string]::IsNullOrWhiteSpace($e)) { continue }
    if ($e.Trim().TrimEnd('\') -ieq 'D:\HuanJing\rust\cargo-home\bin') { continue }
    $keep += $e
}
[Environment]::SetEnvironmentVariable('PATH', ($keep -join ';'), 'User')
```

若日后需要重新指向那次试装的 toolchain（一般不需要），恢复命令：

```powershell
[Environment]::SetEnvironmentVariable('RUSTUP_HOME', 'D:\HuanJing\rust\rustup-home', 'User')
[Environment]::SetEnvironmentVariable('CARGO_HOME',  'D:\HuanJing\rust\cargo-home',  'User')
```

现状核对：`PATH(User)` 首项已回到 `C:\Users\USER\.cargo\bin`（与施工前一致），不再包含 `cargo-home`；`RUSTUP_HOME`/`CARGO_HOME` 用户级值均为空。

**未改动**：`HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment` 下任何值；`HKCU` 以外的注册表项；NVM 写入的 PATH 条目。

---

## 4. 最终验证读数（回退后，默认 1.98.1）

### 4.1 验证方法

`-UseNewEnvironment` 在本机不可用（产出的环境块是 `USERNAME=SYSTEM`、无 `USERPROFILE` 的畸形块）。改用：读 `HKLM\...\Session Manager\Environment` + `HKCU\Environment` 按 Windows 规则拼环境块（Machine 在前、User 在后），补齐登录期注入、不进注册表的易失变量（`SystemRoot`、`TEMP`、`USERPROFILE`、`APPDATA` 等），再以该环境块启动全新 `cmd.exe` 子进程验证。这正是「新开一个终端」拿到的环境。

脚本：`D:\HuanJing\rust\verify-user-env.ps1`、`fresh-check.cmd`。

> 踩坑留证：最初自建环境块漏了 `SystemRoot`，`node -e`/`npm`/`pnpm` 一律 `Assertion failed: ncrypto::CSPRNG(nullptr, 0)` 崩（exit 134），而 `node -v` 不崩，极易误判 Node 坏了。自建环境块必须补易失变量。

### 4.2 工具版本（全新环境块，`RUSTUP_HOME`/`CARGO_HOME` 均为空；cwd = 含 `.nvmdrc` 的目录）

| 项 | 读数 |
| --- | --- |
| `rustc --version` | `rustc 1.98.1 (48a229cea 2026-09-01)` |
| `cargo --version` | `cargo 1.98.1 (797e8a9bc 2026-08-05)` |
| `rustup --version` | `rustup 1.29.3 (edbc831ce 2026-11-02)`，活跃 rustc 1.98.1 |
| `rustup show` | `rustup home: C:\Users\USER\.rustup`，toolchain `1.98.1-x86_64-pc-windows-msvc (active, default)` |
| `rustup target list --installed` | `wasm32-wasip2`、`x86_64-pc-windows-msvc` |
| `rustup component list --installed` | `cargo`、`clippy`、`rust-std-wasm32-wasip2`、`rust-std-x86_64-...`、`rustc`、`rustfmt` |
| `cargo clippy --version` | `clippy 0.1.98 (48a229ceae 2026-09-01)` |
| `cargo fmt --version` | `rustfmt 1.9.0-stable (48a229ceae 2026-09-01)` |
| `node -v` | `v22.22.3`（由 cwd 的 `.nvmdrc` 决定） |
| `node -e` 自检 | `NODEOK v22.22.3 4955` |
| `npm -v` | `10.9.8` |
| `pnpm -v` | `11.29.0` |
| `npm config get prefix` | `D:\HuanJing\nvm\22.22.3` |
| `openidea-plugin --version` | `openidea-plugin 0.1.51 (plugin API 1.15.0)` |
| `where openidea-plugin` | `C:\Users\USER\.cargo\bin\openidea-plugin.exe` |

### 4.3 最小 crate 真实编译（默认 1.98.1 + `wasm32-wasip2`）

工程：`D:\HuanJing\openidea-dev\smoke`（临时验证工程，可删）。`Cargo.toml`：`edition = "2021"`、`[lib] crate-type = ["cdylib"]`；源码 `#[no_mangle] pub extern "C" fn add(a: i32, b: i32) -> i32 { a + b }`。

| 项 | 读数 |
| --- | --- |
| 命令 | `cargo clean` 后 `cargo build --release --target wasm32-wasip2` |
| 结果 | `Finished 'release' profile [optimized] target(s) in 7.00s`，exit 0（墙钟 7892 ms，含 cargo 启动） |
| 产物 | `D:\HuanJing\openidea-dev\smoke\target\wasm32-wasip2\release\smoke.wasm` |
| 大小 | 14,355 B |
| 头 8 字节 | `00 61 73 6d 0d 00 01 00` |

头部 `0d 00 01 00` 仍是 WebAssembly **Component Model** 版本标记（core module 为 `01 00 00 00`），说明 **1.98.1 也能直接产出 wasm 组件**，不需要 nightly、不需要 `wasm-tools component new` 后处理。工具链版本从 1.100.0 换回 1.98.1 后产物结构不变，体积 14,355 B（1.100.0 时 14,569 B，差异属编译器版本正常波动）。

### 4.4 openidea-plugin CLI

| 项 | 读数 |
| --- | --- |
| 来源 | `cargo install --locked --git https://github.com/l0ng-ai/openidea --tag v0.1.51 ide-host --bin openidea-plugin` |
| 编译耗时 | `Finished 'release' profile [optimized] target(s) in 52m 59s`（总 3189 s，首次全量编 wasmtime/cranelift/tree-sitter） |
| 现位置 | `C:\Users\USER\.cargo\bin\openidea-plugin.exe`（从 `D:\HuanJing\rust\cargo-home\bin` 复制而来） |
| 版本 | `openidea-plugin 0.1.51 (plugin API 1.15.0)`，与本机 OpenIDEA 0.1.51 匹配 |
| 子命令 | `new` / `build` / `check` / `link` / `unlink` / `links` / `keygen` / `endorse` / `pack` / `index` |

说明：CLI 是用当时的 1.100.0 编出来的单个 exe，运行不依赖编译时的 toolchain，回退后照常工作。版本号决定 `new` 写进 `Cargo.toml` 的 SDK tag（`v0.1.51`）与组件声明的 plugin API（1.15.0），**不要用 main 分支编的 CLI**。

---

## 5. 使用方式

### 5.1 Node 的版本模型（NVM Desktop，按目录切换）

| 事实 | 说明 |
| --- | --- |
| 版本来源 | NVM Desktop / nvm-windows，根 `D:\HuanJing\nvm`，shim 在 `C:\Users\USER\.nvmd\bin` |
| 已就位版本 | **22.22.3**（`D:\HuanJing\nvm\22.22.3\node.exe`），另有 22.10.0 / 22.17.1 / 24.16.0 / v24.0.0 |
| 版本锁定 | 仓库根 `.nvmdrc`，内容一行 `22.22.3`（纯文本、不带 `v`，实测 nvmd shim 认这个格式） |
| 实测 | 在含 `.nvmdrc=22.22.3` 的目录 `node -v` = 22.22.3；换到无该文件目录 = 22.10.0（shim 默认） |
| 未做 | 没有 `nvm use` 改全局当前版本、没有设全局默认、没有把 Node 目录写进 PATH |

切版本注意：`.nvmdrc` 只对走 nvmd shim 的进程生效；绕过 shim 或非交互子进程读不到时，显式用 `D:\HuanJing\nvm\22.22.3\node.exe`，或在 NVM Desktop 里把 22.22.3 设为默认。pnpm 装在 22.22.3 的全局前缀内，切到别的版本就没有 pnpm 11，需在该版本下重装。

### 5.2 新终端使用现状

| 场景 | 行为 |
| --- | --- |
| 新开 cmd / PowerShell / Git Bash | `rustc`/`cargo`/`rustup` = 默认家目录 1.98.1（含 wasm32-wasip2）；`openidea-plugin` = 0.1.51，均无需脚本 |
| 旧终端 | 读不到变更，需重开 |
| 插件仓库目录内 | `node` = 22.22.3（`.nvmdrc`）、`pnpm` = 11.29.0 |

---

## 6. CI 与新人上手

| 侧 | 做法 |
| --- | --- |
| 本地 Rust | 默认家目录 1.98.1；新增机器只需 `rustup target add wasm32-wasip2` |
| CI Rust | 仓库根 `rust-toolchain.toml` 钉 `channel = "1.98.1"`、`targets = ["wasm32-wasip2"]`、`components = ["clippy","rustfmt"]` |
| 本地 Node | 仓库根 `.nvmdrc` 写 `22.22.3`；NVM Desktop 进目录自动切 |
| CI Node | `actions/setup-node@v4` 用 `node-version: 22`（与本地 22.22.3 同大版本；要逐位一致写 `22.22.3`） |
| pnpm | `web-src/package.json` 写 `"packageManager": "pnpm@11.29.0"`，corepack 按它执行 |

需提交的文件：`.nvmdrc`、`rust-toolchain.toml`、`web-src/package.json` 的 `packageManager`、`Cargo.lock`。

---

## 7. 未解决项与风险

| 风险 / 未决项 | 现状 | 说明与建议 |
| --- | --- | --- |
| 冗余 toolchain `D:\HuanJing\rust`（2.73 GB） | 未删除，按用户要求先保留 | 其中 `cargo-home\registry` 约 0.92 GB 是依赖缓存；toolchain 本体与编译产物已不再被任何环境变量引用。**可删除的冗余 toolchain（2.73 GB）**，删了不影响现在的 Rust 使用（默认家目录在 C 盘），删除时机由用户决定 |
| 用户 PATH 尾部有 `D:\HuanJing\nvm\22.10.0` 固定路径 | 未改动（既有配置） | 它排在 nvmd shim 之后，通常不抢；若某工具直连 PATH 查 node 会拿到 22.10.0。建议在 NVM Desktop 里清理 |
| `.nvmdrc` 只在走 shim 时生效 | 已实测 | 非交互子进程可显式用版本目录 node.exe，或把 22.22.3 设为 NVM 默认 |
| 默认 toolchain 是浮动 `stable` | 1.98.1 现为默认 | 将来 `rustup update` 可能升到更高版本；要可复现就在仓库放 `rust-toolchain.toml` 钉 1.98.1 |
| 网络依赖 | 全部经 `127.0.0.1:7890` + rsproxy/npmmirror | 代理关闭时 `cargo install --git`、`nvm install`、首次拉依赖会失败 |
| MSVC 与 native 构建 | `cargo test` 原生链接成功 | `cl` 未进 PATH 不影响 cargo；手工调 `cl` 才需 `vcvars64.bat` |
| `wasm32-wasip2` 成熟度 | 1.98.1 直接产组件（实测） | 无需 nightly；遇 `wit-bindgen` 版本冲突属依赖问题 |
| cargo-nextest | 未装 | 可选：`cargo install cargo-nextest --locked` |
| 本次新增临时文件 | `D:\HuanJing\rust\*.ps1|*.cmd|*.log`、`D:\HuanJing\openidea-dev\{smoke,nvmdrc-test}`、`D:\HuanJing\_dl\{rustup-init.exe,node-22.23.5.zip,node-index.json}` | `_dl` 约 48 MB 与 `nvmdrc-test` 可删；`smoke` 可删 |

---

## 8. 附：读数原始出处

| 文件 | 内容 |
| --- | --- |
| `D:\HuanJing\rust\fresh-cmd.log` | 回退后全新环境块下的全部版本读数 |
| `D:\HuanJing\rust\smoke-build.log` | 早前一轮清理重建输出（1.100.0） |
| `D:\HuanJing\rust\verify-user-env.ps1` | 按 HKLM+HKCU+易失变量复刻新终端环境的验证脚本 |
| `D:\HuanJing\rust\rollback-rust-env.ps1` | 回退用户级 Rust 变量与 PATH 的脚本 |
| `D:\HuanJing\rust\set-user-env.ps1` | 当初写入用户级变量的脚本（历史留档，现已不生效） |
| `D:\HuanJing\openidea-dev\smoke\` | 最小 wasm32-wasip2 工程与产物 |
| `D:\HuanJing\openidea-dev\nvmdrc-test\.nvmdrc` | `.nvmdrc` 机制实测夹具（内容 `22.22.3`） |
