import os
import sys
import time

REC = r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer\record"
sys.path.insert(0, REC)
import bc_common as bc  # noqa: E402

# bc_common 默认按 dev 工作树找沙盒；本次沙盒跑在主仓 build 下，这里覆盖。
bc.DEV_TREE = r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer"
bc.SHOTS_DIR = os.path.join(bc.DEV_TREE, "build", "MPSV-NavYield-shots")
os.environ["BC_SANDBOX"] = "MPSV-NavYield"

print("ide:", bc.ide())
bc.front()
time.sleep(1.2)
bc.open_file("CustInfoMapper", wait=2.2)
time.sleep(1.8)
print("java:", bc.shot("navyield-01-java"))
bc.open_file("CustInfoMapper.xml", wait=2.2)
time.sleep(1.8)
print("xml:", bc.shot("navyield-02-xml"))
print("done")