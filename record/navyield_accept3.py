import os
import sys
import time

REC = r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer\record"
sys.path.insert(0, REC)
import bc_common as bc  # noqa: E402
import pyautogui  # noqa: E402

bc.DEV_TREE = r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer"
bc.SHOTS_DIR = os.path.join(bc.DEV_TREE, "build", "MPSV-NavYield-shots")
os.environ["BC_SANDBOX"] = "MPSV-NavYield"

pid, hwnd, rect = bc.ide()
print("ide:", pid, hwnd, rect)
bc.front()
time.sleep(1.0)

# Click the first tab (cust-center CustInfoMapper.xml) — the second tab is another module's same-name file.
l, t = rect[0], rect[1]
pyautogui.click(l + 150, t + 57)
time.sleep(4.0)
print("xml:", bc.shot("navyield-05-xml"))

# Open the Java mapper interface via Go to Class (FQN), which is unambiguous.
pyautogui.hotkey("ctrl", "shift", "o")
time.sleep(1.4)
pyautogui.typewrite("cn.song.custcenter.mapper.CustInfoMapper", interval=0.03)
time.sleep(1.6)
pyautogui.keyDown("return")
pyautogui.keyUp("return")
time.sleep(8.0)
print("java:", bc.shot("navyield-06-java"))
print("done")