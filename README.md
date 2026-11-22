This is a command line interface (CLI) to execute Eclipse Source Clean-Ups  (equivalent of right-click menu `Source -> Clean Up`).

Quick start :
  * Build with `mvn package` or download from releases
  * Copy `io.github.nbauma109.refactoring.cli-x.y.z.jar` to `dropins` folder of Eclipse
  * In Eclipse, export a profile file from `Window -> Preferences` and then `Java -> Code Style -> Clean Up` and save it to `C:\path\to\source_cleanup_profile.xml`
  * Run `cmd` and execute command
```
eclipsec -nosplash -clean -data C:\path\to\.refactoring-workspace -application io.github.nbauma109.refactoring.cli.app --source 25 --profile C:\path\to\source_cleanup_profile.xml C:\path\to\codebase_to_cleanup\
```

## Report-only cleanup analysis

The Eclipse application can report changes without modifying Java source files:

```sh
eclipsec -nosplash -clean -data /tmp/eclipse-cleanup-workspace \
  -application io.github.nbauma109.refactoring.cli.app \
  --source 25 --profile /path/to/cleanup-profile.xml \
  --source-root /path/to/project/src/main/java \
  --report /path/to/cleanup-diagnostics.json \
  /path/to/project
```

The output is UTF-8 JSON with `formatVersion: 1` and an `issues` array. Each issue contains `file` (project-relative source path), `line`, `column`, `cleanup` (exact cleanup option when uniquely identifiable; otherwise the module name), `module` (Eclipse cleanup implementation class), `message` (enabled cleanup step descriptions), `startOffset` and `endOffset` (zero-based UTF-16 offsets in the original file). Existing cleanup execution without `--report` retains its original modifying behaviour.

For safety, the report-only mode **rejects** the custom `cleanup.instanceof` fallback if it is enabled: that fallback still applies source edits outside the standard Eclipse refactoring `Change` pipeline. Disable that option in the reporting profile until a non-mutating preview is available. Report-only mode also refuses invalid source roots and edits outside the project. Reported cleanup class names are not yet exact individual cleanup option identifiers when a module controls multiple transformations.

The external Java 25 and Eclipse installation remain necessary. The [static-analysis-maven-plugin](https://github.com/nbauma109/static-analysis-maven-plugin) will consume this JSON format using a separate process.

The report-only analyzer also inspects Eclipse's named text-edit groups. When a group maps uniquely to a declared cleanup step and an enabled cleanup option, the individual edit is labeled with that exact option even if its module enables several cleanups. Unmatched or overlapping groups retain the module identifier. This is a conservative attribution mechanism rather than a guarantee that every Eclipse cleanup exposes edit-level categories.

The diagnostic writer groups named text edits belonging to one Eclipse refactoring opportunity before serializing the report. For example, a try-with-resources conversion that moves the resource, inserts it in the try declaration, and removes a finally block is one warning, not a warning per text edit. The source range of that warning spans the original edits. Parent settings such as `cleanup.add_missing_annotations` must be enabled with their specific child options; the reporter attributes the resulting step to the specific option when it is unambiguous.

## Broad complementary cleanup integration

The Java 25 integration suite verifies nine diverse Eclipse cleanup options with real
report-only transformations: assignment extraction, increment extraction, runtime and
specification version property accessors, `String.isBlank()` modernization, redundant
modifier removal, array initializer simplification, collection `addAll` conversion
and indentation reduction. The existing module-import integration separately verifies
the tenth new option. Every verified opportunity must have a concrete option identifier
and leave source files unchanged.

The broader [candidate test](tests/java25/test-complementary-candidates.sh) can also probe
24 options (including known Sonar overlap candidates) by running
`ECLIPSE_PROBE_ALL=true bash tests/java25/test-complementary-candidates.sh` with a
compatible Eclipse runtime and `RUNNER_TEMP` set. CI runs only the verified option
set by default. The resulting JSON summary lists exact identifiers and source lines,
allowing us to evaluate future cleanup batches without enabling unverified rules.

## Next high-signal Eclipse cleanup candidates

The Java 25 integration workflow separately screens three potential complementary
refactorings: precompilation of a reused regular expression, conversion of an inner
class that does not use its enclosing instance to a static nested class, and
removal of a stale `@SuppressWarnings` annotation. These remain **experimental**;
none is automatically included in a recommended profile. Their independent reports
show the exact Eclipse cleanup identifier, source line and number of findings.
The screening checks source integrity and compilation but does not assume every
candidate is distinct from SonarJava until combined-engine comparisons pass.
