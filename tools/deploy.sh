#!/bin/sh
# build, sign, install, restart SystemUI and verify the new build actually loaded
set -e
cd /home/user/LockCenter
export JAVA_HOME=/home/user/lc-patch/tools
export PATH="$JAVA_HOME/bin:/home/user/lc-patch/android-sdk/platform-tools:$PATH"
export ANDROID_HOME=/home/user/lc-patch/android-sdk
MARK="PT build $(date +%H%M%S)"
python3 - "$MARK" <<'PY'
import sys,re
mark=sys.argv[1]
p='app/src/main/java/com/anton/pixeltweaks/MainHook.java'
s=open(p,encoding='utf-8').read()
s=re.sub(r'XposedBridge\.log\("PT build [^"]*"\);','',s)
s=s.replace('    private static void initSystemUI(XC_LoadPackage.LoadPackageParam lpparam) {',
            '    private static void initSystemUI(XC_LoadPackage.LoadPackageParam lpparam) {\n        XposedBridge.log("'+mark+'");',1)
open(p,'w',encoding='utf-8').write(s)
PY
GRADLE=$(ls -d /home/user/.gradle/wrapper/dists/gradle-9.8.0-bin/*/gradle-9.8.0/bin/gradle | head -1)
"$GRADLE" --no-daemon -q assembleRelease
BT=/home/user/lc-patch/android-sdk/build-tools/37.0.0
"$BT/zipalign" -f 4 app/build/outputs/apk/release/app-release-unsigned.apk build/pt_a.apk
"$BT/apksigner" sign --ks /home/user/.android/debug.keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out build/pt_s.apk build/pt_a.apk
adb install -r build/pt_s.apk >/dev/null
for i in 1 2 3; do
  adb shell "su -c 'killall com.android.systemui'" >/dev/null 2>&1 || true
  sleep 16
  if adb shell "su -c 'grep -c \"$MARK\" /data/adb/lspd/log/modules_*.log 2>/dev/null'" | grep -qv '^0$'; then
    echo "loaded: $MARK"; exit 0
  fi
  echo "retry $i"
done
echo "WARN: marker not seen"; exit 0
