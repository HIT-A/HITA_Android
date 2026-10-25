import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("checker", Path(__file__).with_name("check_repository.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
DATA_SOURCE = "app/src/main/java/cn/limpu/hita/data/model/Example.kt"
UI_MODEL = "cn.limpu.hita.ui.example.Example"


class RepositoryCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)

    def write(self, name, body):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(body if isinstance(body, bytes) else body.encode())

    def allow(self, *entries):
        self.write("scripts/layer_allowlist.txt", "".join(f"{path}\t{ref}\n" for path, ref in entries))

    def commit_fixture(self):
        # Only the isolated temporary repository is committed; no user Git config is changed.
        subprocess.run(["git", "add", "--all"], cwd=self.root, check=True)
        subprocess.run(["git", "-c", "user.name=Repository Test", "-c", "user.email=test@example.invalid",
                        "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null",
                        "commit", "-qm", "test fixture"], cwd=self.root, check=True)
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.root, text=True).strip()

    def test_rejects_reintroduced_data_and_disguised_executable(self):
        for name in ("data/jobs.db", "app/old.apk", "old.tgz"):
            self.write(name, b"fixture")
        self.write("server", b"\x7fELF" + b"\0" * 30)
        errors = checker.check(self.root)
        for name in ("data/jobs.db", "app/old.apk", "old.tgz", "server"):
            self.assertTrue(any(name in e for e in errors), errors)

    def test_links_include_images_references_and_ignore_examples(self):
        self.write("README.md", "[guide](docs/guide.md)\n![bad](missing.png)\n[ref]: missing.md\n```md\n[x](example-only.md)\n```\n")
        self.write("docs/guide.md", "[root](../README.md)\n[web](https://example.com/page)\n")
        errors = checker.check(self.root)
        self.assertEqual(2, len(errors), errors)
        self.assertTrue(any("missing.png" in e for e in errors))
        self.assertTrue(any("missing.md" in e for e in errors))

    def test_ignored_runtime_files_are_not_valid_documentation_targets(self):
        self.write(".gitignore", "data/\n")
        self.write("data/private.json", "{}")
        self.write("README.md", "[runtime](data/private.json)")
        self.assertEqual(1, len(checker.check(self.root)))

    def test_migration_schemas_fonts_and_gradle_wrapper_are_source(self):
        self.write("app/schemas/OldDatabase/1.json", "{}")
        self.write("app/src/main/res/font/example.ttf", b"\x00\x01\x00\x00font")
        self.write("gradle/wrapper/gradle-wrapper.jar", b"PK\x03\x04fixture")
        self.assertEqual([], checker.check(self.root))

    def test_rejects_imports_aliases_wildcards_and_qualified_calls_in_all_layer_roots(self):
        roots = ("data", "feature/timetableshare/protocol", "feature/timetablecompare")
        for directory in roots:
            for extension in ("kt", "java"):
                path = f"app/src/main/java/cn/limpu/hita/{directory}/Example.{extension}"
                self.write(path, "\n".join((f"import {UI_MODEL}", f"import {UI_MODEL} as Alias",
                                           "import cn.limpu.hita.ui.example.*",
                                           f"import static {UI_MODEL}.VALUE;",
                                           f"val result = {UI_MODEL}.create()")))
        errors = checker.check(self.root)
        self.assertEqual(30, len(errors), errors)
        for directory in roots:
            for extension in ("kt", "java"):
                for line in range(1, 6):
                    expected = f"/{directory}/Example.{extension}:{line}: forbidden UI dependency:"
                    self.assertTrue(any(expected in error for error in errors), errors)

    def test_ignores_other_source_roots_extensions_and_similar_package_prefixes(self):
        for name in ("app/src/test/java/cn/limpu/hita/data/Example.kt",
                     "app/src/androidTest/java/cn/limpu/hita/data/Example.kt",
                     "app/src/main/java/cn/limpu/hita/ui/Example.kt",
                     "app/src/main/java/cn/limpu/hita/data_extra/Example.kt",
                     "app/src/main/java/cn/limpu/hita/feature/timetablecompare_extra/Example.kt",
                     "app/src/main/java/cn/limpu/hita/feature/timetableshare/Example.kt",
                     "app/src/main/java/cn/limpu/hita/data/Example.txt",
                     "component/src/main/java/cn/limpu/hita/data/Example.kt"):
            self.write(name, f"import {UI_MODEL}")
        self.write(DATA_SOURCE, "import cn.limpu.hita.ui_extra.Example\n"
                               "import other.cn.limpu.hita.ui.Example\n"
                               "import mycn.limpu.hita.ui.Example\n")
        self.assertEqual([], checker.check(self.root))

    def test_textual_check_includes_comments_and_strings(self):
        self.write(DATA_SOURCE, f'// {UI_MODEL}\nval name = "{UI_MODEL}"\n')
        errors = checker.check(self.root)
        self.assertEqual(2, len(errors), errors)
        self.assertTrue(any(f"{DATA_SOURCE}:1:" in error for error in errors), errors)
        self.assertTrue(any(f"{DATA_SOURCE}:2:" in error for error in errors), errors)

    def test_untracked_sources_are_checked_but_gitignored_files_are_not(self):
        self.write(".gitignore", "Ignored.kt\n")
        self.write(DATA_SOURCE.replace("Example.kt", "Ignored.kt"), f"import {UI_MODEL}")
        self.assertEqual([], checker.check(self.root))
        self.write(DATA_SOURCE, f"import {UI_MODEL}")
        self.assertEqual(1, len(checker.check(self.root)))

    def test_allowlist_matches_exact_reference_without_binding_line_or_alias(self):
        call = f"{UI_MODEL}.create"
        self.allow((DATA_SOURCE, UI_MODEL), (DATA_SOURCE, call))
        self.write(DATA_SOURCE, f"\n\nimport {UI_MODEL} as Alias\nval value = {call}()\n")
        self.assertEqual([], checker.check(self.root))
        self.write(DATA_SOURCE, f"import {UI_MODEL}\nval value = {call}()\nimport {UI_MODEL}Extra\n")
        errors = checker.check(self.root)
        self.assertEqual(1, len(errors), errors)
        self.assertIn(f"{UI_MODEL}Extra", errors[0])

    def test_allowlist_does_not_exempt_another_file_with_the_same_reference(self):
        self.allow((DATA_SOURCE, UI_MODEL))
        self.write(DATA_SOURCE, f"import {UI_MODEL}")
        other = DATA_SOURCE.replace("Example.kt", "Other.kt")
        self.write(other, f"import {UI_MODEL}")
        errors = checker.check(self.root)
        self.assertEqual(1, len(errors), errors)
        self.assertIn(other, errors[0])

    def test_wildcard_import_is_not_a_prefix_allowance(self):
        wildcard = "cn.limpu.hita.ui.example.*"
        self.allow((DATA_SOURCE, wildcard))
        self.write(DATA_SOURCE, f"import {wildcard}\nimport {UI_MODEL}")
        errors = checker.check(self.root)
        self.assertEqual(1, len(errors), errors)
        self.assertIn(UI_MODEL, errors[0])

    def test_rejects_stale_exceptions_after_reference_or_file_removal(self):
        self.allow((DATA_SOURCE, UI_MODEL))
        self.write(DATA_SOURCE, "class Example\n")
        errors = checker.check(self.root)
        self.assertEqual(1, len(errors), errors)
        self.assertIn("stale layer exception", errors[0])
        (self.root / DATA_SOURCE).unlink()
        errors = checker.check(self.root)
        self.assertEqual(1, len(errors), errors)
        self.assertIn("stale layer exception", errors[0])

    def test_rejects_duplicate_and_malformed_allowlist_entries(self):
        self.write(DATA_SOURCE, f"import {UI_MODEL}")
        entry = f"{DATA_SOURCE}\t{UI_MODEL}\n"
        self.write("scripts/layer_allowlist.txt", entry + entry + "missing-reference\n"
                   + f"README.md {UI_MODEL}\n"
                   + f"app/src/main/java/cn/limpu/hita/data/../ui/Example.kt {UI_MODEL}\n"
                   + f"{DATA_SOURCE} cn.limpu.hita.ui_extra.Example\n")
        errors = checker.check(self.root)
        self.assertEqual(5, len(errors), errors)
        for message in (":2: duplicate", ":3: expected", ":4: invalid restricted",
                        ":5: invalid restricted", ":6: invalid UI"):
            self.assertTrue(any(message in error for error in errors), errors)

    def test_rejects_non_utf8_layer_source(self):
        self.write(DATA_SOURCE, b"\xff")
        errors = checker.check(self.root)
        self.assertEqual(1, len(errors), errors)
        self.assertIn("requires UTF-8", errors[0])

    def test_baseline_allows_unchanged_reordered_or_removed_exceptions(self):
        other = f"{UI_MODEL}Other"
        self.write(DATA_SOURCE, f"import {UI_MODEL}\nimport {other}")
        self.allow((DATA_SOURCE, UI_MODEL), (DATA_SOURCE, other))
        base = self.commit_fixture()
        self.assertEqual([], checker.check(self.root, base))
        self.write("scripts/layer_allowlist.txt", f"# reordered\n\n{DATA_SOURCE} {other}\n"
                   f"{DATA_SOURCE} {UI_MODEL}\n")
        self.assertEqual([], checker.check(self.root, base))
        self.write(DATA_SOURCE, f"import {UI_MODEL}")
        self.allow((DATA_SOURCE, UI_MODEL))
        self.assertEqual([], checker.check(self.root, base))
        self.write(DATA_SOURCE, "class Example")
        self.allow()
        self.assertEqual([], checker.check(self.root, base))

    def test_baseline_rejects_added_exception_even_when_source_has_matching_reference(self):
        self.write(DATA_SOURCE, f"import {UI_MODEL}")
        self.allow((DATA_SOURCE, UI_MODEL))
        base = self.commit_fixture()
        other = f"{UI_MODEL}Other"
        self.write(DATA_SOURCE, f"import {UI_MODEL}\nimport {other}")
        self.allow((DATA_SOURCE, UI_MODEL), (DATA_SOURCE, other))
        errors = checker.check(self.root, base)
        self.assertEqual(1, len(errors), errors)
        self.assertIn(f"layer exception added since {base}", errors[0])
        self.assertIn(other, errors[0])

    def test_baseline_rejects_replacement_without_growing_allowlist(self):
        self.write(DATA_SOURCE, f"import {UI_MODEL}")
        self.allow((DATA_SOURCE, UI_MODEL))
        base = self.commit_fixture()
        other = f"{UI_MODEL}Other"
        self.write(DATA_SOURCE, f"import {other}")
        self.allow((DATA_SOURCE, other))
        errors = checker.check(self.root, base)
        self.assertEqual(1, len(errors), errors)
        self.assertIn("layer exception added", errors[0])

    def test_first_allowlist_uses_base_source_not_current_source(self):
        self.write(DATA_SOURCE, f"import {UI_MODEL}\n")
        base = self.commit_fixture()
        self.allow((DATA_SOURCE, UI_MODEL))
        self.assertEqual([], checker.check(self.root, base))
        other = f"{UI_MODEL}Other"
        self.write(DATA_SOURCE, f"import {UI_MODEL}\nimport {other}\n")
        self.allow((DATA_SOURCE, UI_MODEL), (DATA_SOURCE, other))
        errors = checker.check(self.root, base)
        self.assertEqual(1, len(errors), errors)
        self.assertIn(other, errors[0])
        self.assertIn("layer exception added", errors[0])

    def test_empty_baseline_allowlist_does_not_reopen_bootstrap(self):
        self.write(DATA_SOURCE, f"import {UI_MODEL}")
        self.allow()
        base = self.commit_fixture()
        self.allow((DATA_SOURCE, UI_MODEL))
        errors = checker.check(self.root, base)
        self.assertEqual(1, len(errors), errors)
        self.assertIn("layer exception added", errors[0])

    def test_missing_baseline_fails_instead_of_skipping_comparison(self):
        self.write(DATA_SOURCE, "class Example")
        errors = checker.check(self.root, "refs/heads/not-fetched")
        self.assertEqual(1, len(errors), errors)
        self.assertIn("cannot read layer baseline", errors[0])

    def test_external_symlink_source_is_rejected_without_reading_its_content(self):
        with tempfile.TemporaryDirectory() as external:
            target = Path(external) / "Example.kt"
            target.write_text(f"import {UI_MODEL}")
            link = self.root / DATA_SOURCE
            link.parent.mkdir(parents=True)
            link.symlink_to(target)
            errors = checker.check(self.root)
            self.assertEqual([f"{DATA_SOURCE}: symlink leaves repository"], errors)


if __name__ == "__main__":
    unittest.main()
