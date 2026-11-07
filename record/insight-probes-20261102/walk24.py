import time
import walk_util as W
import robot
import pyautogui
import pyperclip

VALID = '{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"demo-svc"}}]},"scopeSpans":[{"spans":[{"traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"00f067aa0ba902b7","name":"db.query","startTimeUnixNano":"1700000000000000000","endTimeUnixNano":"1700000002000000000","status":{"code":"1"},"attributes":[{"key":"db.system","value":{"stringValue":"mysql"}},{"key":"db.operation","value":{"stringValue":"SELECT"}}]}]}]}]}'
BAD = 'not-json {{'

W.front()
time.sleep(0.3)
comps = robot.find_components("//div[contains(@class,'JTextArea')]")
cand = []
for c in comps:
    if c.get("width", 0) < 200:
        continue
    try:
        x, y = robot.component_screen_center("//div[contains(@class,'JTextArea')]", comp=c)
    except Exception:
        continue
    cand.append((x, y, c.get("width"), c.get("height")))
W.log("textarea candidates:", cand)
# 选面板内的输入区（y 在面板中部 300~420）
target = [c for c in cand if 300 <= c[1] <= 430]
W.log("chosen:", target)
if target:
    x, y = target[0][0], target[0][1]
    W.assert_front()
    pyautogui.click(x, y)
    time.sleep(0.4)
    pyperclip.copy(BAD)
    pyautogui.hotkey("ctrl", "v")
    time.sleep(1.0)
    W.shot("w24-bad-pasted")
    btn = [c for c in robot.find_components("//div[contains(@class,'JButton') and contains(@accessiblename,'导入')]")
           if c.get("width", 0) > 0]
    W.log("import buttons:", len(btn))
    if btn:
        bx, by = robot.component_screen_center("//div[contains(@class,'JButton') and contains(@accessiblename,'导入')]", comp=btn[0])
        pyautogui.click(bx, by)
        time.sleep(2.5)
    W.shot("w24-bad-imported")
