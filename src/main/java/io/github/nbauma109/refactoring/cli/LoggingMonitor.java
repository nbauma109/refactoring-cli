package io.github.nbauma109.refactoring.cli;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.ProgressMonitorWrapper;

class LoggingMonitor extends ProgressMonitorWrapper {

    private int totalWork;
    private int completedWork;
    private static final int BAR_WIDTH = 40;
    private boolean hasShownIntermediate;
    private boolean hasPrintedFinal;

    public LoggingMonitor() {
        super(new NullProgressMonitor());
    }

    @Override
    public void beginTask(String name, int totalWork) {
        this.totalWork = totalWork;
        this.completedWork = 0;
        this.hasShownIntermediate = false;
        this.hasPrintedFinal = false;
        System.out.println("BEGIN: " + name + " (" + totalWork + ")");
    }

    @Override
    public void worked(int work) {
        if (work <= 0 || this.totalWork <= 0) {
            return;
        }
        this.completedWork = Math.min(this.completedWork + work, this.totalWork);
        renderProgressBar();
    }

    @Override
    public void done() {
        this.completedWork = this.totalWork;
        renderProgressBar();
        if (this.hasShownIntermediate && !this.hasPrintedFinal) {
            System.out.println();
            this.hasPrintedFinal = true;
        }
        System.out.println("DONE");
    }

    private void renderProgressBar() {
        double progress = (double) this.completedWork / (double) this.totalWork;
        int percent = (int) Math.round(progress * 100.0);

        if (percent == 0) {
            return;
        }

        if (percent == 100 && !this.hasShownIntermediate) {
            return;
        }

        if (percent > 0 && percent < 100) {
            this.hasShownIntermediate = true;
        }

        int filled = (int) Math.round(progress * BAR_WIDTH);

        StringBuilder bar = new StringBuilder();
        bar.append('\r');
        bar.append('[');

        for (int i = 0; i < BAR_WIDTH; i = i + 1) {
            bar.append(i < filled ? '#' : '.');
        }

        bar.append(' ');
        bar.append(percent);
        bar.append('%');
        bar.append(']');

        System.out.print(bar.toString());

        if (percent == 100 && !this.hasPrintedFinal) {
            System.out.println();
            this.hasPrintedFinal = true;
        }
    }
}
