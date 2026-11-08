#!/usr/bin/env python3
"""Documentation and repository consistency checks.

Runs in CI (and locally) without third-party dependencies. Exit code 1 means
the repository is inconsistent; every failure is printed with a reason.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"

REQUIRED = [
    "README.md",
    "CONTRIBUTING.md",
    "TESTING.md",
    "SECURITY.md",
    "CHANGELOG.md",
    ".github/workflows/ci.yml",
]

errors: list[str] = []
warnings: list[str] = []


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def fail(message: str) -> None:
    errors.append(message)


def warn(message: str) -> None:
    warnings.append(message)


def check_required_files() -> None:
    for name in REQUIRED:
        if not (ROOT / name).exists():
            fail(f"missing required file: {name}")


def check_workflow_triggers() -> None:
    for workflow in sorted(WORKFLOWS.glob("*.yml")) + sorted(WORKFLOWS.glob("*.yaml")):
        text = read(workflow)
        for line in text.splitlines():
            if re.match(r"^\s*(push|pull_request)\s*:", line):
                fail(f"{workflow.name}: automatic trigger is not allowed: {line.strip()}")
        if "workflow_dispatch:" not in text:
            fail(f"{workflow.name}: missing workflow_dispatch trigger")


def check_action_pins() -> None:
    for workflow in sorted(WORKFLOWS.glob("*.yml")) + sorted(WORKFLOWS.glob("*.yaml")):
        for match in re.finditer(r"uses:\s*([^\s#]+)", read(workflow)):
            ref = match.group(1)
            if ref.startswith("./") or ref.startswith("docker://"):
                continue
            if "@" not in ref:
                fail(f"{workflow.name}: action without a ref: {ref}")
                continue
            version = ref.rsplit("@", 1)[1]
            if not re.fullmatch(r"[0-9a-f]{40}", version):
                fail(f"{workflow.name}: action is not pinned to a commit SHA: {ref}")


def check_readme() -> None:
    readme = read(ROOT / "README.md")
    if "github.com/nosgk/ChromeXt-nosgk/actions/workflows/ci.yml" not in readme:
        fail("README.md: CI badge does not point at this repository's ci.yml")
    if "JingMatrix/ChromeXt/actions" in readme:
        fail("README.md: stale upstream CI badge")
    if "arm64-v8a" not in readme:
        fail("README.md: missing arm64-v8a artifact documentation")


def check_version() -> None:
    build = read(ROOT / "app" / "build.gradle.kts")
    match = re.search(r'versionName\s*=\s*"([^"]+)"', build)
    if not match:
        fail("app/build.gradle.kts: cannot find versionName")
        return
    version = match.group(1)
    changelog = read(ROOT / "CHANGELOG.md")
    if f"[{version}]" not in changelog and f"## [{version}]" not in changelog:
        fail(f"CHANGELOG.md: missing section for versionName {version}")


def check_package_lists() -> None:
    main = read(ROOT / "app" / "src" / "main" / "kotlin" / "org" / "matrix" / "chromext" / "MainHook.kt")
    block = main.split("supportedPackages", 1)[1].split(")", 1)[0]
    supported = set(re.findall(r'"([a-z0-9_.]+)"', block))
    arrays_path = ROOT / "app" / "src" / "main" / "res" / "values" / "arrays.xml"
    arrays = set(re.findall(r"<item>([^<]+)</item>", read(arrays_path)))
    missing = sorted(supported - arrays)
    if missing:
        fail(f"arrays.xml: missing supported packages: {', '.join(missing)}")


def check_local_links() -> None:
    for name in ["README.md", "CONTRIBUTING.md", "TESTING.md", "SECURITY.md", "CHANGELOG.md"]:
        text = read(ROOT / name)
        for target in re.findall(r"\]\(([^)\s]+)\)", text):
            if target.startswith(("http://", "https://", "mailto:", "#")):
                continue
            path = target.split("#", 1)[0].split("?", 1)[0]
            if not path:
                continue
            if not (ROOT / path).exists():
                fail(f"{name}: broken local link: {target}")


def check_ci_scripts() -> None:
    ci = read(WORKFLOWS / "ci.yml")
    for script in ["scripts/verify-release.sh", "scripts/sign-apk.sh"]:
        if script in ci and not (ROOT / script).exists():
            fail(f"ci.yml references missing script: {script}")


def check_no_internal_docs() -> None:
    result = subprocess.run(
        ["git", "ls-files", "docs/harness"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode == 0 and result.stdout.strip():
        fail("internal documents under docs/harness are tracked; they must stay local")


def main() -> int:
    check_required_files()
    check_workflow_triggers()
    check_action_pins()
    check_readme()
    check_version()
    check_package_lists()
    check_local_links()
    check_ci_scripts()
    check_no_internal_docs()

    for message in warnings:
        print(f"warning: {message}")
    for message in errors:
        print(f"error: {message}")

    if errors:
        print(f"\n{len(errors)} error(s)")
        return 1
    print("documentation checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
