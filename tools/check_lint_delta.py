#!/usr/bin/env python3
"""Reject Android Lint additions against the reviewed R0 inventory.

Run after ``lintDebug``::

    python tools/check_lint_delta.py
    python tools/check_lint_delta.py --report path/to/lint-results.xml \
        --baseline tools/lint-warning-baseline.json

Defaults are relative to this script's repository, independent of the shell's
working directory. No Gradle invocation, report suppression, or baseline rewrite
is performed. Exit codes: 0 = no additions, 1 = added/escalated issue or any
Error/Fatal issue, 2 = unreadable/invalid input.

The inventory counts (id, message, normalized location paths, severity), keeping
duplicate issues but ignoring line/column numbers and location ordering. A removed
issue cannot offset a different new issue. Severity decreases are allowed;
increases are additions. New informational findings also require review.

Project paths become <PROJECT>/..., and Gradle cache paths become
<GRADLE_USER_HOME>/caches/..., independent of drive, user, and slash direction.
Use --project-root for an XML copied from a different checkout. An optional
--gradle-user-home (or GRADLE_USER_HOME) also normalizes non-cache Gradle paths.
Other absolute paths remain distinct. The same known roots are normalized in
messages so embedded machine paths do not create false additions.

For exact AndroidGradlePluginVersion update notices, only the advertised latest
version is normalized: that recommendation changes independently of this project.
The artifact, installed version, severity, locations, and counts remain checked.
"""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import json
import os
from pathlib import Path
import posixpath
import re
import sys
from typing import Iterable
import xml.etree.ElementTree as ET


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SEVERITIES = {"Information": 1, "Warning": 2, "Error": 3, "Fatal": 4}
SCHEMA_VERSION = 1
AGP_UPDATE_NOTICE = re.compile(
    r"(A newer version of \S+ than \S+ is available: )"
    r"[0-9]+(?:\.[0-9]+)+(?:[-+][A-Za-z0-9][A-Za-z0-9.-]*)?"
)


def comparison_message(issue_id: str, message: str) -> str:
    if issue_id == "AndroidGradlePluginVersion":
        match = AGP_UPDATE_NOTICE.fullmatch(message)
        if match:
            return match[1] + "<LATEST_RECOMMENDATION>"
    return message


@dataclass(frozen=True, order=True)
class Finding:
    id: str
    message: str
    paths: tuple[str, ...]
    severity: str

    @property
    def identity(self) -> tuple[str, str, tuple[str, ...]]:
        return self.id, self.message, self.paths


def portable_path(value: str) -> str:
    """Normalize lexically; Windows report paths also work on Unix hosts."""
    return posixpath.normpath(value.replace("\\", "/"))


def is_absolute(value: str) -> bool:
    return value.startswith("/") or bool(re.match(r"^[A-Za-z]:/", value))


class PathNormalizer:
    def __init__(self, project_root: str, gradle_user_home: str | None = None):
        self.roots: dict[str, str] = {portable_path(project_root): "<PROJECT>"}
        if gradle_user_home:
            self.roots[portable_path(gradle_user_home)] = "<GRADLE_USER_HOME>"

    def learn_gradle_roots(self, paths: Iterable[str]) -> None:
        for value in paths:
            path = portable_path(value)
            # Prefer project ownership if the checkout itself contains caches/.
            if self._under_root(path, next(iter(self.roots))) is not None:
                continue
            if is_absolute(path) and "/caches/" in path:
                home, _ = path.split("/caches/", 1)
                self.roots[home] = "<GRADLE_USER_HOME>"

    @staticmethod
    def _under_root(path: str, root: str) -> str | None:
        # Windows drive/UNC paths are case insensitive, Unix paths are not.
        windows = bool(re.match(r"^[A-Za-z]:/", root)) or root.startswith("//")
        compared_path, compared_root = (path.casefold(), root.casefold()) if windows else (path, root)
        if compared_path == compared_root:
            return ""
        if compared_path.startswith(compared_root.rstrip("/") + "/"):
            return path[len(root.rstrip("/")):]
        return None

    def path(self, value: str) -> str:
        path = portable_path(value)
        for root, marker in sorted(self.roots.items(), key=lambda item: -len(item[0])):
            suffix = self._under_root(path, root)
            if suffix is not None:
                return marker + suffix
        if not is_absolute(path):
            return "<PROJECT>/" + path
        return path

    def message(self, value: str) -> str:
        for root, marker in sorted(self.roots.items(), key=lambda item: -len(item[0])):
            # Change separators only inside recognized path references, not prose.
            parts = root.rstrip("/").split("/")
            pattern = r"[\\/]".join(re.escape(part) for part in parts)
            windows = bool(re.match(r"^[A-Za-z]:/", root)) or root.startswith("//")
            value = re.sub(pattern + r"(?=[\\/]|$|[\s`'\"),:])", lambda _: marker, value,
                           flags=re.IGNORECASE if windows else 0)
        # Path suffixes embedded in messages may still use Windows separators.
        return re.sub(r"<(?:PROJECT|GRADLE_USER_HOME)>[^\s`'\"<>]*",
                      lambda match: match.group().replace("\\", "/"), value)


