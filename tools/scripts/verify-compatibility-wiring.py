#!/usr/bin/env python3
#
# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import subprocess
import sys
from pathlib import Path

import yaml


REQUIRED_BASELINE_COMMIT = "e3de87198da2509168a975c461885cc6c4c1e7c7"
REQUIRED_EXTENSIONS_REPOSITORY = "agentic-ai-java/argi-extensions"
REQUIRED_EXTENSIONS_REFS = [
    "ec023a24910a0a30c0e3e1c810e4c3ac2ef0dc40",
    "ed07835729405e7c8b97772d5b478d63ac1f9760",
]
REQUIRED_EXTENSIONS_CHECKOUT_PATH = ".ci/argi-extensions"
REQUIRED_GATE_ORDER = [
    "compatibility-wiring-check",
    "binary-compatibility-check",
    "source-compatibility-check",
]


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: verify-compatibility-wiring.py REPO_ROOT", file=sys.stderr)
        return 2

    repo_root = Path(sys.argv[1]).resolve()
    failures: list[str] = []

    require_file(repo_root, "tools/scripts/verify-core-binary-compatibility.sh", failures)
    require_file(repo_root, "tools/scripts/verify-core-source-compatibility.sh", failures)
    require_file(repo_root, "tools/scripts/verify-extensions-compatibility.sh", failures)
    require_file(repo_root, "tools/compatibility/legacy-api-consumer/pom.xml", failures)
    require_file(repo_root, "tools/compatibility/policy.yaml", failures)

    policy_contract = validate_policy(repo_root, failures)

    validate_make_targets(repo_root, failures, policy_contract)
    validate_binary_script(repo_root, failures, policy_contract)
    validate_source_fixture(repo_root, failures)

    if failures:
        print("Compatibility wiring verification failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("Compatibility wiring verification passed.")
    return 0


def require_file(repo_root: Path, relative_path: str, failures: list[str]) -> None:
    if not (repo_root / relative_path).is_file():
        failures.append(f"missing file: {relative_path}")


def read_text(repo_root: Path, relative_path: str, failures: list[str]) -> str:
    path = repo_root / relative_path
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        failures.append(f"cannot read {relative_path}: {exc}")
        return ""


def validate_make_targets(repo_root: Path, failures: list[str], policy_contract: dict) -> None:
    expected_commands = {
        "compatibility-wiring-check": "tools/scripts/verify-compatibility-wiring.sh",
        "binary-compatibility-check": "tools/scripts/verify-core-binary-compatibility.sh",
        "source-compatibility-check": "tools/scripts/verify-core-source-compatibility.sh",
    }
    for target, expected_command in expected_commands.items():
        output = run_make_dry_run(repo_root, target, failures)
        if expected_command not in output:
            failures.append(f"{target} must execute {expected_command}")
        if "skipped" in output.lower():
            failures.append(f"{target} must not skip compatibility validation")

    combined = run_make_dry_run(repo_root, "compatibility-check", failures)
    gate_scripts = {
        "compatibility-wiring-check": "tools/scripts/verify-compatibility-wiring.sh",
        "binary-compatibility-check": "tools/scripts/verify-core-binary-compatibility.sh",
        "source-compatibility-check": "tools/scripts/verify-core-source-compatibility.sh",
    }
    expected_order = policy_contract.get("compatibility_gate_order", REQUIRED_GATE_ORDER)
    positions = [combined.find(gate_scripts.get(target, "")) for target in expected_order]
    if any(position < 0 for position in positions) or positions != sorted(positions):
        failures.append("compatibility-check must run wiring, binary, then source compatibility gates sequentially")


def run_make_dry_run(repo_root: Path, target: str, failures: list[str]) -> str:
    completed = subprocess.run(
        ["make", "-s", "-n", "-C", str(repo_root), target],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if completed.returncode != 0:
        failures.append(f"make dry-run failed for {target}: {completed.stdout.strip()}")
    return completed.stdout


def validate_binary_script(repo_root: Path, failures: list[str], policy_contract: dict) -> None:
    text = read_text(repo_root, "tools/scripts/verify-core-binary-compatibility.sh", failures)
    baseline_commit = policy_contract.get("core_binary_baseline", REQUIRED_BASELINE_COMMIT)
    if baseline_commit not in text:
        failures.append(f"binary baseline must match policy contract {baseline_commit}")
    if "REMOVED_BUILTIN" in text:
        failures.append("binary compatibility script must not carry pre-ARGI removed-type exclusions")
    if "--exclude" in text:
        failures.append("binary compatibility script must not pass japicmp excludes")
    if 'run_maven_with_retry "${REPO_ROOT}" -pl "${CORE_RUNTIME_MODULES}" clean' not in text:
        failures.append("binary compatibility script must clean candidate runtime modules before packaging")


def validate_source_fixture(repo_root: Path, failures: list[str]) -> None:
    text = read_text(repo_root, "tools/compatibility/legacy-api-consumer/pom.xml", failures)
    if "<groupId>io.github.agentic-ai-java</groupId>" not in text:
        failures.append("source compatibility fixture must use current ARGI Maven coordinates")


def validate_policy(repo_root: Path, failures: list[str]) -> dict:
    text = read_text(repo_root, "tools/compatibility/policy.yaml", failures)
    try:
        contract = yaml.safe_load(text)
    except yaml.YAMLError as exc:
        failures.append(f"compatibility policy gate contract must parse as YAML: {exc}")
        return {}

    if not isinstance(contract, dict):
        failures.append("compatibility policy gate contract must be a mapping")
        return {}

    expected_values = {
        "core_binary_baseline": REQUIRED_BASELINE_COMMIT,
        "extensions_repository": REQUIRED_EXTENSIONS_REPOSITORY,
        "extensions_refs": REQUIRED_EXTENSIONS_REFS,
        "extensions_checkout_path": REQUIRED_EXTENSIONS_CHECKOUT_PATH,
        "extensions_maven_repo_override": "EXTENSIONS_COMPAT_MAVEN_REPO",
        "compatibility_gate_order": REQUIRED_GATE_ORDER,
    }
    for key, expected in expected_values.items():
        if contract.get(key) != expected:
            failures.append(f"compatibility policy contract {key} must be {expected}")

    return contract


if __name__ == "__main__":
    sys.exit(main())
