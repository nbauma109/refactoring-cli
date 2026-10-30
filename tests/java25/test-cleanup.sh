#!/usr/bin/env bash
set -euo pipefail

: "${ECLIPSE_HOME:?Set ECLIPSE_HOME to the installed Eclipse directory}"
: "${RUNNER_TEMP:?RUNNER_TEMP must point to a temporary directory}"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_dir="$root/tests/java25/src"
source_file="$source_dir/smoke/Java25FlexibleConstructor.java"
profile="$root/tests/java25/cleanup-profile.xml"
workspace="$RUNNER_TEMP/java25-cleanup-workspace"

# Confirm that this fixture uses Java 25's final flexible constructor bodies.
mkdir -p "$RUNNER_TEMP/java25-original-classes"
javac --release 25 -d "$RUNNER_TEMP/java25-original-classes" "$source_file"
cp "$source_file" "$RUNNER_TEMP/java25-before.java"

run_cleanup() {
    xvfb-run -a "$ECLIPSE_HOME/eclipse" \
        -nosplash -clean -consoleLog \
        -data "$workspace" \
        -application io.github.nbauma109.refactoring.cli.app \
        --source 25 --profile "$profile" "$source_dir"
}

run_cleanup

# The cleanup must really transform the cast, not just exit successfully.
python3 - "$source_file" "$RUNNER_TEMP/java25-before.java" <<'PY'
import pathlib
import re
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
before = pathlib.Path(sys.argv[2]).read_text(encoding="utf-8")

if source == before:
    raise SystemExit("Eclipse cleanup did not modify the Java 25 fixture")
if not re.search(r"\binstanceof\s+String\s+[A-Za-z_$][\w$]*", source):
    raise SystemExit("Eclipse cleanup did not introduce an instanceof pattern")
if re.search(r"\(\s*String\s*\)\s*candidate\b", source):
    raise SystemExit("Eclipse cleanup left the redundant String cast")
if not re.search(r"\bsuper\s*\(\s*\)\s*;", source):
    raise SystemExit("Eclipse cleanup removed the Java 25 constructor invocation")

print("Java 25 source was refactored successfully")
PY

mkdir -p "$RUNNER_TEMP/java25-refactored-classes"
javac --release 25 -d "$RUNNER_TEMP/java25-refactored-classes" "$source_file"

# The same cleanup must be idempotent on the refactored Java 25 source.
cp "$source_file" "$RUNNER_TEMP/java25-after.java"
run_cleanup
diff -u "$RUNNER_TEMP/java25-after.java" "$source_file"
echo "Java 25 cleanup is idempotent"
