#!/usr/bin/env bash
set -euo pipefail

: "${ECLIPSE_HOME:?The installed Eclipse runtime is required}"
: "${RUNNER_TEMP:?The runner temporary directory is required}"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
project="$RUNNER_TEMP/java25-unused-private-members"
source_file="$project/src/smoke/UnusedPrivateMemberCandidates.java"
workspace="$RUNNER_TEMP/java25-unused-private-workspace"
mkdir -p "$(dirname "$source_file")"
cp "$root/tests/java25/src/smoke/UnusedPrivateMemberCandidates.java" "$source_file"
original_hash="$(sha256sum "$source_file")"
javac --release 25 -d "$RUNNER_TEMP/java25-unused-private-classes" "$source_file"

for option in remove_unused_private_fields remove_unused_private_methods; do
    profile="$RUNNER_TEMP/java25-$option.xml"
    report="$RUNNER_TEMP/java25-$option-report.json"
    cat > "$profile" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<profiles version="2">
  <profile kind="CleanUpProfile" name="$option" version="2">
    <setting id="cleanup.remove_unused_private_members" value="true"/>
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
        > "$RUNNER_TEMP/java25-$option.log" 2>&1

    python3 - "$report" "$option" "$source_file" <<'PY'
import json
from pathlib import Path
import re
import sys

document = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
key = "cleanup." + sys.argv[2]
source = Path(sys.argv[3]).read_text(encoding="utf-8")
issues = document["issues"]
assert document["formatVersion"] == 1, document
assert len(issues) == 1, (key, issues)
assert issues[0]["cleanup"] == key, (key, issues)
assert issues[0]["file"] == "src/smoke/UnusedPrivateMemberCandidates.java", issues
assert 0 <= issues[0]["startOffset"] < issues[0]["endOffset"] <= len(source), issues
expected = "unusedCounter" if key.endswith("_fields") else "unusedDescription"
changed = source[issues[0]["startOffset"]:issues[0]["endOffset"]]
assert expected in changed, (key, changed, issues)
assert not re.search(r"\b(activeCounter|usedDescription)\b", changed), issues
print("PRIVATE_MEMBER_CANDIDATE", key, json.dumps(issues, indent=2))
PY
    test "$(sha256sum "$source_file")" = "$original_hash"
done
echo "Unused private member candidates verified without rewriting source"
