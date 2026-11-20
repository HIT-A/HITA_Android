#!/usr/bin/env python3
"""校验审计报告里的 `路径:行号` 引用是否仍然成立。

用法：
    python docs/audit/verify_citations.py

规则：
- 只校验形如 `name.ext:12` 或 `name.ext:12-34` 的引用。
- 带目录的引用按「路径后缀」匹配；只写文件名的按 basename 匹配，必须唯一。
- 含 `...` 的省略式引用跳过（无法唯一解析）。
- 行号必须落在文件实际行数内。
- 退出码 0 表示全部通过，1 表示存在失效引用。
"""

from __future__ import annotations

import os
import re
import sys

REPOS = [
    r"D:/project/HITA/HITA_Android",
    r"D:/project/HITA/HITA-Aura",
]
REPORT = os.path.join(os.path.dirname(__file__), "2026-11-14-ios-android-feature-diff.md")
OPTIONS = os.path.join(os.path.dirname(__file__), "2026-11-14-migration-options.md")

SKIP_DIRS = {"build", ".git", ".gradle", "node_modules", "DerivedData", ".build", "Pods", ".kotlin"}
EXTS = (".kt", ".kts", ".java", ".swift", ".mjs", ".xml", ".gradle", ".toml", ".md", ".xcconfig")

CITATION = re.compile(
    r"`([A-Za-z0-9_./+\-]+\.(?:kt|kts|java|swift|mjs|xml|gradle|toml|md|xcconfig)):(\d+)(?:-(\d+))?`"
)


def index() -> dict[str, list[str]]:
    """basename -> absolute paths"""
    names: dict[str, list[str]] = {}
    for root_dir in REPOS:
        if not os.path.isdir(root_dir):
            continue
        for root, dirs, files in os.walk(root_dir):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
            for name in files:
                if name.endswith(EXTS):
                    names.setdefault(name, []).append(os.path.join(root, name))
    return names


def line_count(path: str) -> int:
    try:
        with open(path, "rb") as handle:
            return sum(1 for _ in handle)
    except OSError:
        return -1


def resolve(citation: str, names: dict[str, list[str]]) -> tuple[str | None, str | None]:
    """返回 (路径, 错误)。路径为 None 时错误非空；两者皆 None 表示跳过。"""
    if "..." in citation:
        return None, None
    base = os.path.basename(citation)
    candidates = names.get(base, [])
    if not candidates:
        return None, f"找不到文件 {citation}"
    if "/" in citation:
        suffix = "/" + citation.lstrip("./")
        matched = [p for p in candidates if p.replace("\\", "/").endswith(suffix)]
        if len(matched) == 1:
            return matched[0], None
        if not matched:
            return None, f"路径后缀不匹配 {citation}（{len(candidates)} 个同名文件）"
        return None, f"{citation} 匹配到 {len(matched)} 个路径"
    if len(candidates) == 1:
        return candidates[0], None
    return None, f"{citation} 有 {len(candidates)} 个同名文件，需写全路径"


def main() -> int:
    names = index()
    if not names:
        print("没有找到任何目标仓库，检查 REPOS 配置")
        return 1

    failures: list[str] = []
    checked = 0
    for report in (REPORT, OPTIONS):
        if not os.path.exists(report):
            failures.append(f"缺少报告文件: {report}")
            continue
        with open(report, encoding="utf-8") as handle:
            for lineno, text in enumerate(handle, 1):
                for match in CITATION.finditer(text):
                    citation, start, end = match.group(1), int(match.group(2)), match.group(3)
                    end = int(end) if end else start
                    path, error = resolve(citation, names)
                    if error:
                        failures.append(f"{os.path.basename(report)}:{lineno} {error}")
                        continue
                    if path is None:
                        continue
                    total = line_count(path)
                    checked += 1
                    if start < 1 or end > total or start > end:
                        failures.append(
                            f"{os.path.basename(report)}:{lineno} {citation}:{start}-{end} 超出文件行数 {total}"
                        )

    if failures:
        print(f"引用检查失败 {len(failures)} 项（已校验 {checked} 条）：")
        for item in failures:
            print("  -", item)
        return 1

    print(f"引用检查通过：{checked} 条 `路径:行号` 全部存在且行号有效")
    return 0


if __name__ == "__main__":
    sys.exit(main())
