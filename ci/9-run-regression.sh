#!/bin/bash
# Semantic regression checks on a fixed fixture (ci/fixture.html). Every case ends with a Copy
# tap and a paste into the page textarea, so the assertion is on the real copied text.
OUT=ci/out; mkdir -p $OUT; PKG=com.aipro.select; URL="http://10.0.2.2:8901/$(basename $(ls ci/*fixture.html))"
V=$OUT/rg-verdicts.txt; : > $V
want() { [ -z "$RG_CASES" ] || [[ " $RG_CASES " == *" $1 "* ]]; }
verdict() { echo "$1" | tee -a $V; }
bars() { if [ $INVALID = 1 ]; then verdict "$1=INVALID (precondition failed, not an app result)"; elif has Copy; then verdict "$1=PASS"; else verdict "$1=FAIL"; fi; }
ui() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml /tmp/u.xml >/dev/null 2>&1; }
has() { ui; grep -q "text=\"$1\"" /tmp/u.xml; }
alive() { adb shell pidof $PKG >/dev/null 2>&1; }
shot() { adb shell screencap -p /sdcard/$1.png; adb pull /sdcard/$1.png $OUT/ >/dev/null 2>&1; }
mev() { [ $INVALID = 1 ] && return; adb shell input motionevent "$@"; }
magcount() { adb shell dumpsys SurfaceFlinger 2>/dev/null | grep -ci "magnifier surface"; }
dismiss() { for i in 1 2 3; do ui; for t in "Wait" "No thanks" "Use without an account"; do
  B=$(python3 - "$t" <<'PY'
import re,sys
try: x=open('/tmp/u.xml').read()
except Exception: sys.exit(0)
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
    if m.group(1)==sys.argv[1]:
        a,b,c,d=map(int,m.groups()[1:]); print((a+c)//2,(b+d)//2); break
PY
); [ -n "$B" ] && adb shell input tap $B && sleep 1; done; done; }

adb wait-for-device; adb shell input keyevent 82
for s in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $s 0; done
adb install -r app/build/outputs/apk/debug/app-debug.apk || { verdict "RG_INSTALL=FAIL"; exit 1; }
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.CaptureService
adb shell settings put secure accessibility_enabled 1
DPI=$(adb shell wm density | tr -d '\r' | grep -o '[0-9]*$' | tail -1)
SIZE=$(adb shell wm size | tr -d '\r' | awk '{print $3}' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
N=0; INVALID=0; OCR0=0
ocrn() { adb logcat -d -s RGOBS:D 2>/dev/null | grep -c "ocr done"; }
num() { [[ "$1" =~ ^[0-9]+$ ]]; }
page() { INVALID=0; local u=${1:-$URL}; local mark=${2:-alpha}; N=$((N+1)); adb shell input keyevent 4 >/dev/null 2>&1
  for k in 1 2 3 4 5 6; do adb shell am start -a android.intent.action.VIEW -d "$u?c=$N" >/dev/null; sleep 8; dismiss; ui
    if grep -q "$mark" /tmp/u.xml; then break; fi; echo "page retry $k ($u)" >> $OUT/rg-notes.txt; done
  cp /tmp/u.xml /tmp/page.xml
  if ! grep -q "$mark" /tmp/page.xml; then INVALID=1; echo "INVALID: page marker $mark missing" >> $OUT/rg-notes.txt; fi; }
overlay() { [ $INVALID = 1 ] && return; OCR0=$(ocrn); adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 12; dismiss
  for k in $(seq 1 12); do [ $(( $(ocrn) - OCR0 )) -ge 5 ] && return; sleep 4; done; INVALID=1; echo "INVALID: OCR not ready (recognizers done < 5)" >> $OUT/rg-notes.txt; }
# g ROW COL -> sets XL YM XR XC from the page dump taken by page()
g() { read XL YM XR XC < <(python3 $(ls ci/*geom.py) /tmp/page.xml $DPI $1 $2); echo "geom r$1 c$2: $XL $YM $XR $XC dpi=$DPI" >> $OUT/rg-notes.txt; for v in "$XL" "$YM" "$XR" "$XC"; do num "$v" || { INVALID=1; echo "INVALID: geometry r$1 c$2 not numeric" >> $OUT/rg-notes.txt; break; }; done; }
tapw() { g $1 $2; [ $INVALID = 1 ] && return; for k in 1 2 3 4; do adb shell input tap $XC $YM; sleep 5; dismiss; if has Copy; then break; fi; echo "tap retry $k (no Copy yet)" >> $OUT/rg-notes.txt; done; adb shell uiautomator dump /sdcard/dbg.xml >/dev/null 2>&1; adb pull /sdcard/dbg.xml $OUT/dbg-$N.xml >/dev/null 2>&1; }
# paste check: after Copy the overlay finishes and Chrome shows the page again
paste_text() { sleep 2; ui; read TXL TYM TXR TXC < <(python3 - $DPI <<'PY'
import re,sys
x=open('/tmp/u.xml').read(); d=int(sys.argv[1])/160.0
for m in re.finditer(r'class="android.webkit.WebView"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x):
    t=int(m.group(2)); break
print(0,int(t+680*d),0,int(160*d))
PY
); adb shell input tap $TXC $TYM; sleep 1; adb shell input keyevent 279; sleep 1; ui; python3 $(ls ci/*ta.py) /tmp/u.xml; }
tapcopy() { ui; B=$(python3 - <<'PY'
import re
x=open('/tmp/u.xml').read()
for m in re.finditer(r'text="Copy"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
    a,b,c,d=map(int,m.groups()); print((a+c)//2,(b+d)//2); break
PY
); if [ -z "$B" ]; then echo "<<no-copy-button>>"; return; fi; adb shell input tap $B; paste_text; }
check() { # name expected actual
  if [ $INVALID = 1 ]; then verdict "$1=INVALID (precondition failed, not an app result)"; return; fi
  if [ "$3" = "$2" ]; then verdict "$1=PASS (copied '$3')"; else verdict "$1=FAIL (expected '$2' got '$3')"; fi; }

if want C0; then
# C0 control: the same tap the existing passing CI script makes on testpage.html (30%W,19%H)
page "http://10.0.2.2:8901/testpage.html" "quick brown"
overlay; if [ $INVALID = 0 ]; then for k in 1 2 3; do adb shell input tap $((W*30/100)) $((H*19/100)); sleep 5; dismiss; if has Copy; then break; fi; done; fi; shot rg-c0
bars RG_C0_CONTROL_TESTPAGE_TAP
fi
if want C1; then
# C1 tap selects one word
page; overlay; tapw 0 1; shot rg-c1; check RG_C1_TAP_WORD "bravo" "$(tapcopy)"
fi
if want C2; then
# C2 end handle drag extends the range alpha..charlie
page; overlay; tapw 0 0; g 0 0; HX=$XR; HY=$YM; g 0 2; mev DOWN $HX $HY; sleep .3; mev MOVE $(((HX+XC)/2)) $HY; sleep .3; mev MOVE $XC $YM; sleep .5; shot rg-c2-mid; mev UP $XC $YM; sleep 3; shot rg-c2
check RG_C2_END_HANDLE "alpha bravo charlie" "$(tapcopy)"
fi
if want C3; then
# C3 start handle dragged past the end swaps: bravo, start handle to delta -> bravo charlie delta
page; overlay; tapw 0 1; g 0 1; SX=$XL; SY=$YM; g 1 0; mev DOWN $SX $SY; sleep .3; mev MOVE $(((SX+XC)/2)) $(((SY+YM)/2)); sleep .3; mev MOVE $XC $YM; sleep .5; mev UP $XC $YM; sleep 3; shot rg-c3
check RG_C3_HANDLE_SWAP "bravo charlie delta" "$(tapcopy)"
fi
if want C4; then
# C4 failed drop far from any word: bars back and the confirmed selection (bravo) unchanged
page; overlay; tapw 0 1; g 0 1; HX=$XR; HY=$YM; shot rg-c4-before
mev DOWN $HX $HY; sleep .3; mev MOVE $HX $((H*80/100)); sleep .3; mev UP $HX $((H*80/100)); sleep 3; shot rg-c4-after
bars RG_C4_BARS_AFTER_FAILED_DROP
check RG_C4_SELECTION_KEPT "bravo" "$(tapcopy)"
fi
if want C5; then
# C5 cancel mid handle drag: no magnifier, bars back, selection (bravo) unchanged
page; overlay; tapw 0 1; g 0 1; HX=$XR; HY=$YM; g 0 2; CX=$XC; CY=$YM
mev DOWN $HX $HY; sleep .3; mev MOVE $(((HX+CX)/2)) $HY; sleep .3; mev MOVE $CX $CY; sleep .4
M1=$(magcount); mev CANCEL $CX $CY; sleep 2; M2=$(magcount); shot rg-c5-after
verdict "RG_C5_MAGNIFIER during=$M1 after_cancel=$M2 (expect after=0)"
bars RG_C5_BARS_AFTER_CANCEL
check RG_C5_SELECTION_KEPT "bravo" "$(tapcopy)"
fi
if want C6; then
# C6 circle round "hotel" only (rx 50css, ry 30css); neighbours are 130css / 100css away
page; overlay; g 2 1
python3 - $XC $YM $DPI > /tmp/circ.txt <<'PY'
import math,sys
cx,cy,dpi=int(sys.argv[1]),int(sys.argv[2]),int(sys.argv[3]); d=dpi/160.0
for i in range(0,41):
    a=2*math.pi*i/36; print(int(cx+50*d*math.cos(a)), int(cy+30*d*math.sin(a)))
PY
n=0; f=1; while read X Y; do if [ $f = 1 ]; then mev DOWN $X $Y; f=0; else mev MOVE $X $Y; fi; n=$((n+1)); [ $n = 12 ] && shot rg-c6-mid; done < /tmp/circ.txt
mev UP $X $Y; sleep 4; shot rg-c6-after
check RG_C6_CIRCLE_ONLY_HOTEL "hotel" "$(tapcopy)"
fi
if want C7; then
# C7 rectangle drag over delta..foxtrot: clean and jittery drags must select the same text
for MODE in clean jitter; do
  page; overlay; g 1 0; X0=$((XL-10)); Y0=$((YM-40)); g 1 2; X1=$((XR+10)); Y1=$((YM+40))
  mev DOWN $X0 $Y0
  for i in $(seq 1 30); do X=$((X0+(X1-X0)*i/30)); Y=$((Y0+(Y1-Y0)*i/30)); [ $MODE = jitter ] && Y=$((Y+(i%2)*10-5)); mev MOVE $X $Y; done
  shot rg-c7-$MODE-mid; mev UP $X1 $Y1; sleep 4; shot rg-c7-$MODE
  check RG_C7_RECT_$MODE "delta echo foxtrot" "$(tapcopy)"
done
fi
adb logcat -d > $OUT/rg-logcat.txt
F=$(grep -c -E "FATAL EXCEPTION|Fatal signal|SIGSEGV" $OUT/rg-logcat.txt); verdict "RG_FATAL_LOG_COUNT=$F"
adb logcat -d -s RGOBS:D > $OUT/rg-obs.txt; echo "=== RGOBS (test-only counters/coords) ==="; head -80 $OUT/rg-obs.txt
echo "=== RG VERDICTS ==="; cat $V
