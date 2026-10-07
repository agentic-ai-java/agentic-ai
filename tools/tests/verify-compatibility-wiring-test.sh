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
readonly TEST_TMP="$(mktemp -d "${TMP_PARENT%/}/argi-compatibility-wiring-test.XXXXXX")"

cleanup() {
	if [[ "${TEST_TMP}" == "${TMP_PARENT%/}"/argi-compatibility-wiring-test.* && -d "${TEST_TMP}" ]]; then
		rm -rf "${TEST_TMP}"
	fi
}
trap cleanup EXIT

fail() {
	echo "FAIL: $*" >&2
	exit 1
}

assert_contains() {
	local haystack="$1"
	local needle="$2"
	grep -Fq -- "${needle}" <<< "${haystack}" || fail "expected output to contain: ${needle}; output was: ${haystack}"
}

assert_file_lines() {
	local file="$1"
	local expected="$2"
	local actual
	actual="$(cat "${file}")"
	[[ "${actual}" == "${expected}" ]] || fail "unexpected ${file} contents; expected [${expected}], got [${actual}]"
}

expect_failure() {
	if "$@"; then
		fail "expected command to fail: $*"
	fi
}

copy_make_fixture() {
	local fixture="$1"
	mkdir -p "${fixture}/tools/make" "${fixture}/tools/scripts"
	cp "${REPO_ROOT}/Makefile" "${fixture}/Makefile"
	cp "${REPO_ROOT}"/tools/make/*.mk "${fixture}/tools/make/"
	mkdir -p "${fixture}/tools/linter/codespell"
	cp "${REPO_ROOT}/tools/linter/codespell/.codespell.skip" "${fixture}/tools/linter/codespell/.codespell.skip"
	cat > "${fixture}/tools/scripts/verify-compatibility-wiring.sh" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
printf 'wiring\n' >> "${COMPAT_TEST_LOG}"
SCRIPT
	cat > "${fixture}/tools/scripts/verify-core-binary-compatibility.sh" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
printf 'binary\n' >> "${COMPAT_TEST_LOG}"
if [[ "${COMPAT_FAIL_BINARY:-0}" == "1" ]]; then
	exit 37
fi
SCRIPT
	cat > "${fixture}/tools/scripts/verify-core-source-compatibility.sh" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
printf 'source\n' >> "${COMPAT_TEST_LOG}"
SCRIPT
	chmod +x "${fixture}"/tools/scripts/*.sh
}

test_make_compatibility_check_runs_real_gates_sequentially() {
	local fixture="${TEST_TMP}/make-order"
	local log="${fixture}/order.log"
	copy_make_fixture "${fixture}"

	( cd "${fixture}" && COMPAT_TEST_LOG="${log}" make -j8 compatibility-check >/dev/null )

	assert_file_lines "${log}" $'wiring\nbinary\nsource'
}

test_make_compatibility_check_propagates_binary_failure() {
	local fixture="${TEST_TMP}/make-failure"
	local log="${fixture}/order.log"
	copy_make_fixture "${fixture}"

	expect_failure env COMPAT_TEST_LOG="${log}" COMPAT_FAIL_BINARY=1 make -C "${fixture}" -j8 compatibility-check >/dev/null 2>&1

	assert_file_lines "${log}" $'wiring\nbinary'
}

write_fake_maven() {
	local path="$1"
	local label="$2"
	local fail_var="$3"
	cat > "${path}" <<SCRIPT
#!/usr/bin/env bash
set -euo pipefail
repo=""
for arg in "\$@"; do
	case "\${arg}" in
		-Dmaven.repo.local=*)
			repo="\${arg#-Dmaven.repo.local=}"
			;;
	esac
done
printf '%s|%s|%s\n' '${label}' "\${repo}" "\$*" >> "\${COMMAND_LOG}"
if [[ "\${${fail_var}:-0}" == "1" ]]; then
	exit 23
fi
SCRIPT
	chmod +x "${path}"
}

copy_extensions_script_fixture() {
	local core_dir="$1"
	mkdir -p "${core_dir}/tools/scripts" "${core_dir}/target"
	cp "${REPO_ROOT}/tools/scripts/verify-extensions-compatibility.sh" "${core_dir}/tools/scripts/"
	chmod +x "${core_dir}/tools/scripts/verify-extensions-compatibility.sh"
	write_fake_maven "${core_dir}/mvnw" "core" "CORE_MVN_FAIL"
}

create_extensions_checkout() {
	local extensions_dir="$1"
	mkdir -p "${extensions_dir}"
	touch "${extensions_dir}/pom.xml"
	write_fake_maven "${extensions_dir}/mvnw" "extensions" "EXT_MVN_FAIL"
}

test_extensions_wrapper_uses_one_repo_and_preserves_caller_owned_repo() {
	local core_dir="${TEST_TMP}/core-preserve"
	local extensions_dir="${TEST_TMP}/extensions-preserve"
	local maven_repo="${TEST_TMP}/caller-owned-m2"
	local log="${TEST_TMP}/extensions-preserve.log"
	copy_extensions_script_fixture "${core_dir}"
	create_extensions_checkout "${extensions_dir}"
	mkdir -p "${maven_repo}"

	COMMAND_LOG="${log}" EXTENSIONS_COMPAT_MAVEN_REPO="${maven_repo}" \
		"${core_dir}/tools/scripts/verify-extensions-compatibility.sh" "${extensions_dir}" >/dev/null

	assert_file_lines "${log}" $'core|'"${maven_repo}"$'|-B -Dmaven.repo.local='"${maven_repo}"$' clean install\nextensions|'"${maven_repo}"$'|-B -f pom.xml -Dmaven.repo.local='"${maven_repo}"$' clean test'
	[[ -d "${maven_repo}" ]] || fail "caller-owned Maven repository was removed"
}

test_extensions_wrapper_cleans_default_owned_repo() {
	local core_dir="${TEST_TMP}/core-default"
	local extensions_dir="${TEST_TMP}/extensions-default"
	local log="${TEST_TMP}/extensions-default.log"
	copy_extensions_script_fixture "${core_dir}"
	create_extensions_checkout "${extensions_dir}"

	COMMAND_LOG="${log}" "${core_dir}/tools/scripts/verify-extensions-compatibility.sh" "${extensions_dir}" >/dev/null

	local maven_repo
	maven_repo="$(awk -F'|' 'NR == 1 {print $2}' "${log}")"
	[[ -n "${maven_repo}" ]] || fail "default Maven repository was not logged"
	[[ ! -d "${maven_repo}" ]] || fail "default task-owned Maven repository was not cleaned: ${maven_repo}"
}

test_extensions_wrapper_uses_maven_cmd_when_checkout_has_no_wrapper() {
	local core_dir="${TEST_TMP}/core-maven-cmd"
	local extensions_dir="${TEST_TMP}/extensions-maven-cmd"
	local log="${TEST_TMP}/extensions-maven-cmd.log"
	local bin_dir="${TEST_TMP}/maven-bin"
	copy_extensions_script_fixture "${core_dir}"
	mkdir -p "${extensions_dir}" "${bin_dir}"
	touch "${extensions_dir}/pom.xml"
	write_fake_maven "${bin_dir}/custom-mvn" "extensions-maven-cmd" "EXT_MVN_FAIL"

	COMMAND_LOG="${log}" MAVEN_CMD="${bin_dir}/custom-mvn" \
		"${core_dir}/tools/scripts/verify-extensions-compatibility.sh" "${extensions_dir}" >/dev/null

	assert_contains "$(cat "${log}")" "extensions-maven-cmd|"
}

test_extensions_wrapper_rejects_bad_checkouts_before_maven() {
	local core_dir="${TEST_TMP}/core-reject"
	local log="${TEST_TMP}/extensions-reject.log"
	copy_extensions_script_fixture "${core_dir}"

	expect_failure env COMMAND_LOG="${log}" "${core_dir}/tools/scripts/verify-extensions-compatibility.sh" "${TEST_TMP}/missing" >/dev/null 2>&1
	[[ ! -f "${log}" ]] || fail "Maven ran for missing Extensions checkout"

	local unsafe_dir="${core_dir}/target/argi-extensions"
	mkdir -p "${unsafe_dir}"
	touch "${unsafe_dir}/pom.xml"
	expect_failure env COMMAND_LOG="${log}" "${core_dir}/tools/scripts/verify-extensions-compatibility.sh" "${unsafe_dir}" >/dev/null 2>&1
	[[ ! -f "${log}" ]] || fail "Maven ran for unsafe Extensions checkout"
}

test_extensions_wrapper_propagates_core_and_extensions_failures() {
	local core_dir="${TEST_TMP}/core-failures"
	local extensions_dir="${TEST_TMP}/extensions-failures"
	local core_log="${TEST_TMP}/extensions-core-failure.log"
	local ext_log="${TEST_TMP}/extensions-ext-failure.log"
	copy_extensions_script_fixture "${core_dir}"
	create_extensions_checkout "${extensions_dir}"

	expect_failure env COMMAND_LOG="${core_log}" CORE_MVN_FAIL=1 \
		"${core_dir}/tools/scripts/verify-extensions-compatibility.sh" "${extensions_dir}" >/dev/null 2>&1
	assert_file_lines "${core_log}" 'core|'"$(awk -F'|' 'NR == 1 {print $2}' "${core_log}")"'|-B -Dmaven.repo.local='"$(awk -F'|' 'NR == 1 {print $2}' "${core_log}")"' clean install'

	expect_failure env COMMAND_LOG="${ext_log}" EXT_MVN_FAIL=1 \
		"${core_dir}/tools/scripts/verify-extensions-compatibility.sh" "${extensions_dir}" >/dev/null 2>&1
	assert_contains "$(cat "${ext_log}")" "core|"
	assert_contains "$(cat "${ext_log}")" "extensions|"
}

copy_wiring_validator_fixture() {
	local fixture="$1"
	mkdir -p "${fixture}/tools/make" "${fixture}/tools/scripts" "${fixture}/tools/compatibility/legacy-api-consumer" "${fixture}/.github/workflows" "${fixture}/docs"
	cp "${REPO_ROOT}/Makefile" "${fixture}/Makefile"
	cp "${REPO_ROOT}"/tools/make/*.mk "${fixture}/tools/make/"
	mkdir -p "${fixture}/tools/linter/codespell"
	cp "${REPO_ROOT}/tools/linter/codespell/.codespell.skip" "${fixture}/tools/linter/codespell/.codespell.skip"
	cp "${REPO_ROOT}"/tools/scripts/verify-*-compatibility.sh "${fixture}/tools/scripts/"
	cp "${REPO_ROOT}/tools/scripts/verify-compatibility-wiring.sh" "${fixture}/tools/scripts/"
	cp "${REPO_ROOT}"/tools/scripts/verify-compatibility-wiring.py "${fixture}/tools/scripts/"
	cp "${REPO_ROOT}/tools/compatibility/legacy-api-consumer/pom.xml" "${fixture}/tools/compatibility/legacy-api-consumer/pom.xml"
	cp "${REPO_ROOT}/.github/workflows/build-and-test.yml" "${fixture}/.github/workflows/build-and-test.yml"
	cp "${REPO_ROOT}/docs/compatibility-policy.md" "${fixture}/docs/compatibility-policy.md"
	chmod +x "${fixture}"/tools/scripts/*.sh
	git -C "${fixture}" init -q
}

run_wiring_validator() {
	local fixture="$1"
	( cd "${fixture}" && tools/scripts/verify-compatibility-wiring.sh )
}

test_wiring_validator_accepts_current_semantics() {
	local fixture="${TEST_TMP}/validator-valid"
	copy_wiring_validator_fixture "${fixture}"

	run_wiring_validator "${fixture}" >/dev/null
}

test_wiring_validator_rejects_skipped_make_recipes() {
	local fixture="${TEST_TMP}/validator-make-skip"
	copy_wiring_validator_fixture "${fixture}"
	perl -0pi -e 's#tools/scripts/verify-core-binary-compatibility\.sh#echo "Legacy binary compatibility check skipped"#' "${fixture}/tools/make/java.mk"

	local output
	output="$(run_wiring_validator "${fixture}" 2>&1)" && fail "validator accepted skipped binary recipe"
	assert_contains "${output}" "binary-compatibility-check"
}

test_wiring_validator_rejects_baseline_exclusions_and_workflow_semantics() {
	local baseline_fixture="${TEST_TMP}/validator-baseline"
	copy_wiring_validator_fixture "${baseline_fixture}"
	perl -0pi -e 's/e3de87198da2509168a975c461885cc6c4c1e7c7/c128f02584fc976ee641572074db2e556466f6a2/' "${baseline_fixture}/tools/scripts/verify-core-binary-compatibility.sh"

	local output
	output="$(run_wiring_validator "${baseline_fixture}" 2>&1)" && fail "validator accepted wrong binary baseline"
	assert_contains "${output}" "binary baseline"

	local exclude_fixture="${TEST_TMP}/validator-exclude"
	copy_wiring_validator_fixture "${exclude_fixture}"
	perl -0pi -e 's/--ignore-missing-classes/--ignore-missing-classes\n\t\t--exclude removed.Type/' "${exclude_fixture}/tools/scripts/verify-core-binary-compatibility.sh"
	output="$(run_wiring_validator "${exclude_fixture}" 2>&1)" && fail "validator accepted binary exclusions"
	assert_contains "${output}" "binary compatibility script must not pass japicmp excludes"

	local workflow_fixture="${TEST_TMP}/validator-workflow"
	copy_wiring_validator_fixture "${workflow_fixture}"
	perl -0pi -e 's/, api-compatibility//' "${workflow_fixture}/.github/workflows/build-and-test.yml"
	output="$(run_wiring_validator "${workflow_fixture}" 2>&1)" && fail "validator accepted missing build dependency"
	assert_contains "${output}" "build job"

	local unsafe_fixture="${TEST_TMP}/validator-unsafe-checkout"
	copy_wiring_validator_fixture "${unsafe_fixture}"
	perl -0pi -e 's#path: \.ci/argi-extensions#path: target/argi-extensions#' "${unsafe_fixture}/.github/workflows/build-and-test.yml"
	output="$(run_wiring_validator "${unsafe_fixture}" 2>&1)" && fail "validator accepted unsafe Extensions checkout path"
	assert_contains "${output}" "Extensions checkout path"

	local order_fixture="${TEST_TMP}/validator-api-tools-order"
	copy_wiring_validator_fixture "${order_fixture}"
	perl -0pi -e 's/- run: make tools\n      - run: make compatibility-check/- run: make compatibility-check\n      - run: make tools/' "${order_fixture}/.github/workflows/build-and-test.yml"
	output="$(run_wiring_validator "${order_fixture}" 2>&1)" && fail "validator accepted API check before make tools"
	assert_contains "${output}" "make tools before make compatibility-check"

	local policy_fixture="${TEST_TMP}/validator-policy-contract"
	copy_wiring_validator_fixture "${policy_fixture}"
	perl -0pi -e 's/core_binary_baseline: e3de87198da2509168a975c461885cc6c4c1e7c7/core_binary_baseline: c128f02584fc976ee641572074db2e556466f6a2/' "${policy_fixture}/docs/compatibility-policy.md"
	output="$(run_wiring_validator "${policy_fixture}" 2>&1)" && fail "validator accepted wrong policy contract baseline"
	assert_contains "${output}" "compatibility policy contract core_binary_baseline"
}

main() {
	test_make_compatibility_check_runs_real_gates_sequentially
	test_make_compatibility_check_propagates_binary_failure
	test_extensions_wrapper_uses_one_repo_and_preserves_caller_owned_repo
	test_extensions_wrapper_cleans_default_owned_repo
	test_extensions_wrapper_uses_maven_cmd_when_checkout_has_no_wrapper
	test_extensions_wrapper_rejects_bad_checkouts_before_maven
	test_extensions_wrapper_propagates_core_and_extensions_failures
	test_wiring_validator_accepts_current_semantics
	test_wiring_validator_rejects_skipped_make_recipes
	test_wiring_validator_rejects_baseline_exclusions_and_workflow_semantics
	echo "Compatibility wiring behavior tests passed."
}

main "$@"
