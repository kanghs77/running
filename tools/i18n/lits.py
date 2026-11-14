import re,glob,json,sys
def literals(src):
    """yield (start,end) of outermost string literals (handles ${} nesting, skips comments)"""
    i=0;n=len(src);out=[]
    def skip_string(i):
        # src[i]=='"'; returns index after closing quote
        if src.startswith('"""',i):
            j=src.index('"""',i+3); return j+3
        i+=1
        while i<n:
            c=src[i]
            if c=='\\': i+=2; continue
            if c=='"': return i+1
            if c=='$' and i+1<n and src[i+1]=='{':
                i=skip_brace(i+2); continue
            i+=1
        return i
    def skip_brace(i):
        depth=1
        while i<n and depth>0:
            c=src[i]
            if c=='"': i=skip_string(i); continue
            if c=="'" :
                j=i+1
                if j<n and src[j]=='\\': j+=1
                i=j+2; continue
            if c=='{': depth+=1
            elif c=='}': depth-=1
            i+=1
        return i
    while i<n:
        c=src[i]
        if src.startswith('//',i):
            j=src.find('\n',i); i=n if j<0 else j; continue
        if src.startswith('/*',i):
            j=src.find('*/',i); i=n if j<0 else j+2; continue
        if c=="'":
            j=i+1
            if j<n and src[j]=='\\': j+=1
            i=j+2; continue
        if c=='"':
            e=skip_string(i); out.append((i,e)); i=e; continue
        i+=1
    return out
if __name__=='__main__':
    root='/home/user/running/app/src/main/java/app/runmate/'
    res=[]
    for f in sorted(glob.glob(root+'**/*.kt',recursive=True)):
        src=open(f).read()
        for s,e in literals(src):
            lit=src[s:e]
            if re.search('[가-힣]',lit):
                line=src.count('\n',0,s)+1
                res.append((f[len(root):],line,lit))
    for r in res: print(f"{r[0]}:{r[1]}\t{r[2]}")
    print(len(res), len(set(r[2] for r in res)), file=sys.stderr)
