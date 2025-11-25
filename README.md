This is a command line interface (CLI) to execute Eclipse Source Clean-Ups  (equivalent of right-click menu `Source -> Clean Up`).

Quick start :
  * Build with `mvn package`
  * Copy the generated jar to `dropins` folder of Eclipse
  * In Eclipse, export a profile file from `Window -> Preferences` and then `Java -> Code Style -> Clean Up` and save it to `C:\path\to\source_cleanup_profile.xml`
  * Run `cmd` and execute command `eclipsec -nosplash -clean -data C:\path\to\.refactoring-workspace -application io.github.nbauma109.refactoring.cli.app --source 21 --profile C:\path\to\source_cleanup_profile.xml C:\path\to\codebase_to_cleanup\`
