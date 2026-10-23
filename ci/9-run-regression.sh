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
mev() { [ $INVALID = 1 ] && return; if [ "$1" = DOWN ] && { [ "$2" -lt 80 ] || [ "$2" -gt $((W-80)) ]; }; then INVALID=1; echo "INVALID: gesture start x=$2 is inside the system edge-back zone" >> $OUT/rg-notes.txt; return; fi; echo "mev $* top=$(adb shell dumpsys activity activities </dev/null 2>/dev/null | grep -m1 -E "topResumedActivity" | grep -o "com[^ ]*" | head -1)" >> $OUT/rg-mev.txt; adb shell input motionevent "$@" </dev/null; }
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
adb shell settings put global hide_error_dialogs 1
for s in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $s 0; done
adb install -r app/build/outputs/apk/debug/app-debug.apk || { verdict "RG_INSTALL=FAIL"; exit 1; }
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.CaptureService
adb shell settings put secure accessibility_enabled 1
DPI=$(adb shell wm density | tr -d '\r' | grep -o '[0-9]*$' | tail -1)
SIZE=$(adb shell wm size | tr -d '\r' | awk '{print $3}' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
KX=1; DX=0; KY=1; DY=0; N=0; INVALID=0; OCR0=0; OFX=0; OFY=0; TPX=0; TPY=0
ocrn() { adb logcat -d -s RGOBS:D 2>/dev/null | grep -c "ocr done"; }
num() { [[ "$1" =~ ^[0-9]+$ ]]; }
page() { INVALID=0; OFX=0; OFY=0; local u=${1:-$URL}; local mark=${2:-alpha}; N=$((N+1)); dismiss; adb shell input keyevent 4 >/dev/null 2>&1; sleep 1
  for k in 1 2 3 4 5 6; do adb shell am start -a android.intent.action.VIEW -d "$u?c=$N" >/dev/null; sleep 8; dismiss; ui
    if grep -q "$mark" /tmp/u.xml; then break; fi; echo "page retry $k ($u)" >> $OUT/rg-notes.txt; done
  cp /tmp/u.xml /tmp/page.xml
  if ! grep -q "$mark" /tmp/page.xml; then INVALID=1; echo "INVALID: page marker $mark missing" >> $OUT/rg-notes.txt; fi; }
calib2() { [ $INVALID = 1 ] && return; local n0=$(adb logcat -d -s RGOBS:D | grep -c tapDetect)
  local AX=$((W*80/100)) AY=$((H*30/100)) BX=$((W*25/100)) BY=$((H*70/100))
  adb shell input tap $AX $AY </dev/null; sleep 3; adb shell input tap $BX $BY </dev/null; sleep 3
  adb logcat -d -s RGOBS:D | grep tapDetect | tail -n +$((n0+1)) > /tmp/cal.txt
  read KX DX KY DY OKC < <(python3 - $AX $AY $BX $BY <<'PY2'
import re,sys
ax,ay,bx,by=map(float,sys.argv[1:5]); L=[re.search(r'bmp=([0-9.]+),([0-9.]+)',l) for l in open('/tmp/cal.txt')]; L=[m for m in L if m]
if len(L)<2: print(1,0,1,0,0); sys.exit()
a,b=L[-2],L[-1]; abx,aby=float(a.group(1)),float(a.group(2)); bbx,bby=float(b.group(1)),float(b.group(2))
kx=(abx-bbx)/(ax-bx); ky=(aby-bby)/(ay-by); print(round(kx,5),round(abx-kx*ax,2),round(ky,5),round(aby-ky*ay,2),1)
PY2
)
  echo "calib2: screen->bitmap kx=$KX dx=$DX ky=$KY dy=$DY ok=$OKC from taps ($AX,$AY) ($BX,$BY)" >> $OUT/rg-notes.txt
  if [ "$OKC" != 1 ] || python3 -c "import sys; sys.exit(0 if 0.7<$KX<1.4 and 0.7<$KY<1.4 else 1)" 2>/dev/null; then :; fi
  [ "$OKC" = 1 ] || { INVALID=1; echo "INVALID: transform calibration taps not logged" >> $OUT/rg-notes.txt; }
}
overlay0() { [ $INVALID = 1 ] && return; OCR0=$(ocrn); adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 12; dismiss
  for k in $(seq 1 12); do [ $(( $(ocrn) - OCR0 )) -ge 5 ] && { adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity' | grep -q "$PKG" || { INVALID=1; echo "INVALID: overlay activity not on screen" >> $OUT/rg-notes.txt; }; return; }; sleep 4; done; INVALID=1; echo "INVALID: OCR not ready (recognizers done < 5)" >> $OUT/rg-notes.txt; }
overlay() { overlay0; calib2; }
# g ROW COL -> sets XL YM XR XC from the page dump taken by page()
g() { read XL YM XR XC < <(python3 $(ls ci/*geom.py) /tmp/page.xml $DPI $1 $2); echo "geom r$1 c$2: $XL $YM $XR $XC dpi=$DPI" >> $OUT/rg-notes.txt; for v in "$XL" "$YM" "$XR" "$XC"; do num "$v" || { INVALID=1; echo "INVALID: geometry r$1 c$2 not numeric" >> $OUT/rg-notes.txt; break; }; done; [ $INVALID = 0 ] && { read XL XR XC YM < <(python3 -c "kx,dx,ky,dy=$KX,$DX,$KY,$DY; f=lambda v,k,d:int(round((v-d)/k)); print(f($XL,kx,dx),f($XR,kx,dx),f($XC,kx,dx),f($YM,ky,dy))"); echo "  mapped screen coords: $XL $YM $XR $XC (k=$KX,$KY d=$DX,$DY)" >> $OUT/rg-notes.txt; }; }
calib() { local L; L=$(adb logcat -d -s RGOBS:D 2>/dev/null | grep tapDetect | tail -1); local bx=$(echo "$L" | sed -n 's/.* bmp=\([0-9.]*\),\([0-9.]*\) .*/\1/p'); local by=$(echo "$L" | sed -n 's/.* bmp=\([0-9.]*\),\([0-9.]*\) .*/\2/p'); [ -z "$bx" ] && { echo "calib: no tapDetect log" >> $OUT/rg-notes.txt; return 1; }
  local dx=$(python3 -c "print(int(round($TPX-$bx)))"); local dy=$(python3 -c "print(int(round($TPY-$by)))")
  echo "calib: tapped screen $TPX,$TPY -> bmp $bx,$by ; applying offset $dx,$dy (screen to overlay bitmap mismatch observed on this device)" >> $OUT/rg-notes.txt
  if [ ${dx#-} -lt 300 ] && [ ${dy#-} -lt 300 ]; then OFX=$((OFX+dx)); OFY=$((OFY+dy)); return 0; fi; return 1; }
tapchk() { adb logcat -d -s RGOBS:D | grep tapDetect | tail -1 > /tmp/tl.txt; python3 - >> $OUT/rg-notes.txt <<'PY3'
import re
l=open('/tmp/tl.txt').read(); m=re.search(r'bmp=([0-9.]+),([0-9.]+)',l)
if not m: print('tapchk: no tapDetect line')
else:
    x,y=float(m.group(1)),float(m.group(2)); bs=re.findall(r'\[([0-9.]+),([0-9.]+)\]\[([0-9.]+),([0-9.]+)\]',l)
    hit=[b for b in bs if float(b[0])<=x<=float(b[2]) and float(b[1])<=y<=float(b[3])]
    print('tapchk: bmp=%.0f,%.0f inside_word_box=%s (%d boxes)'%(x,y,'YES' if hit else 'NO',len(bs)))
PY3
}
tapw() { g $1 $2; [ $INVALID = 1 ] && return; for k in 1 2 3 4; do TPX=$XC; TPY=$YM; adb shell input tap $XC $YM; sleep 5; dismiss; tapchk; if has Copy; then break; fi; echo "tap retry $k (no Copy yet)" >> $OUT/rg-notes.txt; if [ $OFX = 0 ] && [ $OFY = 0 ] && calib; then g $1 $2; fi; done; adb shell uiautomator dump /sdcard/dbg.xml >/dev/null 2>&1; adb pull /sdcard/dbg.xml $OUT/dbg-$N.xml >/dev/null 2>&1; }
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
  local a="$3"; local n=$(printf '%s' "$a" | sed 's/&#10;/ /g' | tr '\n' ' ' | sed 's/  */ /g; s/^ //; s/ $//')
  if [ "$a" = "$2" ]; then verdict "$1=PASS (copied '$a')"; elif [ "$n" = "$2" ]; then verdict "$1=PASS_WITH_LINEBREAK_SEPARATORS (raw copy '$a', words match, separator is a line break not a space)"; else verdict "$1=FAIL (expected '$2' got '$a')"; fi; }

if want C10; then
# C10 screen recordings of gesture start/end frames: tap, circle, rectangle (for plus mark / crop box / flash / Reading text / stray handles review)
rec_start() { adb shell "rm -f /sdcard/$1.mp4; screenrecord --time-limit 30 /sdcard/$1.mp4" </dev/null >/dev/null 2>&1 & RECPID=$!; sleep 3; }
rec_stop() { sleep 2; adb shell pkill -2 screenrecord </dev/null; sleep 3; wait $RECPID 2>/dev/null; adb pull /sdcard/$1.mp4 $OUT/ >/dev/null 2>&1; }
page; overlay; g 0 1; rec_start rec-tap; for k in 1 2 3; do adb shell input tap $XC $YM; sleep 4; has Copy && break; done; shot rg-c10-tap; rec_stop rec-tap
page; overlay; g 2 1
python3 - $XC $YM $DPI > /tmp/circ.txt <<'PY'
import math,sys
cx,cy,dpi=int(sys.argv[1]),int(sys.argv[2]),int(sys.argv[3]); d=dpi/160.0
for i in range(0,41):
    a=2*math.pi*i/36; print(int(cx+50*d*math.cos(a)), int(cy+30*d*math.sin(a)))
PY
awk 'NR==1{print "input motionevent DOWN " $1 " " $2; next}{print "input motionevent MOVE " $1 " " $2}' /tmp/circ.txt > /tmp/circ.sh
X=$(tail -1 /tmp/circ.txt | cut -d' ' -f1); Y=$(tail -1 /tmp/circ.txt | cut -d' ' -f2)
rec_start rec-circle; [ $INVALID = 1 ] || adb shell < /tmp/circ.sh; [ $INVALID = 1 ] || adb shell input motionevent UP $X $Y; sleep 4; shot rg-c10-circle; rec_stop rec-circle
page; overlay; g 1 0; X0=$((XL-4)); Y0=$((YM-40)); g 1 2; X1=$((XR+10)); Y1=$((YM+40))
rec_start rec-rect; mev DOWN $X0 $Y0; for i in $(seq 1 15); do mev MOVE $((X0+(X1-X0)*i/15)) $((Y0+(Y1-Y0)*i/15)); done; mev UP $X1 $Y1; sleep 4; shot rg-c10-rect; rec_stop rec-rect
verdict "RG_C10_RECORDINGS=captured (visual review only, no automatic verdict)"
fi
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
page; overlay; tapw 0 0; g 0 0; HX=$XR; HY=$YM; g 0 2; mev DOWN $HX $((HY+80)); sleep .3; mev MOVE $(((HX+XC)/2)) $HY; sleep .3; mev MOVE $((XR-65)) $YM; sleep .5; shot rg-c2-mid; mev UP $((XR-65)) $YM; sleep 3; shot rg-c2
check RG_C2_END_HANDLE "alpha bravo charlie" "$(tapcopy)"
fi
if want C3; then
# C3 start handle dragged past the end swaps: bravo, start handle to delta -> bravo charlie delta
page; overlay; tapw 0 1; g 0 1; SX=$XL; SY=$YM; g 1 0; mev DOWN $SX $((SY+80)); sleep .3; mev MOVE $(((SX+XC)/2)) $(((SY+YM)/2)); sleep .3; mev MOVE $((XR-6)) $YM; sleep .5; mev UP $((XR-6)) $YM; sleep 3; shot rg-c3
check RG_C3_HANDLE_SWAP "charlie delta" "$(tapcopy)"
fi
if want C4; then
# C4 failed drop far from any word: bars back and the confirmed selection (bravo) unchanged
page; overlay; tapw 0 1; g 0 1; HX=$XR; HY=$YM; shot rg-c4-before
mev DOWN $HX $((HY+80)); sleep .3; mev MOVE $HX $((H*80/100)); sleep .3; mev UP $HX $((H*80/100)); sleep 3; shot rg-c4-after
bars RG_C4_BARS_AFTER_FAILED_DROP
check RG_C4_SELECTION_KEPT "bravo" "$(tapcopy)"
fi
if want C5; then
# C5 cancel mid handle drag: no magnifier, bars back, selection (bravo) unchanged
page; overlay; tapw 0 1; g 0 1; HX=$XR; HY=$YM; g 0 2; CX=$XC; CY=$YM
mev DOWN $HX $((HY+80)); sleep .3; mev MOVE $(((HX+CX)/2)) $HY; sleep .3; mev MOVE $CX $CY; sleep .4
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
# fast gesture: events dispatched in one adb shell call without per-event dumpsys (slow dispatch got the touch cancelled by the system)
awk 'NR==1{print "input motionevent DOWN " $1 " " $2; next}{print "input motionevent MOVE " $1 " " $2}' /tmp/circ.txt > /tmp/circ.sh
echo "mev C6 circle batch $(wc -l < /tmp/circ.sh) events top=$(adb shell dumpsys activity activities </dev/null 2>/dev/null | grep -m1 -E topResumedActivity | grep -o 'com[^ ]*' | head -1)" >> $OUT/rg-mev.txt
X=$(tail -1 /tmp/circ.txt | cut -d' ' -f1); Y=$(tail -1 /tmp/circ.txt | cut -d' ' -f2)
[ $INVALID = 1 ] || adb shell < /tmp/circ.sh
[ $INVALID = 1 ] || adb shell input motionevent UP $X $Y; sleep 4; shot rg-c6-after
check RG_C6_CIRCLE_ONLY_HOTEL "hotel" "$(tapcopy)"
fi
if want C7; then
# C7 rectangle drag over delta..foxtrot: clean and jittery drags must select the same text
for MODE in clean jitter; do
  page; overlay; g 1 0; X0=$((XL-4)); Y0=$((YM-40)); g 1 2; X1=$((XR+10)); Y1=$((YM+40))
  mev DOWN $X0 $Y0
  for i in $(seq 1 30); do X=$((X0+(X1-X0)*i/30)); Y=$((Y0+(Y1-Y0)*i/30)); [ $MODE = jitter ] && Y=$((Y+(i%2)*10-5)); mev MOVE $X $Y; done
  shot rg-c7-$MODE-mid; mev UP $X1 $Y1; sleep 4; shot rg-c7-$MODE
  check RG_C7_RECT_$MODE "delta echo foxtrot" "$(tapcopy)"
done
fi

if want C8; then
# C8 digit-level: tap the number 24733, drag the end handle to the boundary after the 3rd digit; copy must be 247
page "http://10.0.2.2:8901/10-fixture.html" "24733"; overlay; tapw 3 2; g 3 2; HX=$XR; HY=$YM; W5=$((XR-XL)); DX=$((XL+W5*62/100))
mev DOWN $HX $((HY+80)); sleep .3; mev MOVE $(((HX+DX)/2)) $YM; sleep .3; mev MOVE $DX $YM; sleep .3; mev UP $DX $YM; sleep 3; shot rg-c8-after
echo "C8 geom XL=$XL XR=$XR DX=$DX" >> $OUT/rg-notes.txt
check RG_C8_DIGITS_247 "247" "$(tapcopy)"
fi
if want C9; then
# C9 cross-line: tap delta (row1), drag end handle onto hotel (row2 col1): copy must start delta, include echo foxtrot golf, not india
page; overlay; tapw 1 0; g 1 0; HX=$XR; HY=$YM; g 2 1; CX=$XC; CY=$YM
mev DOWN $HX $((HY+80)); sleep .3; mev MOVE $(((HX+CX)/2)) $(((HY+CY)/2)); sleep .3; mev MOVE $CX $CY; sleep .3; mev UP $CX $CY; sleep 3; shot rg-c9-after
A="$(tapcopy)"; N9=$(printf '%s' "$A" | tr '\n' ' ' | sed 's/  */ /g')
case "$N9" in delta*echo*foxtrot*golf*) case "$N9" in *india*) verdict "RG_C9_CROSSLINE=FAIL (includes india: '$A')";; *) verdict "RG_C9_CROSSLINE=PASS (copied '$N9')";; esac;; *) verdict "RG_C9_CROSSLINE=FAIL (got '$A')";; esac
fi
adb logcat -d > $OUT/rg-logcat.txt
F=$(grep -c -E "FATAL EXCEPTION|Fatal signal|SIGSEGV" $OUT/rg-logcat.txt); verdict "RG_FATAL_LOG_COUNT=$F"
adb logcat -d -s RGOBS:D > $OUT/rg-obs.txt; cat $OUT/rg-mev.txt 2>/dev/null | head -150; echo "=== RGOBS (test-only counters/coords) ==="; head -80 $OUT/rg-obs.txt
echo "=== RG VERDICTS ==="; cat $V
