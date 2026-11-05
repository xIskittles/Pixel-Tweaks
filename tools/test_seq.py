#!/usr/bin/env python3
"""Sequence test: right-open QS -> close -> left-open (must be the half panel, not full QS)."""
import subprocess, time
from PIL import Image
def sh(c): return subprocess.run(["adb","shell",c],capture_output=True,text=True).stdout
def drag(*a): subprocess.run(["python3","/home/user/LockCenter/tools/drag.py",*map(str,a)])
def shot(n): 
    subprocess.run(f"adb exec-out screencap -p > /tmp/{n}.png",shell=True)
sh("input keyevent KEYCODE_HOME"); time.sleep(1.5); sh("cmd statusbar collapse"); time.sleep(2)
drag(1100,30,1100,2700,1.2,0.3); time.sleep(3); shot("t1")          # right: full QS
drag(600,2500,600,250,0.5); time.sleep(3); shot("t2")               # fast close
drag(300,30,300,1500,1.0,0.3); time.sleep(3); shot("t3")            # left: half panel
drag(600,2200,600,250,0.5); time.sleep(3); shot("t4")               # close
drag(300,30,300,1500,1.0,0.3); time.sleep(3); shot("t5")            # left again
ims=[Image.open(f"/tmp/t{i}.png") for i in range(1,6)]; w,h=ims[0].size; s=0.12
c=Image.new("RGB",(int(w*s)*5,int(h*s)))
for i,a in enumerate(ims): c.paste(a.resize((int(w*s),int(h*s))),(int(w*s)*i,0))
c.save("/tmp/t_seq.png")
