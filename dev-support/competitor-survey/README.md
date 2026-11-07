# competitor-survey —— 竞品横向对比调研脚本之家

来源：20261103 竞品横向对比第三十五轮（A 路 Marketplace 抓取与对账，218 请求 0 失败）。
此前的 r34 留档放在系统临时目录，被清理后第三十五轮只能从报告存量读数重建基线——
本轮起脚本沉淀进仓，**原始 JSON 数据仍留临时目录**（体量几百个文件，不入仓）。

## 文件

| 文件 | 用途 |
|---|---|
| `fetch_all.sh` | 抓竞品 ID 清单的四路端点（base / updates / comments / rating），请求日志落 `_reqlog.txt` |
| `fetch_kw.sh` | 8 个关键词全翻页（sql explain / sql preview / sql log / sql visualizer / mybatis / mybatis log / jpa sql / sql formatter），取位次与新面孔候选 |
| `fetch_candidates.sh` | 对新面孔候选逐一抓 updates 最末行日期判「真首发」（口径：base.cdate 是最近发版时间，不能当注册时间） |
| `analyze_r35.py` | 汇总生成 `delta-table.json`（每家 上轮值/现值/增量/窗口天数/折算日增量） |

## 每轮用法

1. **建数据目录**并改抓取脚本的 `DATA` 变量指向它（惯例 `C:/Users/USER/AppData/Local/Temp/mpsv-survey-r<N>`）：
   ```bash
   mkdir -p C:/Users/USER/AppData/Local/Temp/mpsv-survey-r36
   ```
2. **跑三个抓取脚本**（直连失败自动走本地代理重试的兜底逻辑在 fetch 函数里；全部 curl，禁 python requests——走代理 TLS 被掐）。
3. **改 `analyze_r35.py` 两处再复跑**：`R34_SNAP/R35_SNAP` 两个快照时刻、`R34 = {...}` 基线字典（直接抄上轮报告主表）。
   ```bash
   python analyze_r35.py C:/Users/USER/AppData/Local/Temp/mpsv-survey-r36
   ```
4. `delta-table.json` 就是下一轮的 diff 基线——但**以当轮报告主表为准入库，临时目录随时会被清理**。

## 口径备忘（承调研报告口径注）

- `searchPlugins` 不传 `max`，用 offset 翻页。
- 均分由 votes 的 `{星数: 票数}` 加权重算；rating API 的 `meanRating/meanVotes` 是干扰值（15075 实测 4.06 vs 真值 4.94）。
- 位次普涨判读须对照搜索结果总量漂移（同窗结果总数也在变，名次可凭空抬高）。
- 新面孔判定用 updates 最末行日期（≈首次发布），不用 base.cdate。
- 竞品 ID 清单每轮在主表基础上滚动维护；盯防组见各轮调研报告 §12。
