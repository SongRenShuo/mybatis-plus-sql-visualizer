# -*- coding: utf-8 -*-
"""像素级菜单项定位：更多 → Down×1 截图找高亮条带 → 推算目标项 y → 点击（通用验证）。"""
import sys, time
sys.path.insert(0, r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer\record")
import ac, bc_common as bc, nav, pyautogui
from PIL import ImageGrab, ImageChops
SHOTS = r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer-dev\build\ui243-shots"

ac.ensure_ide_front(); time.sleep(0.5)
pyautogui.press("esc"); time.sleep(0.8)
pyautogui.moveTo(117, 55, duration=0.2); pyautogui.click(); time.sleep(0.8)
pyautogui.hotkey("ctrl", "g"); time.sleep(0.7)
ac.paste_text("27"); time.sleep(0.3); pyautogui.press("enter"); time.sleep(0.7)
nav.trigger_preview(wait=3.0, use_find_action=True)
pyautogui.press("enter"); time.sleep(3.0)
rect = bc.preview_rect()
print("rect:", rect)
if not rect: sys.exit(2)
# 更多 → 菜单开（无高亮态截图）
pyautogui.moveTo(rect[2] - 72, rect[1] + 68, duration=0.2)
pyautogui.click(); time.sleep(1.3)
MENU = (rect[2] - 92, rect[1] + 72, rect[2] + 8, rect[1] + 250)
a = ImageGrab.grab(bbox=MENU)
# Down×1 → 高亮首项截图
pyautogui.press("down"); time.sleep(0.5)
b = ImageGrab.grab(bbox=MENU)
diff = ImageChops.difference(a.convert("RGB"), b.convert("RGB")).getbbox()
print("highlight band bbox (rel):", diff)
if not diff: sys.exit(2)
y0, y1 = diff[1], diff[3]
h = y1 - y0
print("first item y (screen):", MENU[1] + y0, "item height:", h)
# 差异对比 = 第 3 项
target_y = MENU[1] + y0 + 2 * h + h // 2
print("target click y:", target_y, "x:", MENU[0] + 60)
# 先 Down 到第 3 项（保持视觉与点击一致），再点击目标坐标
pyautogui.press("down"); time.sleep(0.25)
pyautogui.press("down"); time.sleep(0.35)
pyautogui.moveTo(MENU[0] + 60, target_y, duration=0.25)
time.sleep(0.5)
ImageGrab.grab(bbox=MENU).save(SHOTS + "\imgpix-1-hover.png")
pyautogui.click(); time.sleep(4.0)
ImageGrab.grab().save(SHOTS + "\imgpix-2-result.png")
print("done")
