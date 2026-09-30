#!/usr/bin/env bash

# Verify Core and Extensions in dependency order using an isolated Maven repository.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
core_dir="$(cd "$script_dir/../.." && pwd)"
extensions_dir="${1:-$(cd "$core_dir/../argi-extensions" 2>/dev/null && pwd || true)}"

if [[ -z "$extensions_dir" || ! -f "$extensions_dir/pom.xml" ]]; then
  echo "Usage: $0 /path/to/argi-extensions" >&2
  exit 2
fi

temporary_repo=""
if [[ -n "${MAVEN_REPO_LOCAL:-}" ]]; then
  maven_repo="$MAVEN_REPO_LOCAL"
else
  temporary_repo="$(mktemp -d "${TMPDIR:-/tmp}/agentic-maven-repo.XXXXXX")"
  maven_repo="$temporary_repo"
fi

cleanup() {
  if [[ -n "$temporary_repo" ]]; then
    rm -rf "$temporary_repo"
  fi
}
trap cleanup EXIT

echo "Testing and installing Core into $maven_repo"
cd "$core_dir"
./mvnw -B -Dmaven.repo.local="$maven_repo" clean install

echo "Testing Extensions from $extensions_dir against the isolated repository"
if [[ -x "$extensions_dir/mvnw" ]]; then
  extensions_maven="$extensions_dir/mvnw"
else
  extensions_maven="${MAVEN_CMD:-mvn}"
fi
"$extensions_maven" -B -f "$extensions_dir/pom.xml" -Dmaven.repo.local="$maven_repo" clean test
