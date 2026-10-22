#!/usr/bin/env python3
"""受控作业：数据集画像（WBS-3.5.4 沙箱 /opt/ctds/jobs 只读挂载）。

输入：/work/input（平台复制的只读数据文件 + manifest.tsv）
输出：/work/output/dataset-profile.md（结果物元数据由平台登记，内容不出沙箱）

只读标准库；不联网（容器 --network none）；不写输入目录。
"""
import os
import sys


def profile(input_dir):
    lines = ["# 数据集画像", ""]
    total_bytes = 0
    file_count = 0
    for name in sorted(os.listdir(input_dir)):
        path = os.path.join(input_dir, name)
        if not os.path.isfile(path):
            continue
        size = os.path.getsize(path)
        line_count = 0
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as handle:
                line_count = sum(1 for _ in handle)
        except OSError:
            line_count = -1
        total_bytes += size
        file_count += 1
        lines.append("- files: {0}, bytes: {1}, lines: {2}".format(name, size, line_count))
    lines.append("")
    lines.append("file_count: {0}".format(file_count))
    lines.append("total_bytes: {0}".format(total_bytes))
    return "\n".join(lines) + "\n"


def main():
    if len(sys.argv) != 3:
        print("usage: dataset_profile.py <input-dir> <output-file>", file=sys.stderr)
        return 2
    content = profile(sys.argv[1])
    with open(sys.argv[2], "w", encoding="utf-8") as handle:
        handle.write(content)
    return 0


if __name__ == "__main__":
    sys.exit(main())
