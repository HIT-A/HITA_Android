#!/usr/bin/env python3
"""聚合 Gradle JUnit XML 为 Markdown 测试报告。

只读取实际存在的 XML，不推断未执行的测试；报告中的通过数直接来自 XML 属性。
"""
import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_ROOTS = {
    "app-unit": "app/build/test-results/testDebugUnitTest",
    "app-instrumented": "app/build/outputs/androidTest-results/connected",
    "core": "timetable-core/build/test-results/test",
    "component-unit": "component/build/test-results/testDebugUnitTest",
    "component-instrumented": "component/build/outputs/androidTest-results/connected",
    "hitauser-unit": "hitauser/build/test-results/testDebugUnitTest",
    "hitauser-instrumented": "hitauser/build/outputs/androidTest-results/connected",
    "style-instrumented": "style/build/outputs/androidTest-results/connected",
}


def collect(root, rel):
    files = glob.glob(os.path.join(root, rel, "**", "TEST-*.xml"), recursive=True)
    totals = {"files": len(files), "tests": 0, "failures": 0, "errors": 0, "skipped": 0, "seconds": 0.0}
    cases = []
    for path in sorted(files):
        try:
            tree = ET.parse(path).getroot()
        except ET.ParseError as error:
            print(f"警告: 无法解析 {path}: {error}", file=sys.stderr)
            continue
        totals["tests"] += int(tree.get("tests", 0))
        totals["failures"] += int(tree.get("failures", 0))
        totals["errors"] += int(tree.get("errors", 0))
        totals["skipped"] += int(tree.get("skipped", 0))
        totals["seconds"] += float(tree.get("time", 0) or 0)
        for case in tree.findall("testcase"):
            cases.append({
                "class": case.get("classname", ""),
                "name": case.get("name", ""),
                "time": float(case.get("time", 0) or 0),
                "failure": case.find("failure") is not None,
                "error": case.find("error") is not None,
                "skipped": case.find("skipped") is not None,
            })
    return totals, cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
                        .replace(os.sep, "/"))
    parser.add_argument("--out", help="输出 Markdown 路径；缺省打印到标准输出")
    args = parser.parse_args()
    root = args.root

    sections = []
    total = {"files": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0, "seconds": 0.0}
    all_cases = []
    for name, rel in DEFAULT_ROOTS.items():
        totals, cases = collect(root, rel)
        if not totals["files"]:
            sections.append((name, totals, []))
            continue
        sections.append((name, totals, cases))
        for key in total:
            total[key] += totals[key]
        all_cases.extend(cases)

    lines = ["# 测试执行报告", "", "> 数据来源：实际存在的 Gradle JUnit XML；未执行的任务不出现在统计中（不做推断）。", ""]
    lines += ["| 测试集 | XML 数 | 用例 | 通过 | 失败 | 错误 | 跳过 | 耗时(s) |",
              "|---|---|---|---|---|---|---|---|"]
    for name, totals, _ in sections:
        executed = totals["tests"]
        passed = executed - totals["failures"] - totals["errors"] - totals["skipped"]
        if not totals["files"]:
            lines.append(f"| {name} | 0 | — | — | — | — | — | — |")
            continue
        lines.append(
            f"| {name} | {totals['files']} | {executed} | {passed} | {totals['failures']} | "
            f"{totals['errors']} | {totals['skipped']} | {totals['seconds']:.1f} |"
        )
    executed_total = total["tests"]
    passed_total = executed_total - total["failures"] - total["errors"] - total["skipped"]
    lines.append(
        f"| **合计** | {total['files']} | {executed_total} | {passed_total} | "
        f"{total['failures']} | {total['errors']} | {total['skipped']} | {total['seconds']:.1f} |"
    )

    duplicates = {}
    for case in all_cases:
        key = (case["class"], case["name"])
        duplicates[key] = duplicates.get(key, 0) + 1
    repeated = {key: count for key, count in duplicates.items() if count > 1}
    lines += ["", f"## 重复测试身份：{len(repeated)}", ""]
    for (cls, name), count in sorted(repeated.items())[:20]:
        lines.append(f"- `{cls}.{name}` × {count}")

    slowest = sorted(all_cases, key=lambda c: -c["time"])[:20]
    lines += ["", "## 最慢用例（前 20）", "", "| 耗时(s) | 用例 |", "|---|---|"]
    for case in slowest:
        lines.append(f"| {case['time']:.2f} | `{case['class']}.{case['name']}` |")

    failed = [c for c in all_cases if c["failure"] or c["error"]]
    lines += ["", f"## 失败/错误用例：{len(failed)}", ""]
    for case in failed:
        lines.append(f"- `{case['class']}.{case['name']}`")

    skipped = [c for c in all_cases if c["skipped"]]
    lines += ["", f"## 跳过用例：{len(skipped)}", ""]
    for case in skipped:
        lines.append(f"- `{case['class']}.{case['name']}`")

    text = "\n".join(lines) + "\n"
    if args.out:
        with open(args.out, "w", encoding="utf-8") as stream:
            stream.write(text)
        print(f"写入 {args.out}；合计 {executed_total} 项，失败 {total['failures']}，错误 {total['errors']}")
    else:
        sys.stdout.write(text)


if __name__ == "__main__":
    main()
