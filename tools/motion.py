#!/usr/bin/env python3
"""Measure vertical motion of the shade per recorded frame (px/frame) and flag non-physical jumps."""
import os, sys, statistics
import numpy as np
from PIL import Image
d="/tmp/frames"; fs=sorted(os.listdir(d))
frames=[np.asarray(Image.open(os.path.join(d,f)).convert("L").resize((60,130)),dtype=np.float32) for f in fs]
R=70  # max search offset in downscaled px
def shift(a,b):
    best=(1e18,0)
    H=a.shape[0]
    for o in range(-R,R+1):
        if o>=0: x=a[o:H]; y=b[0:H-o]
        else:    x=a[0:H+o]; y=b[-o:H]
        if x.shape[0]<20: continue
        v=float(np.abs(x-y).mean())
        if v<best[0]: best=(v,o)
    return best[1], best[0]
prev=None; out=[]
for f in frames:
    if prev is not None:
        o,_=shift(prev,f); out.append(o)
    prev=f
# scale: 130 rows represent ~3120 px -> 24 px per unit
px=[o*24 for o in out]
vel=[b-a for a,b in zip(px,px[1:])]
med=statistics.median([abs(v) for v in vel]) or 1
jumps=[ (i,v) for i,v in enumerate(vel) if abs(v) > max(120, med*8) ]
print("motion px/frame:", " ".join(str(v) for v in px[:70]))
print(f"frames={len(px)} median|v|={med:.0f} max|v|={max(abs(v) for v in vel) if vel else 0}")
print("JUMPS:", [(i,v) for i,v in jumps][:12])
print("VERDICT:", "SMOOTH" if len(jumps)<=2 else f"{len(jumps)} discontinuities")
