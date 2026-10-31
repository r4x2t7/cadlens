#!/usr/bin/env python3
"""补齐 LibreDWG 编译期需要的描述表：*.spec、objects.inc/classes.inc、programs/my_stat.h。

这些是 #include 进来的宏展开表，不是编译单元，但缺一个就整个 .c 编不过。
"""
import os, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
DEST = os.path.join(ROOT, 'libredwg')
SHA = '7fdd73f39d74a78ac19e5629756a3c105349d8fd'

FILES = [
    'src/2ndheader.spec', 'src/acds.spec', 'src/appinfo.spec', 'src/auxheader.spec',
    'src/common_entity_data.spec', 'src/common_entity_handle_data.spec',
    'src/common_object_handle_data.spec', 'src/dwg.spec', 'src/dwg2.spec',
    'src/filedeplist.spec', 'src/header.spec', 'src/header_variables.spec',
    'src/header_variables_dxf.spec', 'src/header_variables_r11.spec',
    'src/objfreespace.spec', 'src/r2004_file_header.spec', 'src/revhistory.spec',
    'src/security.spec', 'src/summaryinfo.spec', 'src/template.spec',
    'src/vbaproject.spec',
    'src/objects.inc', 'src/classes.inc',
    'programs/my_stat.h',
]

MIRRORS = [
    'https://fastly.jsdelivr.net/gh/LibreDWG/libredwg@%s/%s',
    'https://ghproxy.net/https://raw.githubusercontent.com/LibreDWG/libredwg/master/%s',
]


def get(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'cadlens-build'})
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read()


ok = fail = 0
for path in FILES:
    out = os.path.join(DEST, path.replace('/', os.sep))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    if os.path.exists(out) and os.path.getsize(out) > 0:
        ok += 1
        continue
    for attempt in range(4):
        done = False
        for tpl in MIRRORS:
            url = tpl % ((SHA, path) if tpl.count('%s') == 2 else path)
            try:
                data = get(url)
                if not data:
                    raise IOError('empty')
                open(out, 'wb').write(data)
                print('ok  %-38s %7d' % (path, len(data)), flush=True)
                ok += 1
                done = True
                break
            except Exception as e:
                last = e
        if done:
            break
        time.sleep(2 + attempt * 2)
    else:
        print('FAIL', path, last, flush=True)
        fail += 1

print('done ok=%d fail=%d' % (ok, fail))
sys.exit(1 if fail else 0)
