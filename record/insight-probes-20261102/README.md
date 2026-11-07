# insight 工作树未跟踪探针归档（2026-11-02）

来源：`mpsv-sql-insight` 工作树（分支 `t-sql-insight`）`record/` 下 297 个未跟踪文件，
分支本身 227 个提交尚未并入 dev（合流债待裁决），为防工作树回收时丢失，先行归档于此。

## 内容分类

| 前缀/文件 | 用途 |
|---|---|
| `walk00~84`、`walk_util.py` | 面板全功能走查系列（84 步脚本重放） |
| `whitebar_*`、`whitebar3_*`、`whitebar3_util.py`、`whitebar3_win.py` | 深色主题白条专项排查（JSplitPane Metal 分隔条问题） |
| `ui001_*`、`ui001_kill*.ps1`、`ui001_util.py` | UI-001 验收批（信任弹窗/设置页/仪表盘/gutter） |
| `rm_e01~e91`、`rm_lib.py` | 真机环境重建（RM）验收批，含驱动/数据源/预览/捕获链 |
| `w*`、`s_*` | 设置页与数据源切换专项 |
| `d_*`、`i_*`、`kill_i*.py`、`ps_procs.ps1` | 单点诊断探针（连接/对话框/DPI/驱动/front 抢前台） |

## 复用提示

- 通用工具库：`walk_util.py`、`whitebar3_util.py`、`whitebar_util.py`、`rm_lib.py`、`ui001_util.py`
- 运行前提与像素带/图像锚点方法见 `.agents/skills/ide-ui-acceptance/SKILL.md`
- 一次性诊断探针（`d_*`/`i_*`/`kill_*`）仅供排查参考，不建议直接复跑
- 已排除：`__pycache__/`、`_elev_*.log` 等日志与缓存
