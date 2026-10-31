# -*- coding: utf-8 -*-
"""开录前 / 点击后自检：把「录完才发现坏了」提前到「开录前拦下」。

设计依据（2026-10-12 大范围录制实测）：4 张成品跑了 **8 次 take**，返工几乎全是三类**可自检**问题：

  1. **点击落空** —— 坐标偏 / 未聚焦子窗口吞掉首击 / 上游开关把整组置灰（症状都一样：点完没反应）；
  2. **末帧被浮层挡住** —— 插件的列文档浮层会「粘住」（鼠标离开也不退），末帧通常就是 poster；
  3. **成片时长骤短** —— 纯凝视 take 的鼠标移动不产生像素变化，被静段裁剪整段吃掉（实测 3.6s vs 7.5s）。

用法（scene 脚本里）：

```python
from verify import Guard
g = Guard()
g.mark("preview")                       # 开录前：记基准
...
g.require("preview", msg="预览未就绪")   # 开录前断言，不符就中止（别录坏画面）
r.start()
g.after_click((1256, 829), name="preview", msg="点『执行 SQL』落空")
```

`Guard` 只做「有没有可见变化」的判定，**不判语义**——语义由调用方给的 `name`/`msg` 表达。
"""
import time

import numpy as np

from locate import grab


def fingerprint(region, grid=8):
    """区域指纹：灰度均值、标准差 + `grid×grid` 分块均值（分块才能抓住「小区域变了但全图均值没动」）。"""
    a = grab(region)
    h, w = a.shape
    ys = np.linspace(0, h, grid + 1).astype(int)
    xs = np.linspace(0, w, grid + 1).astype(int)
    blocks = [a[ys[i]:ys[i + 1], xs[j]:xs[j + 1]].mean()
              for i in range(grid) for j in range(grid)]
    return {'mean': float(a.mean()), 'std': float(a.std()), 'blocks': np.array(blocks)}


def differs(a, b, thr=6.0):
    """两个指纹是否有可见差异：分块均值**最大差** > `thr`。

    取最大差而不是全图均值差——全图均值会把小区域的变化稀释掉（这与 `gifkit`/`assemble`
    的静段判定用 16×16 分块取最大是同一个道理）。
    """
    return float(np.abs(a['blocks'] - b['blocks']).max()) > thr


def diff_score(a, b):
    """差异分值（分块最大差），用于打印与调阈值。"""
    return float(np.abs(a['blocks'] - b['blocks']).max())


class Guard:
    """状态守卫：`mark()` 记基准 → `require()` 断言 → `after_click()` 验变化。"""

    def __init__(self, region=None):
        self.region = region
        self.base = {}

    def _region(self, region):
        r = region or self.region
        if r is None:
            raise ValueError('未给 region：Guard(region=(l,t,w,h)) 或逐次传 region=')
        return r

    def mark(self, name, region=None):
        """记录基准指纹（同一名字可覆盖）。"""
        self.base[name] = fingerprint(self._region(region))
        return self.base[name]

    def require(self, name, region=None, msg='', thr=6.0):
        """断言当前画面与基准**一致**；不一致抛 `AssertionError`（用于开录前自检：不符就中止）。

        典型用途：开录前确认「预览面板确实开着」「设置页停在预期页」「浮层已清干净」。
        """
        cur = fingerprint(self._region(region))
        score = diff_score(self.base[name], cur)
        if score > thr:
            raise AssertionError('%s（与基准差异 %.1f > %.1f）' % (msg or '画面与基准不符', score, thr))
        return score

    def changed(self, name, region=None, wait=0.25, thr=6.0):
        """等 `wait` 秒后判定画面与基准**不同**（不同＝动作生效）；返回布尔，不抛错。"""
        time.sleep(wait)
        cur = fingerprint(self._region(region))
        score = diff_score(self.base[name], cur)
        return score > thr, score

    def after_click(self, xy, name=None, region=None, wait=0.25, thr=6.0, retries=1, msg=''):
        """点击后验变化：无变化即判定落空，按 `retries` 重试；仍无变化则抛 `AssertionError`。

        「落空」的三种真实成因（症状相同，都靠这一件拦下）：坐标偏、未聚焦子窗口吞首击、
        上游开关把整组控件置灰。`name` 给基准名；不给则用点击前的即时快照当基准。
        """
        import pyautogui
        reg = self._region(region)
        base = self.base.get(name) if name else fingerprint(reg)
        score = 0.0
        for attempt in range(retries + 1):
            pyautogui.click(*xy)
            time.sleep(wait)
            score = diff_score(base, fingerprint(reg))
            if score > thr:
                return True
            if attempt < retries:
                time.sleep(0.2)
        raise AssertionError('%s（点击 %s 后画面无变化，差异 %.1f ≤ %.1f）'
                             % (msg or '点击落空', xy, score, thr))


if __name__ == '__main__':
    print(__doc__)
