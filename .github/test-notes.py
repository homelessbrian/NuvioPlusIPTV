#!/usr/bin/env python3
"""TEST_NOTES.md for Build Test App.

  test-notes.py build TEST_NOTES.md OUT    writes the notes for a test build to OUT (headings with
                                           nothing under them left out); exits 1 if there are none
  test-notes.py clear TEST_NOTES.md USED   removes the lines that went into a test build (USED),
                                           keeping the headings and anything added since
"""
import sys


def body(lines):
    """The file without its title and the comment explaining it."""
    out, in_comment = [], False
    for i, line in enumerate(lines):
        s = line.strip()
        if in_comment:
            in_comment = "-->" not in s
            continue
        if s.startswith("<!--"):
            in_comment = "-->" not in s
            continue
        if i == 0 and s.startswith("# "):
            continue
        out.append(line)
    return out


def level(line):
    s = line.lstrip()
    if not s.startswith("#"):
        return 0
    n = len(s) - len(s.lstrip("#"))
    return n if s[n:n + 1] == " " else 0


def is_item(line):
    return line.strip().startswith(("- ", "* "))


def build(src, dst):
    lines = body(open(src, encoding="utf-8").read().splitlines())
    keep = []
    for i, line in enumerate(lines):
        lv = level(line)
        if lv:
            # A heading stays only if something is listed before the next heading at its level.
            has = False
            for nxt in lines[i + 1:]:
                nl = level(nxt)
                if nl and nl <= lv:
                    break
                if is_item(nxt):
                    has = True
                    break
            if not has:
                continue
        elif not line.strip():
            if not keep or not keep[-1].strip():
                continue
        keep.append(line)
    while keep and not keep[-1].strip():
        keep.pop()
    if not any(is_item(l) for l in keep):
        return 1
    open(dst, "w", encoding="utf-8").write("\n".join(keep) + "\n")
    return 0


def clear(src, used):
    published = {l.strip() for l in open(used, encoding="utf-8").read().splitlines() if is_item(l)}
    lines = open(src, encoding="utf-8").read().splitlines()
    kept = [l for l in lines if not (is_item(l) and l.strip() in published)]
    open(src, "w", encoding="utf-8").write("\n".join(kept) + "\n")
    return 0


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "build" and len(sys.argv) == 4:
        sys.exit(build(sys.argv[2], sys.argv[3]))
    if mode == "clear" and len(sys.argv) == 4:
        sys.exit(clear(sys.argv[2], sys.argv[3]))
    print(__doc__)
    sys.exit(2)
