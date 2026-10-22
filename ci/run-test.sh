#!/bin/bash
set -x
OUT=ci/out; mkdir -p $OUT
PKG=com.aipro.select
adb wait-for-device
adb shell input keyevent 82
adb install -r app/build/outputs/apk/debug/app-debug.apk || { echo INSTALL_FAIL > $OUT/verdict-install.txt; exit 1; }
echo INSTALL_OK > $OUT/verdict-install.txt
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.CaptureService
adb shell settings put secure accessibility_enabled 1
sleep 3
adb shell am start -a android.intent.action.VIEW -d "http://10.0.2.2:8901/testpage.html"
sleep 14
adb shell screencap -p /sdcard/01-page.png
adb logcat -c
adb shell am start -n $PKG/.MainActivity
sleep 15
adb shell screencap -p /sdcard/02-overlay.png
adb shell uiautomator dump /sdcard/ui2.xml && adb pull /sdcard/ui2.xml $OUT/ui2.xml
adb shell input swipe 60 400 1020 400 700
sleep 5
adb shell screencap -p /sdcard/03-dragselect.png
adb shell uiautomator dump /sdcard/ui3.xml && adb pull /sdcard/ui3.xml $OUT/ui3.xml
(grep -q "Translate" $OUT/ui3.xml && echo DRAG_OPTIONS_BAR=FOUND || echo DRAG_OPTIONS_BAR=MISSING) | tee $OUT/verdict-drag.txt
adb shell pidof $PKG > $OUT/pid-after-drag.txt || echo "PROCESS DEAD after drag" | tee $OUT/verdict-crash.txt
adb shell input keyevent 4
sleep 2
adb shell am start -n $PKG/.MainActivity
sleep 15
adb shell input tap 300 400
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
