#!/bin/sh
# record.sh NAME SECONDS -> /tmp/NAME_frames/f_%03d.png at 30fps + montage
adb shell "rm -f /sdcard/pt.mp4; screenrecord --time-limit $2 --bit-rate 8000000 /sdcard/pt.mp4" &
