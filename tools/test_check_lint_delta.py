"""Synthetic XML tests for the Lint difference gate (no Android build needed)."""

from collections import Counter
from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

import check_lint_delta as gate


class LintDeltaTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def report(self, issues, project_root="C:/checkout/Floryx"):
        xml = ET.Element("issues")
        for item in issues:
            issue = ET.SubElement(xml, "issue", id=item.get("id", "Existing"),
                                  severity=item.get("severity", "Warning"),
                                  message=item.get("message", "Existing warning"))
            for path in item.get("paths", [project_root + "/app/src/Example.kt"]):
                ET.SubElement(issue, "location", file=path, line=str(item.get("line", 10)), column="5")
        path = self.root / "report.xml"
        ET.ElementTree(xml).write(path, encoding="utf-8")
        return gate.read_report(path, project_root)

    def test_unchanged_moved_lines_and_duplicate_counts(self):
        baseline = self.report([{}, {}])
        self.assertEqual(2, sum(baseline.values()))
        self.assertEqual((Counter(), Counter()), gate.compare(self.report([{"line": 99}, {"line": 100}]), baseline))
        added, _ = gate.compare(self.report([{}, {}, {}]), baseline)
        self.assertEqual(1, sum(added.values()))

    def test_removal_passes(self):
        added, removed = gate.compare(self.report([{}]), self.report([{}, {}]))
        self.assertFalse(added)
        self.assertEqual(1, sum(removed.values()))

    def test_replacement_at_same_total_fails(self):
        added, removed = gate.compare(self.report([{"id": "New"}]), self.report([{}]))
        self.assertEqual(1, sum(added.values()))
        self.assertEqual(1, sum(removed.values()))

    def test_changed_message_or_path_is_new(self):
        baseline = self.report([{}])
        for item in ({"message": "Changed warning"}, {"paths": ["app/src/Different.kt"]}):
            with self.subTest(item=item):
                self.assertTrue(gate.compare(self.report([item]), baseline)[0])

    def test_severity_increase_fails_and_decrease_passes(self):
        info, warning = self.report([{"severity": "Information"}]), self.report([{}])
        self.assertTrue(gate.compare(warning, info)[0])
        self.assertFalse(gate.compare(info, warning)[0])

    def test_location_order_does_not_change_identity(self):
        first = self.report([{"paths": ["app/src/One.kt", "app/src/Two.kt"]}])
        second = self.report([{"paths": ["app/src/Two.kt", "app/src/One.kt"]}])
        self.assertFalse(gate.compare(second, first)[0])

    def test_machine_roots_slashes_and_messages_are_portable(self):
        first = self.report([{"paths": [r"C:\checkout\Floryx\app\src\Example.kt"],
                              "message": r"Problem in `C:\checkout\Floryx\app\src\Example.kt`"}])
        second = self.report([{"paths": ["/home/ci/work/Floryx/app/src/Example.kt"],
                               "message": "Problem in `/home/ci/work/Floryx/app/src/Example.kt`"}],
                             project_root="/home/ci/work/Floryx")
        self.assertEqual(first, second)
        relative = self.report([{"paths": ["app/src/Example.kt"]}])
        self.assertEqual(relative, self.report([{}]))

    def test_gradle_custom_and_default_cache_roots_are_portable(self):
        suffix = "caches/modules-2/files-2.1/group/artifact/1/hash/artifact-1.jar"
        first_path = "D:/custom/GradleCache/" + suffix
        second_path = "/home/ci/.gradle/" + suffix
        first = self.report([{"paths": [first_path], "message": "Problem in `" + first_path + "`"}])
        second = self.report([{"paths": [second_path], "message": "Problem in `" + second_path + "`"}])
        self.assertEqual(first, second)

    def test_unknown_external_paths_remain_distinct(self):
        first = self.report([{"paths": ["/opt/one/Example.kt"]}])
        second = self.report([{"paths": ["/opt/two/Example.kt"]}])
        self.assertTrue(gate.compare(second, first)[0])

    def write_baseline(self, findings):
        path = self.root / "baseline.json"
        path.write_text(json.dumps({
            "schema_version": gate.SCHEMA_VERSION,
            "counts_by_severity": dict(gate.severity_counts(findings)),
            "issues": [{"id": finding.id, "severity": finding.severity, "message": finding.message,
                        "paths": list(finding.paths), "count": count} for finding, count in findings.items()],
        }), encoding="utf-8")
        return path

    def cli(self, baseline):
        output = io.StringIO()
        with redirect_stdout(output), redirect_stderr(output):
            code = gate.main(["--report", str(self.root / "report.xml"), "--baseline", str(baseline),
                              "--project-root", "C:/checkout/Floryx"])
        return code, output.getvalue()

    def test_cli_pass_and_replacement_failure(self):
        baseline = self.write_baseline(self.report([{}]))
        self.assertEqual(0, self.cli(baseline)[0])
        self.report([{"id": "New"}])
        self.assertEqual(1, self.cli(baseline)[0])

    def test_agp_latest_recommendation_drift_preserves_raw_files_and_warning_count(self):
        old_message = "A newer version of com.android.application than 8.7.3 is available: 9.4.1"
        new_message = old_message.replace("9.4.1", "9.5.1")
        paths = ("<PROJECT>/gradle/libs.versions.toml",)
        baseline = self.write_baseline(Counter({
            gate.Finding("AndroidGradlePluginVersion", old_message, paths, "Warning"): 3
        }))
        raw_baseline = baseline.read_bytes()
        current = self.report([{"id": "AndroidGradlePluginVersion", "message": new_message,
                                "paths": ["gradle/libs.versions.toml"]}] * 3)
        self.assertEqual(3, sum(current.values()))
        self.assertEqual((Counter(), Counter()), gate.compare(current, gate.read_baseline(baseline)))
        code, output = self.cli(baseline)
        self.assertEqual(0, code)
        self.assertIn("changing latest-version recommendation", output)
        self.assertIn("3 Warning", output)
        self.assertIn(new_message, (self.root / "report.xml").read_text(encoding="utf-8"))
        self.assertEqual(raw_baseline, baseline.read_bytes())

    def test_agp_drift_does_not_hide_installed_artifact_path_severity_or_count_changes(self):
        old_message = "A newer version of com.android.application than 8.7.3 is available: 9.4.1"
        new_message = old_message.replace("9.4.1", "9.5.1")
        baseline = self.write_baseline(Counter({
            gate.Finding("AndroidGradlePluginVersion", old_message,
                         ("<PROJECT>/gradle/libs.versions.toml",), "Warning"): 1
        }))
        original = {"id": "AndroidGradlePluginVersion", "message": new_message,
                    "paths": ["gradle/libs.versions.toml"]}
        changes = (
            {"message": new_message.replace("8.7.3", "8.7.4")},
            {"message": new_message.replace("com.android.application", "com.android.library")},
            {"paths": ["build.gradle.kts"]},
            {"severity": "Error"},
        )
        for change in changes:
            with self.subTest(change=change):
                self.report([original | change])
                self.assertEqual(1, self.cli(baseline)[0])
        self.report([original, original])
        self.assertEqual(1, self.cli(baseline)[0])

    def test_recommendation_normalization_does_not_change_other_issue_or_message_semantics(self):
        old_message = "A newer version of com.android.application than 8.7.3 is available: 9.4.1"
        for issue_id, suffix in (("GradleDependency", ""), ("AndroidGradlePluginVersion", "; review required")):
            with self.subTest(issue_id=issue_id, suffix=suffix):
                baseline = self.write_baseline(Counter({
                    gate.Finding(issue_id, old_message + suffix, ("<PROJECT>/app/src/Example.kt",), "Warning"): 1
                }))
                self.report([{"id": issue_id, "message": old_message.replace("9.4.1", "9.5.1") + suffix}])
                self.assertEqual(1, self.cli(baseline)[0])

    def test_existing_error_or_fatal_always_fails(self):
        for severity in ("Error", "Fatal"):
            with self.subTest(severity=severity):
                baseline = self.write_baseline(self.report([{"severity": severity}]))
                self.assertEqual(1, self.cli(baseline)[0])

    def test_bad_xml_and_missing_report_fail_closed(self):
        baseline = self.write_baseline(self.report([{}]))
        (self.root / "report.xml").write_text("<broken", encoding="utf-8")
        self.assertEqual(2, self.cli(baseline)[0])
        (self.root / "report.xml").unlink()
        self.assertEqual(2, self.cli(baseline)[0])

    def test_invalid_baseline_count_fails_closed(self):
        baseline = self.write_baseline(self.report([{}]))
        data = json.loads(baseline.read_text(encoding="utf-8"))
        data["issues"][0]["count"] = -1
        baseline.write_text(json.dumps(data), encoding="utf-8")
        self.assertEqual(2, self.cli(baseline)[0])


if __name__ == "__main__":
    unittest.main()
