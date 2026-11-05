#!/usr/bin/env python3
"""Record the screen while injecting a gesture, then measure animation smoothness frame by frame.
usage: anim.py X0 Y0 X1 Y1 SECONDS [--hold H]"""
import subprocess, sys, os, time, shutil, threading, statistics
args=[a for a in sys.argv[1:] if not a.startswith('--')]
hold=0.0
if '--hold' in sys.argv: hold=float(sys.argv[sys.argv.index('--hold')+1])
dev="/dev/input/event4"
x0,y0,x1,y1=map(int,args[:4]); dur=float(args[4])
steps=max(2,int(dur*50))
def ev(t,c,v): return f"sendevent {dev} {t} {c} {v}"
lines=["#!/system/bin/sh"]+[ev(3,0x2f,0),ev(3,0x39,77),ev(3,0x35,x0),ev(3,0x36,y0),ev(3,0x3a,60),ev(3,0x30,8),ev(1,0x14a,1),ev(0,0,0)]
for i in range(1,steps+1):
    f=i/steps
    lines+=[ev(3,0x35,int(x0+(x1-x0)*f)),ev(3,0x36,int(y0+(y1-y0)*f)),ev(0,0,0),"sleep 0.018"]
if hold: lines.append(f"sleep {hold}")
lines+=[ev(3,0x39,-1),ev(1,0x14a,0),ev(0,0,0)]
open("/tmp/g.sh","w").write("\n".join(lines)+"\n")
subprocess.run(["adb","push","/tmp/g.sh","/data/local/tmp/g.sh"],capture_output=True)
rec=subprocess.Popen(["adb","shell","rm -f /sdcard/r.mp4; screenrecord --time-limit 8 --bit-rate 12000000 --size 720x1560 /sdcard/r.mp4"],stdout=subprocess.DEVNULL)
time.sleep(1.6)
subprocess.run(["adb","shell","su -c 'sh /data/local/tmp/g.sh'"])
rec.wait(timeout=30)
subprocess.run(["adb","pull","/sdcard/r.mp4","/tmp/r.mp4"],capture_output=True)
shutil.rmtree("/tmp/frames",ignore_errors=True); os.makedirs("/tmp/frames")
subprocess.run("ffmpeg -loglevel error -i /tmp/r.mp4 -fps_mode passthrough /tmp/frames/f_%03d.png",shell=True)
frames=sorted(os.listdir("/tmp/frames"))
from PIL import Image, ImageChops
prev=None; diffs=[]
for f in frames:
    im=Image.open("/tmp/frames/"+f).convert("L").resize((180,390))
    if prev is not None:
        d=ImageChops.difference(im,prev); diffs.append(sum(d.getdata())/ (180*390))
    prev=im
med=statistics.median(diffs) if diffs else 0
spikes=[round(d,2) for d in diffs if med>0 and d>med*6 and d>2]
print(f"frames={len(diffs)} median={med:.2f} max={max(diffs):.2f} spikes(>6x median)={len(spikes)}")
print("diff curve:", " ".join(f"{d:.0f}" for d in diffs))
print("VERDICT:", "SMOOTH" if len(spikes)==0 else f"JUMPS:{spikes}")
