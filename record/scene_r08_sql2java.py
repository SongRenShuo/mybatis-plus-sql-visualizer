# -*- coding: utf-8 -*-
"""R08 SQL → Java 反向转换重录（T-DocsRefresh1023）：工具窗「SQL → Java」页签 →
粘贴 SQL → 转换 → 切 Lambda 形态重生成 → 复制。

相对 20260913 版（scene_t6）的差异：
- 页签已去 `(Beta)`（T-SqlToJavaPromote）；
- 坐标按当前工具窗宽度重标；
- 页签切换用像素点（robot 组件树里 TabLabel 有两层同名，click_component 会拒多点）。

前置（prep）：SQL Visualizer 工具窗已开、SQL → Java 页签激活、输入/输出区为空。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import Recorder, click, paste, shot, wait_stable

REGION = (277, 25, 1370, 984)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "r08_sql2java.mp4")

OX, OY = REGION[0], REGION[1]

TAB_SQL2JAVA = (1035, 97)      # 页签（region 相对）
SQL_INPUT = (1100, 300)        # SQL 输入区
RUN = (888, 128)               # ▶ 转换
FMT_DROPDOWN = (1086, 162)     # 形态下拉
FMT_LAMBDA = (1090, 260)       # 下拉项 LambdaQueryWrapper
COPY_OUTPUT = (888, 538)       # 输出区复制

# 用 t_order（demo 里唯一的表名）而不是 user——user 在多个 demo 模块都有实体，
# 反向转换会弹「选择要转换的 SQL」多候选框挡住画面
SQL = """SELECT id, order_no, user_id, amount, status
FROM t_order
WHERE status = 1 AND amount >= 100
ORDER BY id DESC"""


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def ensure_tab():
    """像素验证 SQL → Java 页签已选中：活动标签暗蓝药丸（region y≈90~110, x≈1030~1090）。"""
    import numpy as np
    from PIL import Image as _Image
    for i in range(4):
        path = os.path.join(BASE, "perf", "_tab_r08.png")
        shot(REGION, path)
        img = np.asarray(_Image.open(path).convert("RGB"), dtype=int)
        reg = img[88:112, 1025:1095]
        r, g, b = reg[:, :, 0], reg[:, :, 1], reg[:, :, 2]
        n = int(((b > 60) & (b - r > 30)).sum())
        if n > 300:
            print("sql2java tab confirmed" if not i else f"confirmed on retry {i}")
            return True
        click(*abs_pt(TAB_SQL2JAVA), move_dur=0.25)
        time.sleep(1.3)
    raise RuntimeError("SQL -> Java tab not active")


def main():
    ensure_tab()
    time.sleep(0.6)

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(0.5)
        click(*abs_pt(SQL_INPUT), move_dur=0.3)      # 1. 粘贴 SQL
        paste(SQL)
        time.sleep(0.6)

        click(*abs_pt(RUN), move_dur=0.3)            # 2. 转换
        wait_stable(REGION)
        time.sleep(1.1)

        click(*abs_pt(FMT_DROPDOWN), move_dur=0.3)   # 3. 形态下拉
        time.sleep(0.9)
        click(*abs_pt(FMT_LAMBDA), move_dur=0.3)     # 4. 切 LambdaQueryWrapper
        time.sleep(0.7)
        click(*abs_pt(RUN), move_dur=0.3)            #    重生成
        wait_stable(REGION)
        time.sleep(1.5)

        click(*abs_pt(COPY_OUTPUT), move_dur=0.3)    # 5. 复制生成代码
        time.sleep(0.9)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
