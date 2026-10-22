#!/bin/bash
set -x
OUT=ci/out; mkdir -p $OUT
PKG=com.aipro.select

tap_text() {  # tap center of node whose text equals $1; returns 0 if tapped
  adb shell uiautomator dump /sdcard/uix.xml >/dev/null 2>&1
  adb pull /sdcard/uix.xml /tmp/uix.xml >/dev/null 2>&1
  B=$(python3 - "$1" <<'PY'
import re,sys
try: x=open('/tmp/uix.xml').read()
except Exception: sys.exit(0)
t=sys.argv[1]
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
    if m.group(1)==t:
        x1,y1,x2,y2=map(int,m.groups()[1:])
        print(str((x1+x2)//2)+' '+str((y1+y2)//2)); break
PY
)
  if [ -n "$B" ]; then adb shell input tap $B; sleep 2; return 0; fi
  return 1
}

page_has_text() {
  adb shell uiautomator dump /sdcard/uip.xml >/dev/null 2>&1
  adb pull /sdcard/uip.xml /tmp/uip.xml >/dev/null 2>&1
  grep -q "quick brown fox" /tmp/uip.xml
}

adb wait-for-device
adb shell input keyevent 82
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
sleep 5

adb install -r app/build/outputs/apk/debug/app-debug.apk || { echo INSTALL_FAIL > $OUT/verdict-install.txt; exit 1; }
echo INSTALL_OK > $OUT/verdict-install.txt
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.CaptureService
adb shell settings put secure accessibility_enabled 1
sleep 3

# dynamic screen geometry
SIZE=$(adb shell wm size | tr -d '\r' | awk '{print $3}')
W=${SIZE%x*}; H=${SIZE#*x}
echo "SCREEN=$SIZE" | tee $OUT/screen.txt
SWY=$((H * 19 / 100)); X1=$((W * 8 / 100)); X2=$((W * 92 / 100)); TX=$((W * 30 / 100)); SY1=$((H * 17 / 100)); SY2=$((H * 27 / 100))

adb shell am start -a android.intent.action.VIEW -d "http://10.0.2.2:8901/testpage.html"
for i in 1 2 3 4 5 6; do
  tap_text "Wait" && echo "dismissed ANR dialog"
  tap_text "Use without an account" && echo "skipped chrome first-run"
  tap_text "No thanks" && echo "dismissed chrome notification dialog"
  if page_has_text; then echo PAGE_LOADED | tee $OUT/verdict-page.txt; break; fi
  sleep 5
done
grep -q PAGE_LOADED $OUT/verdict-page.txt 2>/dev/null || echo PAGE_NOT_LOADED | tee $OUT/verdict-page.txt
adb shell screencap -p /sdcard/01-page.png

adb logcat -c
adb shell am start -n $PKG/.MainActivity
sleep 12
for i in 1 2 3; do tap_text "Wait" && echo "dismissed ANR dialog"; tap_text "No thanks" && echo "dismissed chrome notification dialog"; done
adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" > $OUT/focus.txt
adb shell screencap -p /sdcard/02-overlay.png

adb shell input swipe $X1 $SY1 $X2 $SY2 700   # diagonal box drag: horizontal-only swipe yields a zero-height rect the app discards
sleep 5
tap_text "Wait" && echo "dismissed ANR dialog"
adb shell screencap -p /sdcard/03-dragselect.png
adb shell uiautomator dump /sdcard/ui3.xml && adb pull /sdcard/ui3.xml $OUT/ui3.xml
(grep -q "Translate" $OUT/ui3.xml && echo DRAG_OPTIONS_BAR=FOUND || echo DRAG_OPTIONS_BAR=MISSING) | tee $OUT/verdict-drag.txt
adb shell pidof $PKG > $OUT/pid-after-drag.txt || echo "PROCESS DEAD after drag" | tee $OUT/verdict-crash.txt

adb shell input keyevent 4
sleep 2
adb shell am start -n $PKG/.MainActivity
sleep 12
for i in 1 2 3; do tap_text "Wait" && echo "dismissed ANR dialog"; tap_text "No thanks" && echo "dismissed chrome notification dialog"; done
adb shell input tap $TX $SWY
sleep 4
adb shell screencap -p /sdcard/04-tapword.png
adb shell uiautomator dump /sdcard/ui4.xml && adb pull /sdcard/ui4.xml $OUT/ui4.xml
(grep -q "Translate" $OUT/ui4.xml && echo TAP_OPTIONS_BAR=FOUND || echo TAP_OPTIONS_BAR=MISSING) | tee $OUT/verdict-tap.txt
adb shell pidof $PKG > $OUT/pid-after-tap.txt || echo "PROCESS DEAD after tap" >> $OUT/verdict-crash.txt

sleep 10
adb shell screencap -p /sdcard/05-after.png
adb shell pidof $PKG > $OUT/pid-final.txt || echo "PROCESS DEAD at end" >> $OUT/verdict-crash.txt
adb logcat -d > $OUT/logcat.txt
grep -c -E "FATAL EXCEPTION|Fatal signal|SIGSEGV" $OUT/logcat.txt > $OUT/logcat-fatal-count.txt || true
for f in 01-page 02-overlay 03-dragselect 04-tapword 05-after; do adb pull /sdcard/$f.png $OUT/ 2>/dev/null; done
echo "=== VERDICTS ==="; cat $OUT/verdict-*.txt $OUT/logcat-fatal-count.txt 2>/dev/null
