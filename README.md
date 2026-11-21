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

The output is UTF-8 JSON with `formatVersion: 1` and an `issues` array. Each issue contains `file` (project-relative source path), `line`, `column`, `cleanup` (Eclipse cleanup class), `message` (enabled cleanup step descriptions), `startOffset` and `endOffset` (zero-based UTF-16 offsets in the original file). Existing cleanup execution without `--report` retains its original modifying behaviour.

For safety, the report-only mode **rejects** the custom `cleanup.instanceof` fallback if it is enabled: that fallback still applies source edits outside the standard Eclipse refactoring `Change` pipeline. Disable that option in the reporting profile until a non-mutating preview is available. Report-only mode also refuses invalid source roots and edits outside the project. Reported cleanup class names are not yet exact individual cleanup option identifiers when a module controls multiple transformations.

The external Java 25 and Eclipse installation remain necessary. The [static-analysis-maven-plugin](https://github.com/nbauma109/static-analysis-maven-plugin) will consume this JSON format using a separate process.
