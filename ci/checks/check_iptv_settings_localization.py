#!/usr/bin/env python3
"""Check IPTV resource definitions, Spanish coverage and Kotlin/Java references.

Run from any directory: python ci/checks/check_iptv_settings_localization.py
Only the Python standard library is required; no Android build or app data is read.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


FORMAT = re.compile(
    r"%(?:(?P<position>[1-9]\d*)\$)?(?P<flags>[-#+ 0,(<]*)"
    r"(?P<width>\d+)?(?P<precision>\.\d+)?"
    r"(?:(?P<date>[tT])(?P<date_conversion>[HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc])|"
    r"(?P<conversion>[bBhHsScCdoxXeEfgGaA%n]))"
)
SOURCE_REFERENCE = re.compile(r"\bR\.(string|plurals|array)\.(iptv_[A-Za-z0-9_]+)\b")
RESOURCE_TYPES = {"string": "string", "plurals": "plurals", "string-array": "array", "array": "array"}


@dataclass
class Resource:
    kind: str
    name: str
    path: Path
    element: ET.Element

    @property
    def key(self) -> tuple[str, str]:
        return self.kind, self.name

    @property
    def translatable(self) -> bool:
        return self.element.get("translatable", "true") != "false"


def android_text(element: ET.Element) -> str:
    """Decode XML text and Android's quoting/escapes before examining format tokens."""
    text = "".join(element.itertext())
    result = []
    index = 0
    escapes = {"n": "\n", "t": "\t", "r": "\r", "'": "'", '"': '"', "\\": "\\", "@": "@", "?": "?"}
    while index < len(text):
        char = text[index]
        if char == '"':
            # Unescaped Android quotes preserve whitespace; they are not display text.
            index += 1
            continue
        if char != "\\":
            result.append(char)
            index += 1
            continue
        if index + 1 >= len(text):
            raise ValueError("unfinished Android escape")
        escaped = text[index + 1]
        if escaped == "u":
            digits = text[index + 2:index + 6]
            if len(digits) != 4 or not re.fullmatch(r"[0-9a-fA-F]{4}", digits):
                raise ValueError("invalid Android Unicode escape")
            result.append(chr(int(digits, 16)))
            index += 6
        elif escaped in escapes:
            result.append(escapes[escaped])
            index += 2
        else:
            raise ValueError(f"unsupported Android escape \\{escaped}")
    return "".join(result)


def format_signature(element: ET.Element) -> Counter:
    """Compare consuming argument positions/types, allowing translated token order."""
    text = android_text(element)
    tokens = []
    invalid = []
    index = 0
    implicit = 0
    implicit_tokens = 0
    previous = None
    while index < len(text):
        if text[index] != "%":
            index += 1
            continue
        match = FORMAT.match(text, index)
        if match is None:
            invalid.append(index)
            index += 1
            continue
        index = match.end()
        conversion = match["conversion"] or match["date_conversion"]
        if conversion in ("%", "n") and match["date"] is None:
            continue
        flags = match["flags"]
        position = match["position"]
        if "<" in flags:
            if previous is None or position is not None:
                raise ValueError("relative format argument has no valid preceding argument")
            argument = previous
        elif position is not None:
            argument = int(position)
        else:
            implicit += 1
            implicit_tokens += 1
            argument = implicit
        previous = argument
        tokens.append((argument, flags.replace("<", ""), match["width"], match["precision"], match["date"], conversion))
    # A plain label such as "100%" is valid; a formatted label must escape literal %.
    if tokens and invalid:
        raise ValueError("unescaped or malformed % in a formatted string")
    if len(tokens) > 1 and implicit_tokens:
        raise ValueError("multiple format arguments must use explicit positional indexes")
    return Counter(tokens)


def variants(resource: Resource) -> dict[str, ET.Element]:
    if resource.kind == "string":
        return {"text": resource.element}
    items = resource.element.findall("item")
    if resource.kind == "plurals":
        names = [item.get("quantity") for item in items]
        if not set(names) <= {"zero", "one", "two", "few", "many", "other"} or len(set(names)) != len(names) or "other" not in names:
            raise ValueError("plural quantities must be unique and include other")
        return dict(zip(names, items))
    return {str(index): item for index, item in enumerate(items)}


