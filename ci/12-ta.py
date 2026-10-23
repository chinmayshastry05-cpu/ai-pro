# prints textarea text from a uidump
import re,sys
x=open(sys.argv[1]).read()
m=re.search(r'class="android.widget.EditText"[^>]*text="([^"]*)"',x) or re.search(r'text="([^"]*)"[^>]*class="android.widget.EditText"',x)
print(m.group(1) if m else "<<no-edittext>>")