def read_report(path: Path, project_root: str, gradle_user_home: str | None = None) -> Counter[Finding]:
    root = ET.parse(path).getroot()
    if root.tag != "issues":
        raise ValueError("Lint XML must have an <issues> root")
    issues = root.findall("issue")
    normalizer = PathNormalizer(project_root, gradle_user_home)
    normalizer.learn_gradle_roots(location.get("file", "")
                                  for issue in issues for location in issue.findall("location"))
    findings: Counter[Finding] = Counter()
    for issue in issues:
        severity = issue.get("severity", "")
        issue_id, message = issue.get("id", ""), issue.get("message")
        if not issue_id or message is None or severity not in SEVERITIES:
            raise ValueError(f"Invalid Lint issue id/message/severity: {issue_id!r}, {severity!r}")
        locations = issue.findall("location")
        if any(not location.get("file") for location in locations):
            raise ValueError(f"Missing location file for {issue_id}")
        paths = tuple(sorted(normalizer.path(location.attrib["file"]) for location in locations))
        findings[Finding(issue_id, comparison_message(issue_id, normalizer.message(message)), paths, severity)] += 1
    return findings


def read_baseline(path: Path) -> Counter[Finding]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema_version") != SCHEMA_VERSION:
        raise ValueError(f"Baseline schema_version must be {SCHEMA_VERSION}")
    findings: Counter[Finding] = Counter()
    for item in data["issues"]:
        count = item["count"]
        if type(count) is not int or count <= 0:
            raise ValueError("Baseline issue counts must be positive integers")
        if not item["id"] or not isinstance(item["message"], str) or item["severity"] not in SEVERITIES:
            raise ValueError("Invalid baseline id/message/severity")
        paths = item["paths"]
        if not isinstance(paths, list) or any(not isinstance(p, str) or not p for p in paths):
            raise ValueError("Baseline paths must be a list of non-empty strings")
        findings[Finding(item["id"], comparison_message(item["id"], item["message"]),
                         tuple(sorted(paths)), item["severity"])] += count
    expected = data.get("counts_by_severity")
    if expected is not None and dict(severity_counts(findings)) != expected:
        raise ValueError("Baseline counts_by_severity does not match issue inventory")
    return findings


def severity_counts(findings: Counter[Finding]) -> Counter[str]:
    totals: Counter[str] = Counter()
    for finding, count in findings.items():
        totals[finding.severity] += count
    return totals


def compare(current: Counter[Finding], baseline: Counter[Finding]) -> tuple[Counter[Finding], Counter[Finding]]:
    """Return additions and removals, allowing only severity downgrades."""
    common = current & baseline
    added, removed = current - common, baseline - common
    for finding in sorted(added, key=lambda item: (-SEVERITIES[item.severity], item)):
        for previous in sorted(removed, key=lambda item: (SEVERITIES[item.severity], item)):
            if previous.identity != finding.identity or SEVERITIES[previous.severity] <= SEVERITIES[finding.severity]:
                continue
            matched = min(added[finding], removed[previous])
            added[finding] -= matched
            removed[previous] -= matched
    return +added, +removed


def format_counts(findings: Counter[Finding]) -> str:
    counts = severity_counts(findings)
    return ", ".join(f"{counts[severity]} {severity}" for severity in reversed(SEVERITIES) if counts[severity]) or "0 issues"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--report", type=Path, default=PROJECT_ROOT / "app/build/reports/lint-results-debug.xml")
    parser.add_argument("--baseline", type=Path, default=PROJECT_ROOT / "tools/lint-warning-baseline.json")
    parser.add_argument("--project-root", default=str(PROJECT_ROOT), help="Project root used inside the report XML")
    parser.add_argument("--gradle-user-home", default=os.environ.get("GRADLE_USER_HOME"))
    args = parser.parse_args(argv)
    try:
        current = read_report(args.report, args.project_root, args.gradle_user_home)
        baseline = read_baseline(args.baseline)
        added, removed = compare(current, baseline)
    except (OSError, ET.ParseError, ValueError, KeyError, TypeError, AttributeError) as error:
        print(f"Lint delta input error: {error}", file=sys.stderr)
        return 2
    print(f"Lint report: {format_counts(current)}; baseline: {format_counts(baseline)}")
    if any(finding.id == "AndroidGradlePluginVersion" and finding.message.endswith("<LATEST_RECOMMENDATION>")
           for finding in current.keys() | baseline.keys()):
        print("Comparison ignores only the changing latest-version recommendation in exact "
              "AndroidGradlePluginVersion notices; original report and baseline files are unchanged")
    print(f"Added/escalated: {sum(added.values())}; removed: {sum(removed.values())}")
    for finding, count in sorted(added.items()):
        print(f"  + {count} x {finding.severity} {finding.id}: {finding.message}")
        for path in finding.paths:
            print(f"      {path}")
    errors = sum(count for finding, count in current.items() if finding.severity in ("Error", "Fatal"))
    if errors:
        print(f"FAIL: report contains {errors} Error/Fatal issue(s), regardless of baseline")
    if added or errors:
        print("FAIL: review and fix the findings; do not refresh the baseline to hide additions")
        return 1
    print("PASS: no new or escalated Lint findings")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
