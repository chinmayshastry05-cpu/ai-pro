# usage: geom.py <uidump> <density_dpi> <row> <col> -> prints x_left y_mid x_right x_center  (word box approx, screen px)
import re,sys
x=open(sys.argv[1]).read(); dpi=int(sys.argv[2]); r=int(sys.argv[3]); c=int(sys.argv[4])
d=dpi/160.0
top=None
for m in re.finditer(r'class="android.webkit.WebView"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x):
    top=int(m.group(2)); break
if top is None: sys.exit(1)
cx_css=20+c*130; cy_css=r*100+30+16
words=[["alpha","bravo","charlie"],["delta","echo","foxtrot"],["golf","hotel","india"],["juliet","kilo","lima"],["mike","november","oscar"],["papa","quebec","romeo"]]
wlen=len(words[r][c])*14.0  # ~css px per char at 26px
xl=int((cx_css)*d); xr=int((cx_css+wlen)*d); ym=int(top+cy_css*d)
print(xl,ym,xr,int((xl+xr)/2))
