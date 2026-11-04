#!/usr/bin/env python3
"""Self-check for the full-QS close gesture. Prints verdict + height series (needs the PT dbg log)."""
import subprocess, re, time, sys
def sh(c): return subprocess.run(["adb","shell",c],capture_output=True,text=True).stdout
def drag(*a): subprocess.run(["python3","/home/user/LockCenter/tools/drag.py",*map(str,a)])
def logs(since): 
    out = sh("su -c 'cat /data/adb/lspd/log/modules_*.log | grep -E \"PT dbg: expandedHeight|PT: own|PT: instant\"'")
    lines = out.splitlines(); return lines[since:]
def nlog(): return len(sh("su -c 'cat /data/adb/lspd/log/modules_*.log | grep -E \"PT dbg: expandedHeight|PT: own|PT: instant\"'").splitlines())
sh("cmd statusbar collapse"); time.sleep(3)
drag(1100,30,1100,2700,1.5,0.3); time.sleep(4)
sh("screencap -p /sdcard/a.png")
mark = nlog()
speed = float(sys.argv[1]) if len(sys.argv)>1 else 3.0
drag(600,2500,600,250,speed); time.sleep(3)
hs=[]; 
for l in logs(mark):
    m=re.search(r"expandedHeight=([-\d.]+)",l)
    if m: hs.append(float(m.group(1)))
ups=[(a,b) for a,b in zip(hs,hs[1:]) if b-a>40 and b!=0]
print("tail:", [int(h) for h in hs[-40:]])
print("samples",len(hs),"start",hs[:1],"end",hs[-1:],"upward jumps>40:",len(ups))
print("own-end:",[l.split('] ')[-1] for l in logs(mark) if 'PT: own' in l])
big=[(a,b) for a,b in zip(hs,hs[1:]) if abs(b-a)>350]
print("big steps (>350px between samples):",big[:6])
