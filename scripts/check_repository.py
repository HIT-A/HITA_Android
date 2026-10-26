#!/usr/bin/env python3
"""Check source artifacts, local Markdown links and restricted layer dependencies."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit


ALLOWED_BINARIES = {"gradle/wrapper/gradle-wrapper.jar"}
FORBIDDEN_SUFFIXES = (
    ".db", ".db-wal", ".db-shm", ".sqlite", ".sqlite3", ".sqlite-wal",
    ".sqlite-shm", ".sqlite3-wal", ".sqlite3-shm", ".db-journal", ".apk", ".aab", ".bak", ".log", ".pyc", ".class",
    ".jar", ".exe", ".so", ".dylib", ".zip", ".tar", ".tar.gz", ".tgz",
)
MAGIC = (b"SQLite format 3\x00", b"\x7fELF", b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf",
         b"\xce\xfa\xed\xfe", b"\xfe\xed\xfa\xce", b"\xca\xfe\xba\xbe", b"MZ")
LAYER_ROOTS = (
    "app/src/main/java/cn/limpu/hita/data/",
    "app/src/main/java/cn/limpu/hita/feature/timetableshare/protocol/",
    "app/src/main/java/cn/limpu/hita/feature/timetablecompare/",
    "timetable-core/src/main/",
)
LAYER_ALLOWLIST = "scripts/layer_allowlist.txt"
# Deliberately textual: also checks aliases, static imports, comments and strings.
# '*' is a literal wildcard-import token, never an allowlist pattern.
UI_REFERENCE = re.compile(r"(?<![\w$.])cn\.limpu\.hita\.ui\.[\w$*]+(?:\.[\w$*]+)*")


def repository_files(root):
    result = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=root
    )
    return sorted(set(os.fsdecode(p) for p in result.split(b"\x00") if p))


def is_layer_source(relative):
    return relative.startswith(LAYER_ROOTS) and Path(relative).suffix in (".kt", ".java")


def ui_references(text):
    for number, line in enumerate(text.splitlines(), 1):
        for match in UI_REFERENCE.finditer(line):
            yield number, match.group()


def parse_layer_allowlist(text, label=LAYER_ALLOWLIST):
    entries, errors = set(), []
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        fields = line.split()
        if len(fields) != 2:
            errors.append(f"{label}:{number}: expected source path and exact UI reference")
            continue
        relative, reference = fields
        if (not is_layer_source(relative) or Path(relative).as_posix() != relative
                or ".." in Path(relative).parts or "\\" in relative):
            errors.append(f"{label}:{number}: invalid restricted source path: {relative}")
            continue
        if not UI_REFERENCE.fullmatch(reference):
            errors.append(f"{label}:{number}: invalid UI reference: {reference}")
            continue
        entry = (relative, reference)
        if entry in entries:
            errors.append(f"{label}:{number}: duplicate layer exception: {relative} {reference}")
        entries.add(entry)
    return entries, errors


def baseline_layer_allowlist(root, base_ref):
    """Read Git objects, never execute code or modify the tree from the baseline."""
    def git(*args):
        return subprocess.check_output(["git", *args], cwd=root, stderr=subprocess.PIPE)

    try:
        commit = git("rev-parse", "--verify", "--end-of-options", f"{base_ref}^{{commit}}").decode().strip()
        tree = git("ls-tree", "-r", "-z", commit, "--", LAYER_ALLOWLIST, *LAYER_ROOTS)
        blobs = {}
        for record in tree.split(b"\0"):
            if record:
                metadata, name = record.split(b"\t", 1)
                mode, kind, oid = metadata.decode().split()
                blobs[os.fsdecode(name)] = (mode, kind, oid)
        if LAYER_ALLOWLIST in blobs:
            mode, kind, oid = blobs[LAYER_ALLOWLIST]
            if kind != "blob" or mode not in ("100644", "100755"):
                return set(), [f"{base_ref}:{LAYER_ALLOWLIST}: baseline allowlist must be a regular file"]
            return parse_layer_allowlist(git("cat-file", "blob", oid).decode("utf-8"),
                                         f"{base_ref}:{LAYER_ALLOWLIST}")
        # First introduction: only references already present in the base may be exempted.
        entries = set()
        for relative, (mode, kind, oid) in blobs.items():
            if is_layer_source(relative) and kind == "blob" and mode in ("100644", "100755"):
                text = git("cat-file", "blob", oid).decode("utf-8")
                entries.update((relative, reference) for _, reference in ui_references(text))
        return entries, []
    except (subprocess.CalledProcessError, OSError, UnicodeError, ValueError):
        return set(), [f"cannot read layer baseline {base_ref!r}; fetch a valid commit before checking"]


def check_layer_dependencies(root, sources, allowlist_text, base_ref):
    allowed, errors = parse_layer_allowlist(allowlist_text)
    observed = set()
    for relative, text in sources.items():
        for line, reference in ui_references(text):
            entry = (relative, reference)
            observed.add(entry)
            if entry not in allowed:
                errors.append(f"{relative}:{line}: forbidden UI dependency: {reference}")
    for relative, reference in sorted(allowed - observed):
        errors.append(f"{LAYER_ALLOWLIST}: stale layer exception: {relative} {reference}")
    if base_ref is not None:
        baseline, baseline_errors = baseline_layer_allowlist(root, base_ref)
        errors.extend(baseline_errors)
        if not baseline_errors:
            for relative, reference in sorted(allowed - baseline):
                errors.append(f"{LAYER_ALLOWLIST}: layer exception added since {base_ref}: {relative} {reference}")
    return errors


def markdown_targets(text):
    """Inline/image and reference destinations outside fenced code blocks."""
    fence = None
    for number, line in enumerate(text.splitlines(), 1):
        marker = re.match(r"^\s{0,3}(`{3,}|~{3,})", line)
        if marker:
            run = marker.group(1)
            if fence is None:
                fence = run
            elif run[0] == fence[0] and len(run) >= len(fence):
                fence = None
            continue
        if fence:
            continue
        line = re.sub(r"`[^`]*`", "", line)
        for match in re.finditer(r"!?\[[^\]\n]*\]\(\s*(<[^>]+>|[^\s)]+)", line):
            yield number, match.group(1).strip("<>")
        reference = re.match(r"^\s{0,3}\[[^]]+\]:\s*(<[^>]+>|\S+)", line)
        if reference:
            yield number, reference.group(1).strip("<>")


def check(root, base_ref=None):
    root = root.resolve()
    paths = repository_files(root)
    existing = {p for p in paths if (root / p).exists()}
    errors = []
    layer_sources, allowlist_text = {}, ""
    for relative in paths:
        path = root / relative
        if not path.exists():
            if path.is_symlink():
                errors.append(f"{relative}: broken symlink")
            continue  # A tracked file removed in this working tree.
        if not path.resolve().is_relative_to(root):
            errors.append(f"{relative}: symlink leaves repository")
            continue
        if not path.is_file():
            continue
        if relative not in ALLOWED_BINARIES:
            if relative.lower().endswith(FORBIDDEN_SUFFIXES):
                errors.append(f"{relative}: runtime/build/archive artifact must stay outside Git")
            with path.open("rb") as stream:
                if stream.read(32).startswith(MAGIC):
                    errors.append(f"{relative}: database or executable content must stay outside Git")
        if path.stat().st_size > 8 * 1024 * 1024:
            errors.append(f"{relative}: exceeds the 8 MiB source-file limit")
        if is_layer_source(relative) or relative == LAYER_ALLOWLIST:
            try:
                text = path.read_text(encoding="utf-8")
            except UnicodeError:
                errors.append(f"{relative}: layer check requires UTF-8 source text")
            else:
                if relative == LAYER_ALLOWLIST:
                    allowlist_text = text
                else:
                    layer_sources[relative] = text
        if path.suffix.lower() != ".md":
            continue
        for line, destination in markdown_targets(path.read_text(encoding="utf-8")):
            url = urlsplit(destination)
            if url.scheme or url.netloc or not url.path:
                continue
            target = unquote(url.path)
            candidate = (root / target.lstrip("/")) if target.startswith("/") else path.parent / target
            candidate = candidate.resolve()
            if not candidate.is_relative_to(root):
                errors.append(f"{relative}:{line}: local link leaves repository: {destination}")
                continue
            name = candidate.relative_to(root).as_posix()
            tracked = name in existing or any(p.startswith(name.rstrip("/") + "/") for p in existing)
            if not candidate.exists() or not tracked:
                errors.append(f"{relative}:{line}: missing versioned link target: {destination}")
    errors.extend(check_layer_dependencies(root, layer_sources, allowlist_text, base_ref))
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--base-ref", help="Git commit/ref whose layer exceptions may only be removed")
    args = parser.parse_args()
    errors = check(args.root, args.base_ref)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("Repository checks passed: source artifacts, local Markdown file links and layer dependencies")
    return 0


if __name__ == "__main__":
    sys.exit(main())
