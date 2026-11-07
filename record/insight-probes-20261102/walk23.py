import time
import walk_util as W
import robot
import pyautogui
import pyperclip

VALID = '{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"demo-svc"}}]},"scopeSpans":[{"spans":[{"traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"00f067aa0ba902b7","name":"db.query","startTimeUnixNano":"1700000000000000000","endTimeUnixNano":"1700000002000000000","status":{"code":"1"},"attributes":[{"key":"db.system","value":{"stringValue":"mysql"}},{"key":"db.operation","value":{"stringValue":"SELECT"}}]}]}]}]}'

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(1488, 234)
time.sleep(1.0)
for _ in range(5):
    pyautogui.press("down")
    time.sleep(0.2)
pyautogui.press("enter")
time.sleep(3.0)
W.shot("w23-trace-panel")

# 面板里的输入区（JTextArea）
comps = [c for c in robot.find_components("//div[contains(@class,'JTextArea')]") if c.get("width", 0) > 200]
W.log("textareas:", len(comps))
if comps:
    x, y = robot.component_screen_center("//div[contains(@class,'JTextArea')]", comp=comps[0])
    W.log("paste at", x, y)
    pyautogui.click(x, y)
    time.sleep(0.3)
    pyperclip.copy(VALID)
    pyautogui.hotkey("ctrl", "v")
    time.sleep(1.2)
    W.shot("w23-trace-pasted")
    # 点「导入粘贴的JSON」
    hit = False
    for c in robot.find_components("//div[contains(@class,'JButton')]"):
        nm = c.get("accessiblename") or c.get("name") or ""
        if "导入" in nm and c.get("width", 0) > 0:
            X, Y = robot.component_screen_center("//div[contains(@class,'JButton')]", comp=c)
            W.log("click import button:", nm, X, Y)
            pyautogui.click(X, Y)
            hit = True
            break
    W.log("import button hit:", hit)
    if not hit:
        # 兜底：键盘 Tab 到按钮不可靠，记录未点
        W.log("WARN 未找到导入按钮")
    time.sleep(3.0)
    W.shot("w23-trace-imported")
    for key in ("span", "Trace", "导入"):
        rows = W.texts(key, 8)
        W.log("== %s (%d)" % (key, len(rows)))
        for r in rows:
            W.log("   ", r)
