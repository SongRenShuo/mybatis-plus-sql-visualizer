import os
import sys
import time

REC = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, REC)
import bc_common as bc  # noqa: E402
import pyautogui  # noqa: E402

bc.DEV_TREE = os.path.dirname(REC)
bc.SHOTS_DIR = os.path.join(bc.DEV_TREE, "build", "MPSV-NSJX-shots")
os.environ["BC_SANDBOX"] = "MPSV-NSJX"

print("ide:", bc.ide())
bc.front()
time.sleep(1.2)

# Java mapper interface: with yield ON + MyBatisX present, only MyBatisX should draw here.
pyautogui.hotkey("ctrl", "n")
time.sleep(1.5)
pyautogui.typewrite("cn.song.mybatisplusdemo.mapper.OrderMapper", interval=0.03)
time.sleep(1.8)
pyautogui.keyDown("return")
pyautogui.keyUp("return")
time.sleep(8)
print("java:", bc.shot("nsjx-01-java"))

# Hover the first gutter icon on the first method line to read its tooltip.
pyautogui.moveTo(310, 300)
time.sleep(2.4)
print("tip:", bc.shot("nsjx-02-java-tip"))

# Same-name XML for the other side.
pyautogui.hotkey("ctrl", "shift", "n")
time.sleep(1.3)
pyautogui.typewrite("OrderMapper.xml", interval=0.03)
time.sleep(1.5)
pyautogui.keyDown("return")
pyautogui.keyUp("return")
time.sleep(7)
print("xml:", bc.shot("nsjx-03-xml"))
print("done")