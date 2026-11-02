import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui001_util as W
import robot
import pyautogui
from PIL import Image, ImageChops

W.front()
time.sleep(0.4)
W.assert_front()


def find_box(label):
    xp = "//div[contains(@accessiblename,'%s')]" % label
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    if not hits:
        return None, None
    return robot.component_screen_center(xp, comp=hits[0]), hits[0]


def crop_rect(c, pt):
    """组件屏幕矩形（左缘 = 中心 - 宽/2，上缘 = 中心 - 高/2）。"""
    w, h = int(c["width"]), int(c["height"])
    return (pt[0] - w // 2, pt[1] - h // 2, pt[0] + w // 2 + 1, pt[1] + h // 2 + 1)


def diff_count(a_path, b_path, box):
    a = Image.open(a_path).crop(box)
    b = Image.open(b_path).crop(box)
    return ImageChops.difference(a.convert("RGB"), b.convert("RGB")).getbbox()


LABELS = ("单次批准此SQL对", "已审阅SELECT函数副作用", "两段SQL均已填入明确字面参数",
          "批准存在的原样替换片段", "确认两段SQL方言适用于所选目标")
for i, label in enumerate(LABELS):
    pt, c = find_box(label)
    W.log("BOX", label, pt, c.get("width"), c.get("height"))
    if not pt:
        continue
    box = crop_rect(c, pt)
    before = W.shot("ui07-before-%d" % i)
    pyautogui.click(pt[0], pt[1])
    time.sleep(0.8)
    after = W.shot("ui07-after-%d" % i)
    W.log("DIFF", label, diff_count(before, after, box))

# 发现目标（填下拉）
pt, c = find_box("发现目标")
if pt:
    pyautogui.click(pt[0], pt[1])
    time.sleep(3.0)
    W.shot("ui07-targets")
    W.log("clicked 发现目标", pt)
W.log("DONE")
