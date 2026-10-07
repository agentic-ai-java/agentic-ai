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

readonly INVOCATION_DIR="$(pwd -P)"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly CORE_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd -P)"
extensions_dir="${1:-}"

if [[ -z "${extensions_dir}" || ! -d "${extensions_dir}" || ! -f "${extensions_dir}/pom.xml" ]]; then
	echo "Usage: $0 /path/to/argi-extensions" >&2
	exit 2
fi

extensions_dir="$(cd "${extensions_dir}" && pwd -P)"
readonly EXTENSIONS_DIR="${extensions_dir}"

path_is_inside_target_tree() {
	local root_dir="$1"
	local candidate_path="$2"
	local relative_path

	case "${candidate_path}" in
		"${root_dir}")
			return 1
			;;
		"${root_dir}/"*)
			relative_path="${candidate_path#"${root_dir}/"}"
			;;
		*)
			return 1
			;;
	esac

	[[ "${relative_path}" == "target" || "${relative_path}" == target/* || "${relative_path}" == */target || "${relative_path}" == */target/* ]]
}

canonicalize_maven_repo() {
	local repo_path="$1"
	local absolute_repo_path

	if [[ "${repo_path}" == /* ]]; then
		absolute_repo_path="${repo_path}"
	else
		absolute_repo_path="${INVOCATION_DIR}/${repo_path}"
	fi
	mkdir -p "${absolute_repo_path}"
	( cd "${absolute_repo_path}" && pwd -P )
}

if path_is_inside_target_tree "${CORE_DIR}" "${EXTENSIONS_DIR}"; then
	echo "Extensions checkout must not live under a Core target/ directory: ${EXTENSIONS_DIR}" >&2
	exit 2
fi

readonly TMP_PARENT="${TMPDIR:-/tmp}"
owned_maven_repo=0
if [[ -n "${EXTENSIONS_COMPAT_MAVEN_REPO:-}" ]]; then
	readonly MAVEN_REPO="$(canonicalize_maven_repo "${EXTENSIONS_COMPAT_MAVEN_REPO}")"
else
	readonly MAVEN_REPO="$(mktemp -d "${TMP_PARENT%/}/argi-extensions-compat-m2.XXXXXX")"
	owned_maven_repo=1
fi

if path_is_inside_target_tree "${CORE_DIR}" "${MAVEN_REPO}"; then
	echo "Caller-owned Maven repository must not live under a Core target/ directory: ${MAVEN_REPO}" >&2
	exit 2
fi
if path_is_inside_target_tree "${EXTENSIONS_DIR}" "${MAVEN_REPO}"; then
	echo "Caller-owned Maven repository must not live under an Extensions target/ directory: ${MAVEN_REPO}" >&2
	exit 2
fi

cleanup() {
	if [[ "${owned_maven_repo}" == "1" && "${MAVEN_REPO}" == "${TMP_PARENT%/}"/argi-extensions-compat-m2.* && -d "${MAVEN_REPO}" ]]; then
		rm -rf "${MAVEN_REPO}"
	fi
}
trap cleanup EXIT

echo "Testing and installing Core into ${MAVEN_REPO}"
(
	cd "${CORE_DIR}"
	./mvnw -B -Dmaven.repo.local="${MAVEN_REPO}" clean install
)

extensions_maven="${EXTENSIONS_DIR}/mvnw"
if [[ ! -x "${extensions_maven}" ]]; then
	extensions_maven="${MAVEN_CMD:-mvn}"
fi

echo "Testing Extensions from ${EXTENSIONS_DIR} against candidate Core artifacts"
(
	cd "${EXTENSIONS_DIR}"
	"${extensions_maven}" -B -f pom.xml -Dmaven.repo.local="${MAVEN_REPO}" clean test
)
