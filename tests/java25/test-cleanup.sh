#!/usr/bin/env bash
set -euo pipefail

: "${ECLIPSE_HOME:?Set ECLIPSE_HOME to the installed Eclipse directory}"
: "${RUNNER_TEMP:?RUNNER_TEMP must point to a temporary directory}"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_dir="$RUNNER_TEMP/java25-module-imports"
source_file="$source_dir/src/smoke/Java25ModuleImports.java"
expected="$root/tests/java25/expected/Java25ModuleImports.java"
profile="$root/tests/java25/cleanup-profile.xml"
workspace="$RUNNER_TEMP/java25-cleanup-workspace"

mkdir -p "$source_dir/src/smoke"
cp "$root/tests/java25/src/smoke/Java25ModuleImports.java" "$source_file"
cp "$root/tests/java25/src/module-info.java" "$source_dir/src/"

mkdir -p "$RUNNER_TEMP/java25-original-classes"
javac --release 25 -d "$RUNNER_TEMP/java25-original-classes" \
    "$source_dir/src/module-info.java" "$source_file"

run_cleanup() {
    xvfb-run -a "$ECLIPSE_HOME/eclipse" \
        -nosplash -clean -consoleLog \
        -data "$workspace" \
        -application io.github.nbauma109.refactoring.cli.app \
        --source 25 --profile "$profile" "$source_dir" "$@"
}

original="$RUNNER_TEMP/java25-original-source.java"
report="$RUNNER_TEMP/java25-cleanup-report.json"
cp "$source_file" "$original"
run_cleanup --source-root "$source_dir/src" --report "$report"
cmp "$original" "$source_file"
python3 - "$report" <<'PY'
import json
import pathlib
import sys

data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["formatVersion"] == 1, data
issues = data["issues"]
assert issues, "Expected a reported Java 25 module imports cleanup"
assert all(issue["file"] == "src/smoke/Java25ModuleImports.java" for issue in issues), issues
assert all(issue["line"] > 0 and issue["column"] > 0 for issue in issues), issues
assert all(issue["startOffset"] <= issue["endOffset"] for issue in issues), issues
PY

run_cleanup
diff -u "$expected" "$source_file"

mkdir -p "$RUNNER_TEMP/java25-refactored-classes"
javac --release 25 -d "$RUNNER_TEMP/java25-refactored-classes" \
    "$source_dir/src/module-info.java" "$source_file"

run_cleanup
diff -u "$expected" "$source_file"
echo "Java 25 module imports cleanup passed and is idempotent"
