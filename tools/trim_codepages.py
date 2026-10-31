#!/usr/bin/env python3
"""把 LibreDWG 的码页表裁剪成只保留真正需要的几种。

src/codepages.c 硬 include 四十多个码页，其中 CP932/BIG5/CP949/JOHAB/WINDOWS-1361
这些单个就近 2MB 文本（编译后每项约 256KB 只读数据）。手机看中文图纸只用得到
WINDOWS-936（GBK）和 WINDOWS-1252（西文）。

做法：以 tools/tree.json 里的完整清单为全集，凡是本地没有的码页，就把它对应的
两个符号（cptbl_x 和 cptbl_alnum_x）在源码里换成 NULL，并删掉那条 include。
NULL 在 LibreDWG 里表示“不做转换”，原样透传，不会崩。

可重复执行：已经处理过的文件再跑一遍不会出错。
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))


def full_list():
    """GitHub 上 codepages 目录的完整文件名清单。"""
    tree = json.load(open(os.path.join(HERE, 'tree.json'), encoding='utf-8'))
    return sorted({os.path.basename(e['path']) for e in tree['tree']
                   if e['type'] == 'blob' and e['path'].startswith('src/codepages/')
                   and e['path'].endswith('.h')})


def patch(src_dir):
    cp_dir = os.path.join(src_dir, 'codepages')
    target = os.path.join(src_dir, 'codepages.c')
    if not os.path.exists(target):
        print('skip (no codepages.c):', target)
        return 0

    have = {f for f in os.listdir(cp_dir)} if os.path.isdir(cp_dir) else set()
    missing = [f for f in full_list() if f not in have]

    s = open(target, encoding='utf-8', errors='replace').read()
    for name in missing:
        base = name[:-2]
        # 两种符号都要处理：char 转换表和“是否字母数字”判定表
        for sym in ('cptbl_alnum_' + base.lower().replace('-', '_').replace('.', '_'),
                    'cptbl_' + base.lower().replace('-', '_').replace('.', '_')):
            # 长的先换，避免 cptbl_ 前缀把 cptbl_alnum_ 截断
            s = re.sub(r'\b' + re.escape(sym) + r'\b', 'NULL', s)
        s = re.sub(r'^#include "codepages/' + re.escape(name) + r'"\s*\n', '', s, flags=re.M)

    open(target, 'w', encoding='utf-8').write(s)
    print('%-58s trimmed %d' % (src_dir, len(missing)))
    return len(missing)


if __name__ == '__main__':
    dirs = sys.argv[1:] or [
        os.path.join(ROOT, 'libredwg', 'src'),
        os.path.join(ROOT, 'cadlens', 'app', 'src', 'main', 'cpp', 'libredwg', 'src'),
    ]
    for d in dirs:
        patch(d)
