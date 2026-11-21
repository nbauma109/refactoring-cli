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

run_cleanup_with_profile() {
    local active_profile="$1"
    local active_root="$2"
    shift 2
    xvfb-run -a "$ECLIPSE_HOME/eclipse" \
        -nosplash -clean -consoleLog \
        -data "$workspace" \
        -application io.github.nbauma109.refactoring.cli.app \
        --source 25 --profile "$active_profile" "$active_root" "$@"
}

run_cleanup() {
    run_cleanup_with_profile "$profile" "$source_dir" "$@"
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
assert all(issue["cleanup"] == "cleanup.module_imports" for issue in issues), issues
assert all(issue["module"] for issue in issues), issues
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

overlap_dir="$RUNNER_TEMP/java25-overlap-cleanups"
overlap_file="$overlap_dir/src/smoke/OverlapCleanups.java"
mkdir -p "$(dirname "$overlap_file")"
cp "$root/tests/java25/src/smoke/OverlapCleanups.java" "$overlap_file"
cp "$overlap_file" "$RUNNER_TEMP/overlap-cleanups-original.java"
javac --release 25 -d "$RUNNER_TEMP/java25-overlap-classes" "$overlap_file"

for option in remove_unused_imports remove_unused_local_variables remove_unnecessary_casts; do
    option_profile="$RUNNER_TEMP/java25-$option.xml"
    option_report="$RUNNER_TEMP/java25-$option-report.json"
    cat > "$option_profile" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<profiles version="2">
  <profile kind="CleanUpProfile" name="java25-$option" version="2">
    <setting id="cleanup.$option" value="true"/>
  </profile>
</profiles>
XML
    run_cleanup_with_profile "$option_profile" "$overlap_dir" \
        --source-root "$overlap_dir/src" --report "$option_report"
    cmp "$RUNNER_TEMP/overlap-cleanups-original.java" "$overlap_file"
    python3 - "$option_report" "$option" <<'PY'
import json
from pathlib import Path
import sys

report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
option = "cleanup." + sys.argv[2]
issues = report["issues"]
assert issues, "Expected an Eclipse cleanup finding for " + option
assert any(issue["cleanup"] == option for issue in issues), (option, issues)
assert all(issue["file"] == "src/smoke/OverlapCleanups.java" for issue in issues), issues
PY
done
echo "Real Eclipse option-level cleanup reports passed without modifying sources"

override_resource_dir="$RUNNER_TEMP/java25-override-resource"
override_resource_file="$override_resource_dir/src/smoke/OverrideResourceCleanups.java"
mkdir -p "$(dirname "$override_resource_file")"
cp "$root/tests/java25/src/smoke/OverrideResourceCleanups.java" "$override_resource_file"
cp "$override_resource_file" "$RUNNER_TEMP/override-resource-original.java"
javac --release 25 -d "$RUNNER_TEMP/java25-override-resource-classes" "$override_resource_file"

for option in add_missing_override_annotations try_with_resource; do
    option_profile="$RUNNER_TEMP/java25-$option.xml"
    option_report="$RUNNER_TEMP/java25-$option-report.json"
    cat > "$option_profile" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<profiles version="2">
  <profile kind="CleanUpProfile" name="java25-$option" version="2">
    <setting id="cleanup.$option" value="true"/>
  </profile>
</profiles>
XML
    run_cleanup_with_profile "$option_profile" "$override_resource_dir" \
        --source-root "$override_resource_dir/src" --report "$option_report"
    cmp "$RUNNER_TEMP/override-resource-original.java" "$override_resource_file"
    python3 - "$option_report" "$option" <<'PY'
import json
from pathlib import Path
import sys

report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
option = "cleanup." + sys.argv[2]
issues = report["issues"]
print(option, json.dumps(issues, indent=2))
assert issues, "Expected real Eclipse cleanup output for " + option
assert any(issue["cleanup"] == option for issue in issues), (option, issues)
assert all(issue["file"] == "src/smoke/OverrideResourceCleanups.java" for issue in issues), issues
PY
done
echo "Eclipse Override and try-with-resources reporting passed without modifying source"
