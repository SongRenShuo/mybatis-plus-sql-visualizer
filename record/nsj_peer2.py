import os
import sys
import time

REC = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, REC)
import bc_common as bc  # noqa: E402
import pyautogui  # noqa: E402

bc.DEV_TREE = os.path.dirname(REC)
bc.SHOTS_DIR = os.path.join(bc.DEV_TREE, "build", "MPSV-NSJ-shots")
os.environ["BC_SANDBOX"] = "MPSV-NSJ"

print("ide:", bc.ide())
bc.front()
time.sleep(1.0)
# Close the Go to Class popup left open by the previous probe.
pyautogui.keyDown("esc")
pyautogui.keyUp("esc")
time.sleep(1.2)
print("xml:", bc.shot("nsj-08-peer-xml-clean"))

pyautogui.hotkey("ctrl", "n")
time.sleep(1.4)
pyautogui.typewrite("cn.song.custcenter.mapper.CustInfoMapper", interval=0.03)
time.sleep(1.6)
pyautogui.keyDown("return")
pyautogui.keyUp("return")
time.sleep(6)
print("java:", bc.shot("nsj-09-peer-java-clean"))
print("done")