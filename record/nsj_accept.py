import os
import sys
import time

REC = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, REC)
import bc_common as bc  # noqa: E402

bc.DEV_TREE = os.path.dirname(REC)
bc.SHOTS_DIR = os.path.join(bc.DEV_TREE, "build", "MPSV-NSJ-shots")
os.environ["BC_SANDBOX"] = "MPSV-NSJ"

print("ide:", bc.ide())
bc.front()
time.sleep(1.2)
bc.open_file("cust-center/src/main/resources/mapper/CustInfoMapper.xml", wait=2.6)
time.sleep(9)
print("xml:", bc.shot("nsj-01-root-tag"))
print("done")