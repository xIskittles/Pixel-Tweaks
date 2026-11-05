import subprocess,time
from PIL import Image
def sh(c): return subprocess.run(["adb","shell",c],capture_output=True,text=True).stdout
def drag(*a): subprocess.run(["python3","/home/user/LockCenter/tools/drag.py",*map(str,a)])
def shot(n): subprocess.run(f"adb exec-out screencap -p > /tmp/{n}.png",shell=True)
sh("input keyevent KEYCODE_HOME"); time.sleep(1.5); sh("cmd statusbar collapse"); time.sleep(2)
drag(300,30,300,1500,1.0,0.3); time.sleep(2.5); shot("w0")             # half
k=1
for i in range(3):
    drag(1100,30,1100,300,0.5,0.2); time.sleep(2.5); shot(f"w{k}"); k+=1  # top-right swipe -> full
    drag(300,30,300,300,0.5,0.2); time.sleep(2.5); shot(f"w{k}"); k+=1    # top-left swipe -> half
ims=[Image.open(f"/tmp/w{i}.png") for i in range(k)]; w,h=ims[0].size; s=0.1
c=Image.new("RGB",(int(w*s)*k,int(h*s)))
for i,a in enumerate(ims): c.paste(a.resize((int(w*s),int(h*s))),(int(w*s)*i,0))
c.save("/tmp/w_sw.png")
