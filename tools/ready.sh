#!/bin/sh
# put the phone into a known testable state
adb shell "svc power stayon usb; settings put system screen_off_timeout 1800000" >/dev/null 2>&1
adb shell "input keyevent KEYCODE_WAKEUP" >/dev/null 2>&1
adb shell "wm dismiss-keyguard" >/dev/null 2>&1
sleep 1
adb shell "input keyevent KEYCODE_HOME" >/dev/null 2>&1
adb shell "cmd statusbar collapse" >/dev/null 2>&1
sleep 1
