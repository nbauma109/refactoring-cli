#!/usr/bin/env bash
set -euo pipefail

: "${ECLIPSE_HOME:?Set ECLIPSE_HOME to the installed Eclipse distribution}"
: "${RUNNER_TEMP:?Set RUNNER_TEMP to the temporary directory}"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
project="$RUNNER_TEMP/java25-broad-complementary"
source_file="$project/src/smoke/BroadComplementaryCleanups.java"
workspace="$RUNNER_TEMP/java25-broad-complementary-workspace"
output="$RUNNER_TEMP/java25-broad-complementary-summary.json"
mkdir -p "$(dirname "$source_file")"
cp "$root/tests/java25/src/smoke/BroadComplementaryCleanups.java" "$source_file"
cp "$source_file" "$RUNNER_TEMP/java25-broad-complementary-original.java"
javac --release 25 -d "$RUNNER_TEMP/java25-broad-complementary-classes" "$source_file"

candidates=(
    pull_up_assignment
    extract_increment
    remove_unnecessary_suppress_warnings
    system_property_javaversion
    system_property_javaspecversion
    no_string_creation
    stringbuffer_to_stringbuilder
    use_string_is_blank
    remove_redundant_modifiers
    static_inner_class
    make_private_fields_final
    array_with_curly
    remove_unnecessary_array_creation
    add_all
    precompile_regex
    remove_redundant_type_arguments
    useless_return
    stringbuilder_for_local_vars
    pull_out_if_from_if_else
    reduce_indentation
    ternary_operator
    remove_unnecessary_nls_tags
    remove_redundant_semicolons
    stringconcat_to_textblock
)

printf '[' > "$output"
separator=''
for option in "${candidates[@]}"; do
    profile="$RUNNER_TEMP/java25-broad-$option.xml"
    report="$RUNNER_TEMP/java25-broad-$option.json"
    cat > "$profile" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<profiles version="2">
  <profile kind="CleanUpProfile" name="broad-$option" version="2">
    <setting id="cleanup.$option" value="true"/>
    <setting id="cleanup.system_property" value="true"/>
  </profile>
</profiles>
XML
    xvfb-run -a "$ECLIPSE_HOME/eclipse" \
        -nosplash -clean -consoleLog \
        -data "$workspace" \
        -application io.github.nbauma109.refactoring.cli.app \
        --source 25 --profile "$profile" \
        "$project" --source-root "$project/src" --report "$report" \
        > "$RUNNER_TEMP/java25-broad-$option.log" 2>&1
    cmp "$RUNNER_TEMP/java25-broad-complementary-original.java" "$source_file"
    python3 - "$report" "$option" "$source_file" "$output" "$separator" <<'PY'
import json
from pathlib import Path
import sys

report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
option = "cleanup." + sys.argv[2]
source = Path(sys.argv[3]).read_text(encoding="utf-8")
issues = report["issues"]
assert report["formatVersion"] == 1
for issue in issues:
    assert issue["file"] == "src/smoke/BroadComplementaryCleanups.java", issue
    assert 0 <= issue["startOffset"] <= issue["endOffset"] <= len(source), issue
    assert issue["line"] > 0 and issue["column"] > 0, issue
identified = sum(issue["cleanup"] == option for issue in issues)
summary = {
    "option": option,
    "total": len(issues),
    "identified": identified,
    "identifiers": sorted({issue["cleanup"] for issue in issues}),
    "lines": sorted({issue["line"] for issue in issues}),
}
print("CANDIDATE", json.dumps(summary, sort_keys=True))
with Path(sys.argv[4]).open("a", encoding="utf-8") as output:
    output.write(sys.argv[5] + json.dumps(summary))
PY
    separator=','
done
printf ']\n' >> "$output"
echo "Candidate cleanup exploration completed without changing original source files."
