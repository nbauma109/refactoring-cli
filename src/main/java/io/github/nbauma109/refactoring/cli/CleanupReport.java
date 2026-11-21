package io.github.nbauma109.refactoring.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.eclipse.core.resources.IFile;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.ltk.core.refactoring.TextEditChangeGroup;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.TextEdit;

/**
 * Serializes the Eclipse cleanup edits against their original source locations.
 */
public final class CleanupReport {

    private CleanupReport() {
    }

    private record EditDiagnostic(TextEdit edit, Diagnostic diagnostic) {
    }

    public record Diagnostic(String file, int line, int column, String cleanup, String module,
            String message, int startOffset, int endOffset) {
    }

    public static void collect(Change change, Path root, CleanupAttribution.Selection attribution,
            String module, String message, List<Diagnostic> diagnostics) throws IOException {
        if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) {
                collect(child, root, attribution, module, message, diagnostics);
            }
        } else if (change instanceof TextFileChange textChange) {
            collectTextEdits(textChange, root, attribution, module, message, diagnostics);
        }
    }

    private static void collectTextEdits(TextFileChange change, Path root,
            CleanupAttribution.Selection attribution, String module, String message,
            List<Diagnostic> diagnostics) throws IOException {
        IFile resource = change.getFile();
        TextEdit edit = change.getEdit();
        if (resource == null || resource.getLocation() == null || edit == null) {
            return;
        }
        Path project = root.toAbsolutePath().normalize();
        Path path = Path.of(resource.getLocation().toOSString()).toAbsolutePath().normalize();
        if (!path.startsWith(project)) {
            throw new IOException("Cleanup edit is outside the project root: " + path);
        }
        String source = Files.readString(path, StandardCharsets.UTF_8);
        String relative = project.relativize(path).toString().replace('\\', '/');
        List<EditDiagnostic> changes = new ArrayList<>();
        TextEditChangeGroup[] groups = change.getTextEditChangeGroups();
        collectLeaves(edit, groups, source, relative, attribution, module, message, changes);
        discardSupplementaryWhitespaceEdits(changes, source, module);
        collectOpportunities(changes, groups, diagnostics);
    }

    private static void collectLeaves(TextEdit edit, TextEditChangeGroup[] groups, String source,
            String file, CleanupAttribution.Selection attribution, String module, String message,
            List<EditDiagnostic> diagnostics) throws IOException {
        if (edit.hasChildren()) {
            for (TextEdit child : edit.getChildren()) {
                collectLeaves(child, groups, source, file, attribution, module, message, diagnostics);
            }
            return;
        }
        if (edit instanceof MultiTextEdit) {
            return;
        }
        int offset = edit.getOffset();
        int length = edit.getLength();
        if (offset < 0 || length < 0 || offset > source.length() - length) {
            throw new IOException("Cleanup edit has an invalid source range in " + file);
        }
        int[] location = sourceLocation(source, offset);
        String identifier = identifyOption(edit, groups, attribution, module, source);
        Diagnostic issue = new Diagnostic(file, location[0], location[1],
                identifier, module, optionDescription(identifier, message), offset, offset + length);
        diagnostics.add(new EditDiagnostic(edit, issue));
    }



    private static void discardSupplementaryWhitespaceEdits(List<EditDiagnostic> changes,
            String source, String module) {
        if (!"ConstantsForSystemPropertyCleanUpCore".equals(module) || changes.size() < 2) {
            return;
        }
        boolean hasSourceChange = false;
        for (EditDiagnostic candidate : changes) {
            if (!isSupplementaryWhitespaceEdit(candidate.edit(), source)) {
                hasSourceChange = true;
                break;
            }
        }
        if (hasSourceChange) {
            changes.removeIf(candidate -> isSupplementaryWhitespaceEdit(candidate.edit(), source));
        }
    }

    private static boolean isSupplementaryWhitespaceEdit(TextEdit edit, String source) {
        int start = edit.getOffset();
        int end = start + edit.getLength();
        return start >= 0 && end <= source.length()
                && source.substring(start, end).isBlank();
    }

    private static void collectOpportunities(List<EditDiagnostic> changes, TextEditChangeGroup[] groups,
            List<Diagnostic> diagnostics) {
        Set<TextEdit> grouped = new HashSet<>();
        if (groups != null) {
            for (TextEditChangeGroup group : groups) {
                List<EditDiagnostic> members = new ArrayList<>();
                for (EditDiagnostic change : changes) {
                    if (!grouped.contains(change.edit()) && belongsToGroup(change.edit(), group)) {
                        members.add(change);
                    }
                }
                if (members.size() > 1 && sameCleanup(members)) {
                    diagnostics.add(mergeEdits(members));
                    for (EditDiagnostic member : members) {
                        grouped.add(member.edit());
                    }
                }
            }
        }
        for (EditDiagnostic change : changes) {
            if (!grouped.contains(change.edit())) {
                diagnostics.add(change.diagnostic());
            }
        }
    }

    private static boolean belongsToGroup(TextEdit edit, TextEditChangeGroup group) {
        for (TextEdit groupedEdit : group.getTextEdits()) {
            if (containsEdit(groupedEdit, edit)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameCleanup(List<EditDiagnostic> changes) {
        Diagnostic first = changes.get(0).diagnostic();
        for (EditDiagnostic change : changes) {
            Diagnostic issue = change.diagnostic();
            if (!first.cleanup().equals(issue.cleanup()) || !first.module().equals(issue.module())) {
                return false;
            }
        }
        return true;
    }

    private static Diagnostic mergeEdits(List<EditDiagnostic> changes) {
        Diagnostic first = changes.get(0).diagnostic();
        int lastOffset = first.endOffset();
        for (EditDiagnostic change : changes) {
            Diagnostic issue = change.diagnostic();
            if (issue.startOffset() < first.startOffset()) {
                first = issue;
            }
            lastOffset = Math.max(lastOffset, issue.endOffset());
        }
        return new Diagnostic(first.file(), first.line(), first.column(), first.cleanup(),
                first.module(), first.message(), first.startOffset(), lastOffset);
    }

    private static String identifyOption(TextEdit leaf, TextEditChangeGroup[] groups,
            CleanupAttribution.Selection attribution, String module, String source) {
        if ("ConstantsForSystemPropertyCleanUpCore".equals(module)) {
            String identifier = systemPropertyIdentifier(leaf, source);
            if (identifier != null) {
                return identifier;
            }
        }
        if (groups == null || attribution.stepOptions().isEmpty()) {
            return attribution.identifier();
        }
        Set<String> matches = new HashSet<>();
        for (TextEditChangeGroup group : groups) {
            String option = attribution.stepOptions().get(group.getName());
            if (option == null) {
                continue;
            }
            for (TextEdit groupedEdit : group.getTextEdits()) {
                if (containsEdit(groupedEdit, leaf)) {
                    matches.add(option);
                }
            }
        }
        return matches.size() == 1
                ? matches.iterator().next()
                : attribution.identifier();
    }

    private static String systemPropertyIdentifier(TextEdit edit, String source) {
        if (edit.getLength() == 0) {
            return null;
        }
        String original = source.substring(edit.getOffset(), edit.getOffset() + edit.getLength());
        if (original.contains("Boolean.parseBoolean(") && original.contains("System.getProperty(")) {
            return "cleanup.system_property_boolean";
        }
        if (original.contains("\"file.separator\"")) {
            return "cleanup.system_property_file_separator";
        }
        if (original.contains("\"path.separator\"")) {
            return "cleanup.system_property_path_separator";
        }
        if (original.contains("\"line.separator\"")) {
            return "cleanup.system_property_line_separator";
        }
        if (original.contains("\"file.encoding\"")) {
            return "cleanup.system_property_file_encoding";
        }
        return null;
    }

    private static String optionDescription(String identifier, String message) {
        String prefix = "cleanup.system_property_";
        if (!identifier.startsWith(prefix)) {
            return message;
        }
        String property = identifier.substring(prefix.length()).toUpperCase(Locale.ROOT);
        if ("BOOLEAN".equals(property)) {
            property = "BOOLEAN_PROPERTY";
        }
        for (String description : message.split("; ")) {
            if (description.endsWith("'" + property + "'")) {
                return description;
            }
        }
        return message;
    }

    private static boolean containsEdit(TextEdit parent, TextEdit leaf) {
        for (TextEdit current = leaf; current != null; current = current.getParent()) {
            if (current == parent) {
                return true;
            }
        }
        return false;
    }

    private static int[] sourceLocation(String source, int offset) {
        int line = 1;
        int column = 1;
        char previous = 0;
        for (int index = 0; index < offset; index++) {
            char current = source.charAt(index);
            if (current == '\r') {
                line++;
                column = 1;
            } else if (current == '\n') {
                if (previous != '\r') {
                    line++;
                }
                column = 1;
            } else {
                column++;
            }
            previous = current;
        }
        return new int[] {line, column};
    }

    public static void write(Path output, List<Diagnostic> diagnostics) throws IOException {
        List<Diagnostic> ordered = new ArrayList<>(diagnostics);
        ordered.sort(Comparator.comparing(Diagnostic::file)
                .thenComparingInt(Diagnostic::startOffset)
                .thenComparingInt(Diagnostic::endOffset)
                .thenComparing(Diagnostic::cleanup)
                .thenComparing(Diagnostic::module)
                .thenComparing(Diagnostic::message));
        Set<Diagnostic> seen = new HashSet<>();
        boolean first = true;
        StringBuilder json = new StringBuilder("{\"formatVersion\":1,\"issues\":[");
        for (Diagnostic issue : ordered) {
            if (!seen.add(issue)) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"file\":").append(quote(issue.file()))
                    .append(",\"line\":").append(issue.line())
                    .append(",\"column\":").append(issue.column())
                    .append(",\"cleanup\":").append(quote(issue.cleanup()))
                    .append(",\"module\":").append(quote(issue.module()))
                    .append(",\"message\":").append(quote(issue.message()))
                    .append(",\"startOffset\":").append(issue.startOffset())
                    .append(",\"endOffset\":").append(issue.endOffset()).append('}');
        }
        json.append("]}");
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(absolute, json.toString(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + seen.size() + " cleanup diagnostic(s) to " + absolute);
    }

    private static String quote(String value) {
        StringBuilder json = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                json.append('\\').append(character);
            } else if (character == '\n') {
                json.append("\\n");
            } else if (character == '\r') {
                json.append("\\r");
            } else if (character == '\t') {
                json.append("\\t");
            } else if (character < 0x20) {
                json.append(String.format("\\u%04x", (int) character));
            } else {
                json.append(character);
            }
        }
        return json.append('"').toString();
    }
}