def check(repo: Path) -> tuple[list[str], dict[str, int]]:
    res = repo / "app/src/main/res"
    errors = []
    scoped = {}
    definitions = {}
    for locale in ("values", "values-es"):
        directory = res / locale
        files = sorted(directory.glob("*iptv*.xml"))
        if not files:
            errors.append(f"{locale}: no IPTV resource files found")
        selected = set(files)
        all_entries = defaultdict(list)
        for path in sorted(directory.glob("*.xml")):
            try:
                root = ET.parse(path).getroot()
            except (OSError, ET.ParseError) as error:
                errors.append(f"{path.relative_to(repo)}: {error}")
                continue
            for element in root:
                name = element.get("name")
                kind = RESOURCE_TYPES.get(element.tag)
                if element.tag == "item" and element.get("type") in ("string", "plurals", "array"):
                    kind = element.get("type")
                if kind and name:
                    entry = Resource(kind, name, path, element)
                    all_entries[entry.key].append(entry)
        for key, entries in all_entries.items():
            if len(entries) > 1 and any(entry.path in selected for entry in entries):
                paths = ", ".join(str(entry.path.relative_to(repo)) for entry in entries)
                errors.append(f"{locale}: duplicate {key[0]}/{key[1]}: {paths}")
        scoped[locale] = {key: entries[0] for key, entries in all_entries.items() if any(entry.path in selected for entry in entries)}
        definitions[locale] = all_entries

    base, spanish = scoped["values"], scoped["values-es"]
    required = {key for key, entry in base.items() if entry.translatable}
    for kind, name in sorted(required - spanish.keys()):
        errors.append(f"values-es: missing translatable {kind}/{name}")
    for kind, name in sorted(spanish.keys() - base.keys()):
        errors.append(f"values-es: no base definition for {kind}/{name}")
    for key in sorted(base.keys() & spanish.keys()):
        english, translated = base[key], spanish[key]
        try:
            english_parts, spanish_parts = variants(english), variants(translated)
            if english_parts.keys() != spanish_parts.keys():
                errors.append(f"{key[0]}/{key[1]}: English/Spanish quantity or array-item keys differ")
                continue
            for variant in english_parts:
                first, second = english_parts[variant], spanish_parts[variant]
                if not english.translatable and android_text(first) != android_text(second):
                    errors.append(f"{key[0]}/{key[1]}[{variant}]: nontranslatable value changed")
                if format_signature(first) != format_signature(second):
                    errors.append(f"{key[0]}/{key[1]}[{variant}]: positional format signature differs")
        except ValueError as error:
            errors.append(f"{key[0]}/{key[1]}: {error}")
    # Validate default definitions even when a nontranslatable entry has no override.
    for key, entry in base.items():
        try:
            for part in variants(entry).values():
                format_signature(part)
        except ValueError as error:
            errors.append(f"{key[0]}/{key[1]}: {error}")

    reference_count = 0
    for path in sorted((repo / "app/src/main").rglob("*")):
        if path.suffix not in (".kt", ".java"):
            continue
        source = path.read_text(encoding="utf-8")
        for match in SOURCE_REFERENCE.finditer(source):
            reference_count += 1
            key = match.group(1), match.group(2)
            if key not in definitions["values"]:
                line = source.count("\n", 0, match.start()) + 1
                errors.append(f"{path.relative_to(repo)}:{line}: missing base {key[0]}/{key[1]}")
    return errors, {"base_resources": len(base), "spanish_resources": len(spanish),
                    "required_translations": len(required), "source_references": reference_count}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    errors, counts = check(args.repo_root.resolve())
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        print(f"IPTV localization check failed: {len(errors)} issue(s)", file=sys.stderr)
        return 1
    print("IPTV localization check passed: " + ", ".join(f"{key}={value}" for key, value in counts.items()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
