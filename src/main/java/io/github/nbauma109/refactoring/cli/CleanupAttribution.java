package io.github.nbauma109.refactoring.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jdt.internal.ui.fix.MapCleanUpOptions;
import org.eclipse.jdt.ui.cleanup.ICleanUp;

/**
 * Attributes a cleanup module to an exact option only when the option uniquely
 * controls the enabled cleanup steps.
 */
final class CleanupAttribution {

    private CleanupAttribution() {
    }

    static Optional<String> identify(ICleanUp cleanUp, Map<String, String> settings) {
        String[] baseline = cleanUp.getStepDescriptions();
        if (baseline == null || baseline.length == 0) {
            return Optional.empty();
        }
        List<String> controllingOptions = new ArrayList<>();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            if (!entry.getKey().startsWith("cleanup.") || !"true".equals(entry.getValue())) {
                continue;
            }
            Map<String, String> disabled = new LinkedHashMap<>(settings);
            disabled.put(entry.getKey(), "false");
            cleanUp.setOptions(new MapCleanUpOptions(disabled));
            if (!Arrays.equals(baseline, cleanUp.getStepDescriptions())) {
                controllingOptions.add(entry.getKey());
            }
        }
        cleanUp.setOptions(new MapCleanUpOptions(settings));
        return controllingOptions.size() == 1
                ? Optional.of(controllingOptions.get(0))
                : Optional.empty();
    }
}
