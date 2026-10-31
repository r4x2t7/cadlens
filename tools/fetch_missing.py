#!/usr/bin/env python3
"""补齐 GitHub API 限流时没能下载的文件。

走 jsDelivr 的 fastly 节点（按 commit sha 寻址，不消耗 API 配额），
失败再退到 ghproxy.net 的 raw 反代。
"""
import os, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
DEST = os.path.join(ROOT, 'libredwg')
SHA = '7fdd73f39d74a78ac19e5629756a3c105349d8fd'

FILES = [
    ('src/codepages/WINDOWS-936.h', 1971333),
    ('src/codepages/WINDOWS-1252.h', 12735),
    ('src/codepages/GB2312.h', 749200),
    ('src/codepages/CP437.h', 12070),
    ('src/codepages/MACINTOSH.h', 12224),
    ('src/in_dxf.c', 635752),
]

MIRRORS = [
    'https://fastly.jsdelivr.net/gh/LibreDWG/libredwg@%s/%s',
    'https://ghproxy.net/https://raw.githubusercontent.com/LibreDWG/libredwg/master/%s',
]


def get(url, want):
    req = urllib.request.Request(url, headers={'User-Agent': 'cadlens-build'})
    with urllib.request.urlopen(req, timeout=180) as r:
        data = r.read()
    if want and len(data) != want:
        raise IOError('size %d != %d' % (len(data), want))
    return data


ok = fail = 0
for path, size in FILES:
    out = os.path.join(DEST, path.replace('/', os.sep))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    if os.path.exists(out) and os.path.getsize(out) == size:
        print('skip', path)
        continue
    for attempt in range(4):
        for tpl in MIRRORS:
            url = tpl % ((SHA, path) if tpl.count('%s') == 2 else path)
            try:
                data = get(url, size)
                open(out, 'wb').write(data)
                print('ok  %-34s %8d  <- %s' % (path, len(data), url.split('/')[2]))
                ok += 1
                break
            except Exception as e:
                last = e
        else:
            time.sleep(3)
            continue
        break
    else:
        print('FAIL', path, last)
        fail += 1

print('done ok=%d fail=%d' % (ok, fail))
sys.exit(1 if fail else 0)
