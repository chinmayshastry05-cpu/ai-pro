# usage: geom.py <uidump> <density_dpi> <row> <col> -> prints x_left y_mid x_right x_center  (word box approx, screen px)
import re,sys
x=open(sys.argv[1]).read(); dpi=int(sys.argv[2]); r=int(sys.argv[3]); c=int(sys.argv[4])
d=dpi/160.0
top=None
for m in re.finditer(r'class="android.webkit.WebView"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x):
    top=int(m.group(2)); break
if top is None: sys.exit(1)
import json,glob,os
L=json.load(open(glob.glob(os.path.join(os.path.dirname(os.path.abspath(sys.argv[0])),'*layout.json'))[0]))
cx_css=L['left']+c*L['col']; cy_css=r*L['row']+L['top']+16
words=L['words']
wlen=len(words[r][c])*14.0  # ~css px per char at 26px
xl=int((cx_css)*d); xr=int((cx_css+wlen)*d); ym=int(top+cy_css*d)
print(xl,ym,xr,int((xl+xr)/2))
