#!/bin/sh
export PATH=/home/user/lc-patch/android-sdk/platform-tools:$PATH
adb shell "input keyevent 26" >/dev/null 2>&1
sleep 1
adb shell "input keyevent 224" >/dev/null 2>&1
sleep 3
adb exec-out screencap -p > /tmp/lock_shot.png
