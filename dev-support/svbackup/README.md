# svbackup — 测试服务器备份体系（仓库资产）

这套脚本原先**只存在于目标服务器的 `/usr/local/bin/`**。2026-11-12 服务器被重装系统，脚本与 `/etc/svbackup/pass`（restic 仓库口令）一起丢失，旧仓库里的数据因此读不出来。从这次事故起，脚本维护在插件仓 `dev-support/svbackup/`，服务器上的副本由 `install-svbackup.sh` 部署，**改脚本先改仓库，再重新部署**。

同日用户授权另起新口令的仓库：新仓库用**同一七牛 bucket 的 `/v2` 路径前缀**
（`s3:s3.cn-east-1.qiniucs.com/songsrv-backup/v2`），旧仓库（根前缀）对象原封不动保留但口令已丢、不再追回。
`restic init` 与 smoke 备份均已实测通过。**绝不能对同一 bucket 直接 `restic init` 覆盖**——那会重写
`config`/`key` 让旧数据彻底不可恢复；加路径前缀是最省事的隔离方式。

## 文件

| 文件 | 作用 | 服务器落点 |
|---|---|---|
| `svbackup-collect.sh` | 枚举容器、按镜像族逻辑导出、打元数据快照、体积哨兵 | `/usr/local/bin/svbackup-collect.sh`（700） |
| `svbackup-run.sh` | 采集 → restic 上传 → `forget --keep-last 7 --prune` → 9GB 容量闸门 → 失败告警 | `/usr/local/bin/svbackup-run.sh`（700） |
| `svbackup-notify.sh` | 失败时推管理后台 `/api/v1/notify/backup`（无令牌静默跳过） | `/usr/local/bin/svbackup-notify.sh`（700） |
| `svbackup.service` / `.timer` / `svbackup-alert@.service` | systemd 调度（每日 UTC 19:00＝北京 03:00 + 5 分钟错峰）、`Persistent` 补跑、`OnFailure` 兜底 | `/etc/systemd/system/`（644） |
| `install-svbackup.sh` | 幂等部署上面这些文件并 `daemon-reload` + `enable --now svbackup.timer` | 在服务器执行，不长期驻留 |

体系设计与恢复流程见 `.agents/skills/srv-backup-restore/SKILL.md`；实例清单与凭据口径见 `.agents/skills/db-env-provisioning/SKILL.md`。

## 凭据纪律

- 脚本里**没有任何真实凭据**。运行时读 `/etc/svbackup/`（目录 700、文件 600）：
  - `credentials`：`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `RESTIC_REPOSITORY`
  - `pass`：restic 仓库口令（`--password-file`，不进 shell 历史）
  - `db.env`：各库账号口令（变量名见 skill 或 `restore-manifest.md`）
  - `notify-token` / `notify-api`：可选告警凭据
- 本地副本一律落 `.local/`（gitignored），**不进仓**。当前新仓库口令的本地副本：
  `.local/svbackup-pass-v2.txt`（600，32 字符随机），服务器侧 `/etc/svbackup/pass`（600）。
- **仓库口令必须离线另存两处**（密码管理器 + 另一台设备）。这是 2026-11-12 事故的直接教训：口令只随服务器存在时，重装等于备份永久失效。当前只满足「服务器 + 本地 `.local/`」两处，**仍需用户再存一份到密码管理器或另一台设备**。
- 旧仓库（根前缀）的口令已丢且用户决定放弃，不要再尝试 `restic init` 覆盖它。

## 部署到全新服务器

```bash
# 1) restic 0.19.1：本机 GitHub 必断，走本地代理下 .bz2 → DBX SFTP 上传（<16MB）→ python bz2 解包
#    （bunzip2 在全新系统上可能没有；python3 的 bz2 模块一定在）
mkdir -p /root/.local/bin
python3 -c "import bz2,shutil; f=bz2.open('/root/restic_0.19.1_linux_amd64.bz2','rb'); \
o=open('/root/.local/bin/restic','wb'); shutil.copyfileobj(f,o); o.close(); f.close()"
chmod +x /root/.local/bin/restic && /root/.local/bin/restic version

# 2) 凭据就位（从离线副本恢复；AK/SK 本地副本在 .local/qiniu-keys.env）
mkdir -p /etc/svbackup && chmod 700 /etc/svbackup
#   credentials / pass / db.env / notify-token / notify-api 逐个落 600

# 3) 上传本目录到服务器 /root/svbackup-src/，执行安装
bash /root/svbackup-src/install-svbackup.sh

# 4) 验收（详见 install-svbackup.sh 末尾清单 + skill §3.2 恢复演练）
systemctl start svbackup.service && journalctl -u svbackup -n 50 --no-pager
```

## 本次重建沉淀的四条经验

1. **口令离线双份**：`pass` 只放服务器＝没备份。重装后 `--insecure-no-password snapshots` 会明确报 `wrong password or no key found`；此时 AK/SK 仍有效、仓库对象仍在，但数据读不出。用户 2026-11-12 决定放弃旧仓库口令，旧快照数据不再追回。
2. **脚本必须入库**：只放服务器的脚本随重装蒸发，且无法逐字复刻（本次按架构文档与 skill 重写，行为对齐但非逐字还原）。
3. **未知引擎族一律 SKIP，不做文件级 tar**：2026-11-08 PanWeiDB 落「未知族」→ 两个容器各 3GB 整卷 tar → 两天打爆七牛 10GB 免费额度。脚本默认 `SKIP_FILE_LEVEL_TAR=1`，只告警；真要文件级备份必须显式关掉开关并承担体积风险，另有 500MB 体积哨兵。
4. **采集器不能因单库失败中断主流程**：每库 2 次尝试、stdin 接 `/dev/null` 防交互等口令、失败写日志并计数，run 阶段据此发告警但不假绿。
5. **主流程必须真跑一次才验收**：本次 `systemctl start svbackup.service` 实跑抓出三个 bug 并修掉——
   ① 容量闸门把 `Total Size: 12.343 KiB` 的第 3 列当数值、单位带前导空格导致 `case` 匹配不上
   （改成 `awk '{print $3, $4}'` 分离数值+单位）；② `forget --prune` 撞上 stale lock 直接失败
   （加 `--retry-lock 2m`，并按日志提示用 `restic unlock` 清残留锁）；
   ③ `probe_tool` 只用 `command -v`，国产库装在 `/opt/...` 且不在 PATH 的 `pg_dump`/`vsql` 全漏
   （Vastbase 两实例都报「找不到 psql/gsql」），改成绝对路径走容器内 `test -x`、命令名走 `command -v`。
   修复后复跑：`仓库体积 27877 B（闸门 9663676416 B）` 正确解析、rc=0。

## 已知边界

- Oracle / Redis / Mongo 等全新引擎落「未知族」被 SKIP；要做逻辑导出需在 `svbackup-collect.sh` 的 `case` 里加族函数（Redis `BGSAVE` 后拷 RDB、Mongo `mongodump`）。
- 容量闸门解析 `restic stats --mode raw-data` 的 `Total Size:` 文本；拿不到读数（单位不在 KiB/MiB/GiB）时会告警并跳过闸门，不会静默放行。
- 采集器对 Vastbase/GaussDB 等国产库的客户端路径按实测清单探测；新引擎若工具路径不在清单里会 WARN 跳过该库，需在 `probe_tool` 的候选列表里补绝对路径。
- 这套脚本只保证「新快照可写、可恢复」，不保证「旧仓库可读」——旧仓库需要原口令。
