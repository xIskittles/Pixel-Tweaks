import subprocess,time
from PIL import Image
def sh(c): return subprocess.run(["adb","shell",c],capture_output=True,text=True).stdout
def drag(*a): subprocess.run(["python3","/home/user/LockCenter/tools/drag.py",*map(str,a)])
sh("input keyevent KEYCODE_HOME"); time.sleep(1.5); sh("cmd statusbar collapse"); time.sleep(2)
drag(1100,30,1100,2700,1.2,0.3); time.sleep(3)
n=len(sh("su -c 'cat /data/adb/lspd/log/modules_*.log'").splitlines())
import threading
threading.Thread(target=lambda: drag(600,2500,600,250,0.5)).start()
time.sleep(0.4)
for i in range(12):
    subprocess.run(f"adb exec-out screencap -p > /tmp/ff{i}.png",shell=True); time.sleep(0.12)
time.sleep(2)
L=sh("su -c 'cat /data/adb/lspd/log/modules_*.log'").splitlines()[n:]
for l in L:
    if any(k in l for k in ("PT:","LC qs:","NPVC","ZERO")): print(l.split("] ")[-1][:420])
ims=[Image.open(f"/tmp/ff{i}.png") for i in range(12)]; w,h=ims[0].size; s=0.07
c=Image.new("RGB",(int(w*s)*12,int(h*s)))
for i,a in enumerate(ims): c.paste(a.resize((int(w*s),int(h*s))),(int(w*s)*i,0))
c.save("/tmp/ff.png")
