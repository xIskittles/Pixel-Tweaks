#!/bin/sh
# Full automated shade test suite. Requires unlocked phone.
set -e
cd /home/user/LockCenter
export PATH=/home/user/lc-patch/android-sdk/platform-tools:$PATH
PY=/home/user/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/bin/python3
sh tools/ready.sh
echo "== 1. right swipe -> full QS"
python3 tools/drag.py 1100 30 1100 2700 1.2 0.3; sleep 3
adb exec-out screencap -p > /tmp/s_1.png
echo "== 2. fast close drag -> must end on home"
python3 tools/drag.py 600 2500 600 250 0.30; sleep 2
adb exec-out screencap -p > /tmp/s_2.png
echo "== 3. left swipe -> half panel"
sh tools/ready.sh
python3 tools/drag.py 300 30 300 1500 1.0 0.3; sleep 2.5
adb exec-out screencap -p > /tmp/s_3.png
echo "== 4. top-right corner -> full QS"
python3 tools/drag.py 1100 30 1100 400 0.5 0.2; sleep 2.5
adb exec-out screencap -p > /tmp/s_4.png
echo "== 5. top-left corner -> back to half panel"
python3 tools/drag.py 300 30 300 400 0.5 0.2; sleep 2.5
adb exec-out screencap -p > /tmp/s_5.png
$PY - <<'PY'
from PIL import Image
ims=[Image.open(f"/tmp/s_{i}.png") for i in range(1,6)]
w,h=ims[0].size; s=0.16
c=Image.new("RGB",(int(w*s)*5,int(h*s)))
for i,a in enumerate(ims): c.paste(a.resize((int(w*s),int(h*s))),(int(w*s)*i,0))
c.save("/tmp/suite.png")
print("montage /tmp/suite.png")
PY
