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

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly CORE_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd -P)"
extensions_dir="${1:-}"

if [[ -z "${extensions_dir}" || ! -d "${extensions_dir}" || ! -f "${extensions_dir}/pom.xml" ]]; then
	echo "Usage: $0 /path/to/argi-extensions" >&2
	exit 2
fi

extensions_dir="$(cd "${extensions_dir}" && pwd -P)"
readonly EXTENSIONS_DIR="${extensions_dir}"

case "${EXTENSIONS_DIR}" in
	"${CORE_DIR}/target" | "${CORE_DIR}/target/"*)
		echo "Extensions checkout must not live under Core target/: ${EXTENSIONS_DIR}" >&2
		exit 2
		;;
esac

readonly TMP_PARENT="${TMPDIR:-/tmp}"
owned_maven_repo=0
if [[ -n "${EXTENSIONS_COMPAT_MAVEN_REPO:-}" ]]; then
	readonly MAVEN_REPO="${EXTENSIONS_COMPAT_MAVEN_REPO}"
	mkdir -p "${MAVEN_REPO}"
else
	readonly MAVEN_REPO="$(mktemp -d "${TMP_PARENT%/}/argi-extensions-compat-m2.XXXXXX")"
	owned_maven_repo=1
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
