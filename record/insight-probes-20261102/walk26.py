import time
import walk_util as W
import robot
import pyautogui
import pyperclip

VALID = '{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"demo-svc"}}]},"scopeSpans":[{"spans":[{"traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"00f067aa0ba902b7","name":"db.query","startTimeUnixNano":"1700000000000000000","endTimeUnixNano":"1700000002000000000","status":{"code":"1"},"attributes":[{"key":"db.system","value":{"stringValue":"mysql"}},{"key":"db.operation","value":{"stringValue":"SELECT"}}]}]}]}]}'
BAD = 'not-json {{'

W.front()
time.sleep(0.3)
W.assert_front()

def import_click():
    xp = "//div[contains(@class,'JButton') and (contains(@accessiblename,'导入') or contains(@name,'导入'))]"
    comps = [c for c in robot.find_components(xp) if c.get("width", 0) > 0]
    W.log("import buttons:", len(comps))
    if not comps:
        return False
    bx, by = robot.component_screen_center(xp, comp=comps[0])
    W.log("import btn at", bx, by)
    pyautogui.click(bx, by)
    return True

# 1) 非法 JSON
pyautogui.click(700, 360)
time.sleep(0.4)
pyautogui.hotkey("ctrl", "a")
pyperclip.copy(BAD)
pyautogui.hotkey("ctrl", "v")
time.sleep(1.0)
W.shot("w26-bad-pasted")
W.log("import:", import_click())
time.sleep(2.5)
W.shot("w26-bad-imported")

# 2) 合法 OTLP JSON
pyautogui.click(700, 360)
time.sleep(0.4)
pyautogui.hotkey("ctrl", "a")
pyperclip.copy(VALID)
pyautogui.hotkey("ctrl", "v")
time.sleep(1.0)
W.shot("w26-good-pasted")
W.log("import:", import_click())
time.sleep(3.0)
W.shot("w26-good-imported")
