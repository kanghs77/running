import re,glob,sys,json
sys.path.insert(0,__import__('os').path.dirname(__import__('os').path.abspath(__file__)))
from lits import literals
root=__import__('os').path.join(__import__('os').path.dirname(__import__('os').path.abspath(__file__)),'..','..','app','src','main','java','app','runmate','')
def template(lit):
    """kotlin literal source -> runtime template with {n}"""
    s=lit[1:-1]; out=[]; i=0; n=0
    while i<len(s):
        c=s[i]
        if c=='\\':
            d=s[i+1]
            if d=='u': out.append(chr(int(s[i+2:i+6],16))); i+=6; continue
            out.append({'n':'\n','t':'\t','"':'"','$':'$','\\':'\\',"'":"'"}[d]); i+=2; continue
        if c=='$' and i+1<len(s) and s[i+1]=='{':
            depth=1; j=i+2
            while depth>0:
                if s[j]=='"':
                    j+=1
                    while s[j]!='"':
                        if s[j]=='\\': j+=1
                        j+=1
                elif s[j]=='{': depth+=1
                elif s[j]=='}': depth-=1
                j+=1
            out.append('{%d}'%n); n+=1; i=j; continue
        if c=='$' and i+1<len(s) and (s[i+1].isalpha() or s[i+1]=='_'):
            j=i+1
            while j<len(s) and (s[j].isalnum() or s[j]=='_'): j+=1
            out.append('{%d}'%n); n+=1; i=j; continue
        out.append(c); i+=1
    return ''.join(out)
res=[]
for f in sorted(glob.glob(root+'**/*.kt',recursive=True)):
    src=open(f).read(); L=literals(src)
    for k,(s,e) in enumerate(L):
        if src[max(0,s-3):s]=='tr(' and k+1<len(L):
            s2,e2=L[k+1]
            if re.fullmatch(r',\s*',src[e:s2]): res.append((f[len(root):],template(src[s2:e2])))
    if f.endswith('core/Elevation.kt'):
        for m in re.finditer(r'[A-Z_]+\("([^"]*)", "([^"]*)"(?:, "([^"]*)", "([^"]*)")?\)',src):
            res.append(('enum',m.group(2)))
            if m.group(4): res.append(('enum',m.group(4)))
uniq=[]
for _,t in res:
    if t not in uniq: uniq.append(t)
json.dump(uniq,open(__import__('os').path.join(__import__('os').path.dirname(__import__('os').path.abspath(__file__)),'templates.json'),'w'),ensure_ascii=False,indent=0)
print(len(res),len(uniq))
for t in uniq: print(repr(t))
