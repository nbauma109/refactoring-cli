package io.github.nbauma109.refactoring.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
        collectLeaves(edit, change.getTextEditChangeGroups(), source, relative,
                attribution, module, message, diagnostics);
    }

    private static void collectLeaves(TextEdit edit, TextEditChangeGroup[] groups, String source,
            String file, CleanupAttribution.Selection attribution, String module, String message,
            List<Diagnostic> diagnostics) throws IOException {
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
        diagnostics.add(new Diagnostic(file, location[0], location[1],
                identifyOption(edit, groups, attribution), module, message, offset, offset + length));
    }


    private static String identifyOption(TextEdit leaf, TextEditChangeGroup[] groups,
            CleanupAttribution.Selection attribution) {
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
