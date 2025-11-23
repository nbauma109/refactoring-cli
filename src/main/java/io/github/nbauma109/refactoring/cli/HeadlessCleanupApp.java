package io.github.nbauma109.refactoring.cli;

import org.eclipse.equinox.app.*;
import java.nio.file.*;
import java.util.*;

public class HeadlessCleanupApp implements IApplication {

    @Override
    public Object start(IApplicationContext context) throws Exception {
        String[] args = (String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS);

        if (args == null || args.length == 0) {
            System.err.println("Missing arguments. Usage:");
            System.err.println("  --source <level> --profile <file> <projectRoot> [--classpath <entries>]");
            return Integer.valueOf(1);
        }

        String sourceLevel = null;
        String profilePath = null;
        String projectRootPath = null;
        List<String> extraClasspath = new ArrayList<>();

        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            if ("--source".equals(arg) && i + 1 < args.length) {
                sourceLevel = args[++i];
            } else if ("--profile".equals(arg) && i + 1 < args.length) {
                profilePath = args[++i];
            } else if ("--classpath".equals(arg) && i + 1 < args.length) {
                String[] cpEntries = args[++i].split(System.getProperty("path.separator"));
                for (String entry : cpEntries) {
                    extraClasspath.add(entry);
                }
            } else {
                projectRootPath = arg;
            }
            i++;
        }

        if (sourceLevel == null || profilePath == null || projectRootPath == null) {
            System.err.println("Missing required parameters.");
            return Integer.valueOf(1);
        }

        Path projectRoot = Paths.get(projectRootPath);
        Path profileFile = Paths.get(profilePath);

        CleanupRunner runner =
                new CleanupRunner(projectRoot, profileFile, sourceLevel, extraClasspath);

        runner.run();

        return Integer.valueOf(0);
    }

    @Override
    public void stop() {
        // no-op
    }
}
