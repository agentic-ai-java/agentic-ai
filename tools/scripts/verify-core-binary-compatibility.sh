#!/usr/bin/env bash
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

set -euo pipefail

readonly BASE_COMMIT="${1:-e3de87198da2509168a975c461885cc6c4c1e7c7}"
readonly JAPICMP_VERSION="${JAPICMP_VERSION:-0.23.1}"
readonly REVISION="${REVISION:-2.1.0-RC1}"
readonly CORE_RUNTIME_MODULES=':argi-graph-core,:argi-agent-framework,:argi-studio,:argi-starter-graph-observation,:argi-starter-builtin-nodes'

REPO_ROOT="$(git rev-parse --show-toplevel)"
readonly REPO_ROOT
readonly TMP_PARENT="${TMPDIR:-/tmp}"
COMPAT_TMP="$(mktemp -d "${TMP_PARENT%/}/agentic-core-binary-compat.XXXXXX")"
readonly COMPAT_TMP
readonly BASELINE_WORKTREE="${COMPAT_TMP}/baseline"
readonly REPORT_DIR="${REPO_ROOT}/target/binary-compatibility"
readonly ISOLATED_MAVEN_REPO="${BINARY_COMPAT_MAVEN_REPO:-${REPORT_DIR}/m2}"

cleanup() {
	if git -C "${REPO_ROOT}" worktree list --porcelain | grep -Fqx "worktree ${BASELINE_WORKTREE}"; then
		git -C "${REPO_ROOT}" worktree remove --force "${BASELINE_WORKTREE}" >/dev/null 2>&1 || true
	fi
	if [[ "${COMPAT_TMP}" == "${TMP_PARENT%/}"/agentic-core-binary-compat.* && "${BASELINE_WORKTREE}" == "${COMPAT_TMP}/baseline" && -d "${COMPAT_TMP}" ]]; then
		rm -rf "${COMPAT_TMP}"
	fi
	git -C "${REPO_ROOT}" worktree prune >/dev/null 2>&1 || true
}
trap cleanup EXIT

run_maven() {
	local workdir="$1"
	shift
	(
		cd "${workdir}"
		if [[ -n "${JAVA_HOME:-}" ]]; then
			JAVA_HOME="${JAVA_HOME}" ./mvnw -B -Dmaven.repo.local="${ISOLATED_MAVEN_REPO}" "$@"
		else
			./mvnw -B -Dmaven.repo.local="${ISOLATED_MAVEN_REPO}" "$@"
		fi
	)
}

run_maven_with_retry() {
	local workdir="$1"
	shift
	local attempt=1
	local max_attempts=3

	until run_maven "${workdir}" "$@"; do
		if ((attempt >= max_attempts)); then
			return 1
		fi
		echo "Maven command failed; retrying (${attempt}/${max_attempts})"
		attempt=$((attempt + 1))
	done
}

echo "Preparing baseline worktree at ${BASE_COMMIT}"
git -C "${REPO_ROOT}" worktree add --detach "${BASELINE_WORKTREE}" "${BASE_COMMIT}" >/dev/null

echo "Building baseline public runtime artifacts in isolated Maven repo"
run_maven_with_retry "${BASELINE_WORKTREE}" -U -Dmaven.test.skip=true -pl "${CORE_RUNTIME_MODULES}" -am package

echo "Building candidate public runtime artifacts in isolated Maven repo"
# Clean only runtime modules: cleaning the root would delete the isolated repo.
run_maven_with_retry "${REPO_ROOT}" -pl "${CORE_RUNTIME_MODULES}" clean
run_maven_with_retry "${REPO_ROOT}" -U -Dmaven.test.skip=true -pl "${CORE_RUNTIME_MODULES}" -am package

echo "Resolving japicmp ${JAPICMP_VERSION}"
run_maven_with_retry "${REPO_ROOT}" dependency:get \
	-Dartifact="com.github.siom79.japicmp:japicmp:${JAPICMP_VERSION}:jar:jar-with-dependencies"

readonly JAPICMP_JAR="${ISOLATED_MAVEN_REPO}/com/github/siom79/japicmp/japicmp/${JAPICMP_VERSION}/japicmp-${JAPICMP_VERSION}-jar-with-dependencies.jar"

mkdir -p "${REPORT_DIR}"

compare_module() {
	local label="$1"
	local baseline_jar="$2"
	local candidate_jar="$3"
	local report_file="${REPORT_DIR}/${label}-japicmp.md"
	local -a japicmp_args=(
		--old "${baseline_jar}"
		--new "${candidate_jar}"
		-a protected
		--only-incompatible
		--error-on-binary-incompatibility
		--ignore-missing-classes
	)
	japicmp_args+=(--markdown)

	echo "Comparing ${label}"
	rm -f "${report_file}"
	java -jar "${JAPICMP_JAR}" "${japicmp_args[@]}" > "${report_file}"
	echo "Binary compatible: ${label} (${report_file})"
}

compare_module "argi-graph-core" \
	"${BASELINE_WORKTREE}/argi-graph-core/target/argi-graph-core-${REVISION}.jar" \
	"${REPO_ROOT}/argi-graph-core/target/argi-graph-core-${REVISION}.jar"

compare_module "argi-agent-framework" \
	"${BASELINE_WORKTREE}/argi-agent-framework/target/argi-agent-framework-${REVISION}.jar" \
	"${REPO_ROOT}/argi-agent-framework/target/argi-agent-framework-${REVISION}.jar"

compare_module "argi-studio" \
	"${BASELINE_WORKTREE}/argi-studio/target/argi-studio-${REVISION}.jar" \
	"${REPO_ROOT}/argi-studio/target/argi-studio-${REVISION}.jar"

compare_module "argi-starter-graph-observation" \
	"${BASELINE_WORKTREE}/spring-boot-starters/argi-starter-graph-observation/target/argi-starter-graph-observation-${REVISION}.jar" \
	"${REPO_ROOT}/spring-boot-starters/argi-starter-graph-observation/target/argi-starter-graph-observation-${REVISION}.jar"

compare_module "argi-starter-builtin-nodes" \
	"${BASELINE_WORKTREE}/spring-boot-starters/argi-starter-builtin-nodes/target/argi-starter-builtin-nodes-${REVISION}.jar" \
	"${REPO_ROOT}/spring-boot-starters/argi-starter-builtin-nodes/target/argi-starter-builtin-nodes-${REVISION}.jar"

echo "Core binary compatibility gate passed"
