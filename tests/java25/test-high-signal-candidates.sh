#!/usr/bin/env bash
set -euo pipefail

: "${ECLIPSE_HOME:?Set ECLIPSE_HOME to the installed Eclipse distribution}"
: "${RUNNER_TEMP:?Set RUNNER_TEMP to the temporary directory}"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
project="$RUNNER_TEMP/java25-high-signal-cleanups"
source_file="$project/src/smoke/HighSignalCleanupCandidates.java"
workspace="$RUNNER_TEMP/java25-high-signal-workspace"
summary="$RUNNER_TEMP/java25-high-signal-summary.json"
mkdir -p "$(dirname "$source_file")"
cp "$root/tests/java25/src/smoke/HighSignalCleanupCandidates.java" "$source_file"
original_hash="$(sha256sum "$source_file")"
javac --release 25 -d "$RUNNER_TEMP/java25-high-signal-classes" "$source_file"

options=(precompile_regex static_inner_class remove_unnecessary_suppress_warnings)
printf '[\n' > "$summary"
separator=''
for option in "${options[@]}"; do
    profile="$RUNNER_TEMP/java25-high-signal-$option.xml"
    report="$RUNNER_TEMP/java25-high-signal-$option.json"
    cat > "$profile" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<profiles version="2">
  <profile kind="CleanUpProfile" name="high-signal-$option" version="2">
    <setting id="cleanup.$option" value="true"/>
  </profile>
</profiles>
XML

    xvfb-run -a "$ECLIPSE_HOME/eclipse" \
        -nosplash -clean -consoleLog \
        -data "$workspace" \
        -application io.github.nbauma109.refactoring.cli.app \
        --source 25 --profile "$profile" \
        "$project" --source-root "$project/src" --report "$report" \
        > "$RUNNER_TEMP/java25-high-signal-$option.log" 2>&1

    test "$(sha256sum "$source_file")" = "$original_hash"
    python3 - "$report" "$option" "$source_file" "$summary" "$separator" <<'PY'
import json
from pathlib import Path
import sys

document = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
identifier = "cleanup." + sys.argv[2]
source = Path(sys.argv[3]).read_text(encoding="utf-8")
assert document["formatVersion"] == 1, document
issues = document["issues"]
for issue in issues:
    assert issue["file"] == "src/smoke/HighSignalCleanupCandidates.java", issue
    assert 0 <= issue["startOffset"] <= issue["endOffset"] <= len(source), issue
    assert issue["line"] > 0 and issue["column"] > 0, issue

matching = [issue for issue in issues if issue["cleanup"] == identifier]
result = {
    "cleanup": identifier,
    "reported": len(issues),
    "attributed": len(matching),
    "identifiers": sorted({issue["cleanup"] for issue in issues}),
    "lines": sorted(issue["line"] for issue in matching),
}
print("HIGH_SIGNAL_CANDIDATE", json.dumps(result, sort_keys=True))
with Path(sys.argv[4]).open("a", encoding="utf-8") as output:
    output.write(sys.argv[5] + json.dumps(result, sort_keys=True) + "\n")
PY
    separator=','
done
printf ']\n' >> "$summary"
echo "High-signal candidate screen completed with unchanged Java sources"
