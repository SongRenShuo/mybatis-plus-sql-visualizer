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

print("ide:", bc.ide())
bc.front()
time.sleep(1.0)

for i, (x, y) in enumerate(((357, 330), (379, 330)), start=1):
    pyautogui.moveTo(x, y)
    time.sleep(2.4)
    print("tip%d:" % i, bc.shot("navyield-1%d-tip" % i))
print("done")