# dev-support/acceptance — 沙盒 UI 验收工具层

243/262 runIde 沙盒 UI 验收的通用可复用脚本集（提权 pyautogui + 图像锚点 + 组件树定位三路线）。
口径与执行清单见 `BC_RUNBOOK.md`；验收证据与批次计划见
`docs/验收文档/20261016-T-DevFullUI-合流后全功能沙盒验收记录.md`。

## 工具层

| 文件 | 用途 |
|---|---|
| `gifkit.py` | 截图/键鼠封装/gutter 图标像素检测 |
| `locate.py` | 窗口几何（window_rect/button_click_point/bright_band_y） |
| `anchor.py` | 图像锚点定位（模板截图→当前屏匹配→点匹配中心） |
| `robot.py` | 组件树定位（robot-server 协议；x/y 为父相对坐标，沿祖先链求屏幕点） |
| `verify.py` | Guard：像素断言（点击前后变化自证） |
| `bc_common.py` | 验收统一引导：沙盒 PID 自寻（config/.lock）/win32 剪贴板直读与真清空/按钮定位三路线/robot 探活 |
| `scene_template.py` | 演示录制场景骨架 |
| `_run_elev.bat` | 提权运行包装（bat 必须纯 ASCII） |
| `corpus-feed.sh` | demo 8081 语料批处理（基础查询/敏感值 INSERT/批量/边界族） |

## probes/ — 批 C 验收探针（20261018）

`probe_bc_U04/U10/U17/U19/U20/U23/U29/U30/U33/U42/U43.py`——按组自包含，
全动态定位（robot 按名 > anchor 模板 > 窗口几何，无手工屏幕坐标），
每组断言与失败判据见 `BC_RUNBOOK.md`。执行方式一律提权：

```
record/_run_elev.bat dev-support/acceptance/probes/probe_bc_U10.py
```

## 硬纪律与已知限制

- **沙盒 IDE 是提权进程**：非提权 SendInput 被 Windows UIPI 拦掉（click 不落地），
  所有输入类操作必须经 `_run_elev.bat` 提权；bat 内容纯 ASCII。
- 部分脚本内含本机绝对路径（`F:/code/...`），跨机复用改脚本头常量。
- gutter 图标右键（`getRightButtonClickAction` 链）对合成输入不触发（左键正常）——
  相关断言以截图人工判读为准，真机人工右键复验结论回填。
- `ac.py`（点击统一入口：DPI 诊断+锚点+组件树+点击自证）在途，交付后补入本目录。
