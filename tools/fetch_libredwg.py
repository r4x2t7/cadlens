#!/usr/bin/env python3
"""从 GitHub 按文件取 LibreDWG 的源码子集（整包 24MB，网络扛不住）。

用 git/blobs API 按 sha 精确取原始字节，避开 contents API 的 1MB 上限。
"""
import json, os, sys, time, base64, urllib.request, urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))  # 仓库外的工作根
DEST = os.path.join(ROOT, 'libredwg')
HERE = os.path.dirname(os.path.abspath(__file__))

TREE = json.load(open(os.path.join(HERE, 'tree.json'), encoding='utf-8'))
BLOBS = {e['path']: e for e in TREE['tree'] if e['type'] == 'blob'}

WANT = []

# 公共头
WANT += ['include/dwg.h', 'include/dwg_api.h']

# src 顶层头文件与配置模板
for p, e in BLOBS.items():
    if p.startswith('src/') and p.count('/') == 1 and (p.endswith('.h') or p.endswith('.in')):
        WANT.append(p)

# 编译单元：DWG 解码 + DXF 输出所需
WANT += [
    'src/bits.c', 'src/common.c', 'src/classes.c', 'src/codepages.c',
    'src/decode.c', 'src/decode2.c', 'src/decode_r11.c', 'src/decode_r2007.c',
    'src/dwg.c', 'src/encode.c', 'src/encode2.c', 'src/free.c',
    'src/geom.c', 'src/hash.c', 'src/logging.c', 'src/objects.c',
    'src/out_dxf.c', 'src/dxfclasses.c', 'src/print.c', 'src/reedsolomon.c',
    'src/in_dxf.c',
]

# codepages：只取中文与西文，亚洲其余码页每个近 2MB，不要
for p in ('src/codepages/WINDOWS-936.h', 'src/codepages/WINDOWS-1252.h',
          'src/codepages/GB2312.h', 'src/codepages/CP437.h',
          'src/codepages/ISO-8859-1.h', 'src/codepages/MACINTOSH.h'):
    if p in BLOBS:
        WANT.append(p)
WANT = [p for p in WANT if p in BLOBS]


def fetch(sha, path):
    url = 'https://api.github.com/repos/LibreDWG/libredwg/git/blobs/' + sha
    req = urllib.request.Request(url, headers={'User-Agent': 'cadlens-build'})
    with urllib.request.urlopen(req, timeout=90) as r:
        j = json.load(r)
    assert j['encoding'] == 'base64', j.get('encoding')
    return base64.b64decode(j['content'])


ok = fail = skip = 0
for i, path in enumerate(WANT, 1):
    out = os.path.join(DEST, path.replace('/', os.sep))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    if os.path.exists(out) and os.path.getsize(out) == BLOBS[path].get('size', -1):
        skip += 1
        continue
    for attempt in range(5):
        try:
            data = fetch(BLOBS[path]['sha'], path)
            if len(data) != BLOBS[path].get('size', len(data)):
                raise IOError('size mismatch %d != %d' % (len(data), BLOBS[path]['size']))
            with open(out, 'wb') as f:
                f.write(data)
            ok += 1
            print('[%2d/%d] %-40s %7d' % (i, len(WANT), path, len(data)), flush=True)
            break
        except Exception as e:
            if attempt == 4:
                print('FAIL', path, e, flush=True)
                fail += 1
            else:
                time.sleep(2 + attempt * 2)

print('done ok=%d skip=%d fail=%d total=%d' % (ok, skip, fail, len(WANT)))
sys.exit(1 if fail else 0)
