#!/usr/bin/env python3
"""Inject a smooth single-finger drag via sendevent (touchscreen /dev/input/event4).
usage: drag.py X0 Y0 X1 Y1 SECONDS [hold_seconds_at_end]"""
import subprocess, sys
dev = "/dev/input/event4"
x0, y0, x1, y1 = map(int, sys.argv[1:5]); dur = float(sys.argv[5])
hold = float(sys.argv[6]) if len(sys.argv) > 6 else 0.0
steps = max(2, int(dur * 50))
def ev(t, c, v): return f"sendevent {dev} {t} {c} {v}"
lines = ["#!/system/bin/sh"]
lines += [ev(3,0x2f,0), ev(3,0x39,77), ev(3,0x35,x0), ev(3,0x36,y0), ev(3,0x3a,60), ev(3,0x30,8),
          ev(1,0x14a,1), ev(0,0,0)]
for i in range(1, steps + 1):
    f = i / steps
    lines += [ev(3,0x35,int(x0+(x1-x0)*f)), ev(3,0x36,int(y0+(y1-y0)*f)), ev(0,0,0), "sleep 0.018"]
if hold: lines.append(f"sleep {hold}")
lines += [ev(3,0x39,-1), ev(1,0x14a,0), ev(0,0,0)]
open("/tmp/drag.sh", "w").write("\n".join(lines) + "\n")
subprocess.run(["adb","push","/tmp/drag.sh","/data/local/tmp/drag.sh"], capture_output=True)
subprocess.run(["adb","shell","su -c 'sh /data/local/tmp/drag.sh'"])
