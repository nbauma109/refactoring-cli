package io.github.nbauma109.refactoring.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jdt.internal.ui.fix.MapCleanUpOptions;
import org.eclipse.jdt.ui.cleanup.ICleanUp;

/**
 * Resolves Eclipse cleanup options from their declared steps, keeping
 * ambiguous combinations attributed to the cleanup module.
 */
final class CleanupAttribution {

    record Selection(String identifier, Map<String, String> stepOptions) {
    }

    private CleanupAttribution() {
    }

    static Selection identify(ICleanUp cleanUp, Map<String, String> settings) {
        String module = cleanUp.getClass().getSimpleName();
        String[] baseline = cleanUp.getStepDescriptions();
        if (baseline == null || baseline.length == 0) {
            return new Selection(module, Map.of());
        }
        List<String> controllingOptions = new ArrayList<>();
        Map<String, Set<String>> candidates = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                if (!entry.getKey().startsWith("cleanup.") || !"true".equals(entry.getValue())) {
                    continue;
                }
                Map<String, String> disabled = new LinkedHashMap<>(settings);
                disabled.put(entry.getKey(), "false");
                cleanUp.setOptions(new MapCleanUpOptions(disabled));
                String[] remaining = cleanUp.getStepDescriptions();
                if (!Arrays.equals(baseline, remaining)) {
                    controllingOptions.add(entry.getKey());
                    collectRemovedSteps(baseline, remaining, entry.getKey(), candidates);
                }
            }
        } finally {
            cleanUp.setOptions(new MapCleanUpOptions(settings));
        }
        Map<String, String> uniqueSteps = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : candidates.entrySet()) {
            String unique = resolveOption(entry.getValue());
            if (unique != null) {
                uniqueSteps.put(entry.getKey(), unique);
            }
        }
        String identifier = resolveOption(new LinkedHashSet<>(controllingOptions));
        return new Selection(identifier == null ? module : identifier, Map.copyOf(uniqueSteps));
    }

    private static String resolveOption(Set<String> controllingOptions) {
        Set<String> specificOptions = new LinkedHashSet<>(controllingOptions);
        if (specificOptions.contains("cleanup.add_missing_annotations")) {
            boolean hasSpecificOption = false;
            for (String option : specificOptions) {
                if (option.startsWith("cleanup.add_missing_")
                        && !"cleanup.add_missing_annotations".equals(option)) {
                    hasSpecificOption = true;
                    break;
                }
            }
            if (hasSpecificOption) {
                specificOptions.remove("cleanup.add_missing_annotations");
            }
        }
        if (specificOptions.contains("cleanup.system_property")) {
            for (String option : specificOptions) {
                if (option.startsWith("cleanup.system_property_")) {
                    specificOptions.remove("cleanup.system_property");
                    break;
                }
            }
        }
        return specificOptions.size() == 1 ? specificOptions.iterator().next() : null;
    }

    private static void collectRemovedSteps(String[] baseline, String[] remaining,
            String option, Map<String, Set<String>> candidates) {
        List<String> current = remaining == null ? List.of() : Arrays.asList(remaining);
        for (String step : baseline) {
            if (!current.contains(step)) {
                candidates.computeIfAbsent(step, key -> new LinkedHashSet<>()).add(option);
            }
        }
    }
}
