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

readonly REPO_ROOT="$(git rev-parse --show-toplevel)"
readonly TMP_PARENT="${TMPDIR:-/tmp}"
readonly COMPAT_REPO="$(mktemp -d "${TMP_PARENT%/}/agentic-source-compat.XXXXXX")"
readonly SOURCE_COMPAT_MODULES=':agentic-spring-ai-agent-framework,:agentic-spring-ai-studio,:agentic-spring-ai-starter-graph-observation,:agentic-spring-ai-starter-builtin-nodes'
readonly FIXTURE_POM="${REPO_ROOT}/tools/compatibility/legacy-api-consumer/pom.xml"

cleanup() {
	if [[ "${COMPAT_REPO}" == "${TMP_PARENT%/}"/agentic-source-compat.* && -d "${COMPAT_REPO}" ]]; then
		rm -rf "${COMPAT_REPO}"
	fi
}
trap cleanup EXIT

if [[ ! -f "${REPO_ROOT}/mvnw" ]]; then
	echo "Cannot find Maven wrapper under repository root: ${REPO_ROOT}" >&2
	exit 1
fi

if [[ ! -f "${FIXTURE_POM}" ]]; then
	echo "Cannot find source compatibility fixture POM: ${FIXTURE_POM}" >&2
	exit 1
fi

"${REPO_ROOT}/mvnw" -B -Dmaven.repo.local="${COMPAT_REPO}" \
	-DskipTests -pl "${SOURCE_COMPAT_MODULES}" \
	-am install

"${REPO_ROOT}/mvnw" -B -Dmaven.repo.local="${COMPAT_REPO}" \
	-f "${FIXTURE_POM}" clean compile

echo "Core source compatibility gate passed"
