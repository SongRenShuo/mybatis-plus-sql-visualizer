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
# Hover the XML root-tag gutter icon (line 3) to read its tooltip and identify the owner.
pyautogui.moveTo(361, 157)
time.sleep(2.6)
print("tip:", bc.shot("nsj-10-peer-root-tip"))
print("done")