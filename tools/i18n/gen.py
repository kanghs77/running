"""번역표(trans1.py, trans2.py)에서 app/.../core/I18n.kt 의 데이터 부분을 다시 만든다.
사용: python3 tools/i18n/extract.py && python3 tools/i18n/gen.py
소스의 tr(...) 영어 문구가 번역표에 모두 있는지도 함께 검사한다."""
import os, re, sys, json
here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, here)
import trans2  # noqa: F401  (trans1.D를 채운다)
from trans1 import D

SKIP = {'km', 'km/h', 'm', 'RunMate', 'OK', '{0} · {1} · {2}km · {3}', '{0} {1}', '{0} · {1}', '{0} ({1})'}
templates = json.load(open(os.path.join(here, 'templates.json')))
missing = [t for t in templates if t not in D and t not in SKIP]
bad = [(k, i) for k, v in D.items() for i, x in enumerate(v)
       if sorted(re.findall(r'\{\d\}', x)) != sorted(re.findall(r'\{\d\}', k)) or x.count('\n') != k.count('\n')]
if missing or bad:
    sys.exit('번역 누락: %r\n자리표시자 불일치: %r' % (missing, bad))

def q(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$').replace('\n', '\\n') + '"'

target = os.path.join(here, '..', '..', 'app', 'src', 'main', 'java', 'app', 'runmate', 'core', 'I18n.kt')
src = open(target).read()
head = src[:src.index('    init {')]
items = sorted(D.items(), key=lambda kv: -len(re.sub(r'\{\d\}', '', kv[0])))   # 고정 글자가 긴 문구부터 맞춰 본다
chunks = [items[i:i + 40] for i in range(0, len(items), 40)]
out = [head + '    init {']
out += ['        part%d()' % i for i in range(len(chunks))]
out += ['    }', '']
for i, ch in enumerate(chunks):
    out.append('    private fun part%d() {' % i)
    for k, v in ch:
        out.append('        add(' + q(k) + ',')
        out.append('            ' + ', '.join(q(x) for x in v) + ')')
    out += ['    }', '']
out.append('}')
open(target, 'w').write('\n'.join(out) + '\n')
print('I18n.kt: %d 문구 x %d 언어' % (len(items), 8))
