#!/usr/bin/env python3
"""受控作业：输入清单（WBS-3.5.4 沙箱 /opt/ctds/jobs 只读挂载）。

输入：/work/input（平台复制的只读数据文件 + manifest.tsv）
输出：/work/output/input-inventory.md（条目数 / 字节数；不含内容原文）

manifest.tsv 由平台生成（fileId / 展示名 / 字节数），不含宿主机路径。
"""
import os
import sys


def inventory(input_dir):
    manifest = os.path.join(input_dir, "manifest.tsv")
    entries = 0
    total_bytes = 0
    if os.path.isfile(manifest):
        with open(manifest, "r", encoding="utf-8") as handle:
            for line in handle:
                parts = line.rstrip("\n").split("\t")
                if len(parts) != 3:
                    continue
                entries += 1
                try:
                    total_bytes += int(parts[2])
                except ValueError:
                    continue
    return "# 输入清单\n\nentries: {0}\ntotal_bytes: {1}\n".format(entries, total_bytes)


def main():
    if len(sys.argv) != 3:
        print("usage: input_inventory.py <input-dir> <output-file>", file=sys.stderr)
        return 2
    content = inventory(sys.argv[1])
    with open(sys.argv[2], "w", encoding="utf-8") as handle:
        handle.write(content)
    return 0


if __name__ == "__main__":
    sys.exit(main())
