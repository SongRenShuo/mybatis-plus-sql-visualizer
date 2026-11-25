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

# XML whose statements have NO matching interface methods.
bc.open_file("cust-center/src/main/resources/mapper/CtAccount0002Mapper.xml", wait=2.6)
time.sleep(7)
print("xml:", bc.shot("nsj-04-nomethod-xml"))

# Put the caret on the statement id and press Ctrl+B -> should degrade to the interface class.
pyautogui.hotkey("ctrl", "f")
time.sleep(0.9)
pyautogui.typewrite("selectByTenant", interval=0.03)
time.sleep(0.9)
pyautogui.keyDown("return")
pyautogui.keyUp("return")
time.sleep(0.7)
pyautogui.keyDown("esc")
pyautogui.keyUp("esc")
time.sleep(0.6)

pyautogui.hotkey("ctrl", "b")
time.sleep(4.5)
print("after-ctrl-b:", bc.shot("nsj-05-nomethod-ctrlb"))
print("done")