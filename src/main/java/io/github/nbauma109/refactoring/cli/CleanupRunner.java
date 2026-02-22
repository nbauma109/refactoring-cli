package io.github.nbauma109.refactoring.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Manifest;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTMatcher;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.fix.CleanUpRefactoring;
import org.eclipse.jdt.internal.corext.fix.CleanUpRegistry;
import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.fix.MapCleanUpOptions;
import org.eclipse.jdt.ui.cleanup.ICleanUp;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.TextEdit;
import org.osgi.framework.Bundle;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class CleanupRunner {

    private final Path projectRoot;
    private final Path profileFile;
    private final String sourceLevel;
    private final List<String> extraClasspath;

    public CleanupRunner(Path projectRoot, Path profileFile, String sourceLevel, List<String> extraClasspath) {
        this.projectRoot = projectRoot;
        this.profileFile = profileFile;
        this.sourceLevel = sourceLevel;
        this.extraClasspath = extraClasspath;
    }

    public List<Path> run() throws Exception {

        LoggingMonitor monitor = new LoggingMonitor();

        System.out.println("=== Starting cleanup ===");
        System.out.println("Project root: " + projectRoot);
        System.out.println("Profile file: " + profileFile);
        System.out.println("Source level: " + sourceLevel);

        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot wsRoot = workspace.getRoot();

        System.out.println("Creating temporary workspace project...");
        IProject project = wsRoot.getProject("refactoring-cli-project");
        if (project.exists()) {
            if (project.isOpen()) {
                project.close(monitor);
            }
            project.delete(true, true, monitor);
        }
        project.create(monitor);
        project.open(monitor);

        addJavaNature(project);
        System.out.println("Java nature enabled.");

        System.out.println("Detecting source folders...");
        Set<Path> sourceFolders = detectSourceFolders(projectRoot);
        System.out.println("Detected " + sourceFolders.size() + " source folders.");

        System.out.println("Linking source folders...");
        Map<IPath, IFolder> linkedFolders = linkSourceFolders(project, sourceFolders);

        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        System.out.println("Workspace refreshed.");

        IJavaProject javaProject = JavaCore.create(project);

        setEncoding(project);
        System.out.println("Encoding set to UTF-8.");

        System.out.println("Configuring classpath...");
        Set<String> requiredBundles = detectRequiredBundles(projectRoot);
        Set<Path> manifestLibraries = detectManifestLibraries(projectRoot);
        if (!requiredBundles.isEmpty()) {
            System.out.println("Detected " + requiredBundles.size() + " required OSGi bundles from MANIFEST.MF.");
        }
        if (!manifestLibraries.isEmpty()) {
            System.out.println("Detected " + manifestLibraries.size() + " local MANIFEST.MF library entries.");
        }
        configureClasspath(javaProject, linkedFolders, requiredBundles, manifestLibraries);

        System.out.println("Configuring compiler options...");
        configureCompilerOptions(javaProject);

        System.out.println("Collecting compilation units...");
        List<ICompilationUnit> units = collectCompilationUnits(javaProject);
        System.out.println("Found " + units.size() + " compilation units.");

        if (units.isEmpty()) {
            System.out.println("Nothing to clean.");
            return new ArrayList<>();
        }

        System.out.println("Loading cleanup settings...");
        Map<String, String> cleanupSettings = loadCleanupSettingsFromProfile(profileFile);
        cleanupSettings.put("cleanup.organize_imports", "false");

        System.out.println("Loading available cleanups...");
        CleanUpRegistry registry = JavaPlugin.getDefault().getCleanUpRegistry();
        ICleanUp[] cleanUps = registry.createCleanUps(null);
        System.out.println("Loaded " + cleanUps.length + " cleanup modules.");

        MapCleanUpOptions options = new MapCleanUpOptions(cleanupSettings);
        List<ICleanUp> enabledCleanUps = new ArrayList<>();

        for (ICleanUp cleanUp : cleanUps) {
            cleanUp.setOptions(options);
            String[] steps = cleanUp.getStepDescriptions();
            if (steps != null && steps.length > 0) {
                enabledCleanUps.add(cleanUp);
            }
        }
        System.out.println("Enabled " + enabledCleanUps.size() + " cleanup modules from profile.");

        List<Path> changed = new ArrayList<>();

        for (ICleanUp cleanUp : enabledCleanUps) {

            System.out.println("=== Running cleanup: " + cleanUp.getClass().getSimpleName() + " ===");

            for (ICompilationUnit unit : units) {

                System.out.println("Preparing refactoring for unit " + unit.getPath());

                CleanUpRefactoring refactoring = new CleanUpRefactoring();
                refactoring.addCompilationUnit(unit);
                refactoring.addCleanUp(cleanUp);

                System.out.println("Checking initial conditions...");
                RefactoringStatus initStatus = refactoring.checkInitialConditions(monitor);
                System.out.println("Initial condition status: " + initStatus);

                if (initStatus.hasFatalError()) {
                    continue;
                }

                System.out.println("Checking final conditions...");
                RefactoringStatus finalStatus = refactoring.checkFinalConditions(monitor);
                System.out.println("Final condition status: " + finalStatus);

                if (finalStatus.hasFatalError()) {
                    continue;
                }

                System.out.println("Creating change...");
                Change change = refactoring.createChange(monitor);
                if (change == null) {
                    continue;
                }

                System.out.println("Initializing change...");
                change.initializeValidationData(monitor);

                System.out.println("Validating change...");
                RefactoringStatus status = change.isValid(monitor);
                System.out.println("Validation result: " + status);
                if (status.hasFatalError()) {
                    System.err.println("Change validation failed.");
                    continue;
                }

                System.out.println("Collecting changed files...");
                List<Path> changedFiles = collectChangedFiles(change);

                System.out.println("Applying change to " + unit.getElementName());

                change.perform(monitor);

                project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

                for (Path p : changedFiles) {
                    if (!changed.contains(p)) {
                        changed.add(p);
                    }
                }

                if (!changedFiles.isEmpty()) {
                    javaProject.getJavaModel().refreshExternalArchives(
                            new IJavaElement[] { javaProject },
                            monitor
                    );
                }
            }
        }

        if (isOptionEnabled(cleanupSettings, "cleanup.instanceof")) {
            System.out.println("Running fallback transformation for cleanup.instanceof...");
            for (ICompilationUnit unit : units) {
                Path fallbackChanged = applyInstanceofPatternFallback(unit, monitor);
                if (fallbackChanged != null && !changed.contains(fallbackChanged)) {
                    changed.add(fallbackChanged);
                }
            }
        }

        ResourcesPlugin.getWorkspace().save(true, monitor);

        System.out.println("=== Cleanup complete ===");
        System.out.println("Modified " + changed.size() + " files.");

        return changed;
    }

    private void addJavaNature(IProject project) throws CoreException {
        IProjectDescription desc = project.getDescription();
        String[] natures = desc.getNatureIds();
        boolean hasJavaNature = false;
        for (String n : natures) {
            if (JavaCore.NATURE_ID.equals(n)) {
                hasJavaNature = true;
                break;
            }
        }
        if (!hasJavaNature) {
            String[] newNatures = new String[natures.length + 1];
            System.arraycopy(natures, 0, newNatures, 0, natures.length);
            newNatures[natures.length] = JavaCore.NATURE_ID;
            desc.setNatureIds(newNatures);
            project.setDescription(desc, null);
        }
    }

    private Set<Path> detectSourceFolders(Path root) throws IOException {
        Set<Path> sourceFolders = new LinkedHashSet<>();

        Files.walkFileTree(root, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.toString().endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }

                Path parent = file.getParent();
                if (parent == null) {
                    return FileVisitResult.CONTINUE;
                }

                for (Path src : sourceFolders) {
                    if (parent.startsWith(src)) {
                        return FileVisitResult.CONTINUE;
                    }
                }

                String packageName = readPackageName(file);
                Path sourceFolder = inferSourceFolder(file, packageName);

                if (sourceFolder != null && Files.isDirectory(sourceFolder)) {
                    sourceFolders.add(sourceFolder.normalize());
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                throw new UncheckedIOException(exc);
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (exc != null) {
                    throw new UncheckedIOException(exc);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return sourceFolders;
    }

    private String readPackageName(Path file) throws IOException {
        char[] source = Files.readString(file, StandardCharsets.UTF_8).toCharArray();
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);

        CompilationUnit unit = (CompilationUnit) parser.createAST(null);

        if (unit.getPackage() != null) {
            return unit.getPackage().getName().getFullyQualifiedName();
        }

        return "";
    }

    private Path inferSourceFolder(Path file, String packageName) {
        Path parent = file.getParent();
        if (parent == null) {
            return null;
        }

        if (packageName == null || packageName.isEmpty()) {
            return parent;
        }

        String[] segments = packageName.split("\\.");
        Path pkgPath = parent;

        for (int i = segments.length - 1; i >= 0; i--) {
            if (pkgPath == null || pkgPath.getFileName() == null || !pkgPath.getFileName().toString().equals(segments[i])) {
                break;
            }
			pkgPath = pkgPath.getParent();
        }

        return pkgPath;
    }

    private Map<IPath, IFolder> linkSourceFolders(IProject project, Set<Path> folders) throws CoreException {
        LinkedHashMap<IPath, IFolder> links = new LinkedHashMap<>();
        int index = 0;
        for (Path folder : folders) {
            String linkName = "src_" + index;
            index = index + 1;
            IFolder linked = project.getFolder(linkName);
            if (!linked.exists()) {
                linked.createLink(new org.eclipse.core.runtime.Path(folder.toString()), IResource.REPLACE, null);
            }
            links.put(new org.eclipse.core.runtime.Path(folder.toString()), linked);
        }
        return links;
    }

    private void configureClasspath(IJavaProject javaProject, Map<IPath, IFolder> linkedFolders, Set<String> requiredBundles,
            Set<Path> manifestLibraries)
            throws CoreException {
        List<IClasspathEntry> entries = new ArrayList<>();
        Set<String> seenLibraryPaths = new HashSet<>();

        for (IFolder folder : linkedFolders.values()) {
            entries.add(JavaCore.newSourceEntry(folder.getFullPath()));
        }

        entries.add(JavaCore.newContainerEntry(
                new org.eclipse.core.runtime.Path("org.eclipse.jdt.launching.JRE_CONTAINER")));

        for (Path lib : manifestLibraries) {
            String normalized = lib.normalize().toString();
            if (seenLibraryPaths.add(normalized)) {
                org.eclipse.core.runtime.Path path = new org.eclipse.core.runtime.Path(normalized);
                entries.add(JavaCore.newLibraryEntry(path, null, null));
            }
        }

        for (String bundleId : requiredBundles) {
            Path bundlePath = resolveBundlePath(bundleId);
            if (bundlePath == null) {
                continue;
            }

            String normalized = bundlePath.normalize().toString();
            if (seenLibraryPaths.add(normalized)) {
                org.eclipse.core.runtime.Path path = new org.eclipse.core.runtime.Path(normalized);
                entries.add(JavaCore.newLibraryEntry(path, null, null));
            }
        }

        for (String cp : extraClasspath) {
            org.eclipse.core.runtime.Path path = new org.eclipse.core.runtime.Path(cp);
            if (seenLibraryPaths.add(path.toOSString())) {
                entries.add(JavaCore.newLibraryEntry(path, null, null));
            }
        }

        javaProject.setRawClasspath(entries.toArray(new IClasspathEntry[0]), null);
    }

    private Set<String> detectRequiredBundles(Path root) throws IOException {
        Set<String> result = new LinkedHashSet<>();

        Files.walkFileTree(root, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!"MANIFEST.MF".equals(file.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }

                Path parent = file.getParent();
                if (parent == null || parent.getFileName() == null || !"META-INF".equals(parent.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }

                try (InputStream in = Files.newInputStream(file)) {
                    Manifest manifest = new Manifest(in);
                    String requireBundleHeader = manifest.getMainAttributes().getValue("Require-Bundle");
                    if (requireBundleHeader == null || requireBundleHeader.isBlank()) {
                        return FileVisitResult.CONTINUE;
                    }

                    List<String> clauses = splitManifestHeaderClauses(requireBundleHeader);
                    for (String clause : clauses) {
                        int semicolon = clause.indexOf(';');
                        String bundleId = semicolon >= 0 ? clause.substring(0, semicolon).trim() : clause.trim();
                        if (!bundleId.isEmpty()) {
                            result.add(bundleId);
                        }
                    }
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                throw new UncheckedIOException(exc);
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (exc != null) {
                    throw new UncheckedIOException(exc);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return result;
    }

    private Set<Path> detectManifestLibraries(Path root) throws IOException {
        Set<Path> result = new LinkedHashSet<>();

        Files.walkFileTree(root, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!"MANIFEST.MF".equals(file.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }

                Path parent = file.getParent();
                if (parent == null || parent.getFileName() == null || !"META-INF".equals(parent.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }

                Path bundleRoot = parent.getParent();
                if (bundleRoot == null) {
                    return FileVisitResult.CONTINUE;
                }

                try (InputStream in = Files.newInputStream(file)) {
                    Manifest manifest = new Manifest(in);
                    String bundleClassPath = manifest.getMainAttributes().getValue("Bundle-ClassPath");
                    if (bundleClassPath == null || bundleClassPath.isBlank()) {
                        return FileVisitResult.CONTINUE;
                    }

                    List<String> clauses = splitManifestHeaderClauses(bundleClassPath);
                    for (String clause : clauses) {
                        String entry = clause.trim();
                        if (entry.isEmpty() || ".".equals(entry)) {
                            continue;
                        }

                        Path resolved = bundleRoot.resolve(entry).normalize();
                        if (Files.exists(resolved)) {
                            result.add(resolved);
                        }
                    }
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                throw new UncheckedIOException(exc);
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (exc != null) {
                    throw new UncheckedIOException(exc);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return result;
    }

    private List<String> splitManifestHeaderClauses(String header) {
        List<String> clauses = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;

        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                current.append(c);
                continue;
            }

            if (c == ',' && !inQuote) {
                String clause = current.toString().trim();
                if (!clause.isEmpty()) {
                    clauses.add(clause);
                }
                current.setLength(0);
                continue;
            }

            current.append(c);
        }

        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            clauses.add(tail);
        }

        return clauses;
    }

    private Path resolveBundlePath(String bundleId) {
        try {
            Bundle bundle = Platform.getBundle(bundleId);
            if (bundle == null) {
                System.out.println("Could not resolve required bundle on running platform: " + bundleId);
                return null;
            }

            java.net.URL root = bundle.getEntry("/");
            if (root == null) {
                System.out.println("Bundle has no root entry: " + bundleId);
                return null;
            }

            java.net.URL localUrl = FileLocator.toFileURL(root);
            URI uri = localUrl.toURI();
            return Paths.get(uri);
        } catch (Exception e) {
            System.out.println("Failed to resolve required bundle path for " + bundleId + ": " + e.getMessage());
            return null;
        }
    }

    private void configureCompilerOptions(IJavaProject javaProject) {
        Map<String, String> options = javaProject.getOptions(false);
        options.put(JavaCore.COMPILER_SOURCE, sourceLevel);
        options.put(JavaCore.COMPILER_COMPLIANCE, sourceLevel);
        options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, sourceLevel);
        javaProject.setOptions(options);
    }

    private boolean isOptionEnabled(Map<String, String> cleanupSettings, String key) {
        String value = cleanupSettings.get(key);
        return value != null && Boolean.parseBoolean(value);
    }

    private void setEncoding(IProject project) throws CoreException {
        project.setDefaultCharset(StandardCharsets.UTF_8.name(), null);
    }

    private List<ICompilationUnit> collectCompilationUnits(IJavaProject javaProject) throws CoreException {
        List<ICompilationUnit> result = new ArrayList<>();
        IPackageFragmentRoot[] roots = javaProject.getPackageFragmentRoots();
        for (IPackageFragmentRoot root : roots) {
            if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                IJavaElement[] children = root.getChildren();
                for (IJavaElement el : children) {
                    if (el instanceof IPackageFragment pkg) {
                        ICompilationUnit[] units = pkg.getCompilationUnits();
                        Collections.addAll(result, units);
                    }
                }
            }
        }
        return result;
    }

    private Map<String, String> loadCleanupSettingsFromProfile(Path profile) throws IOException {
        Map<String, String> settings = new LinkedHashMap<>();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setIgnoringComments(true);
            factory.setIgnoringElementContentWhitespace(true);

            DocumentBuilder builder = factory.newDocumentBuilder();
            try (InputStream in = Files.newInputStream(profile)) {
                Document document = builder.parse(in);

                NodeList nodes = document.getElementsByTagName("setting");
                for (int i = 0; i < nodes.getLength(); i++) {
                    Node node = nodes.item(i);
                    if (node.getNodeType() == Node.ELEMENT_NODE) {
                        Element element = (Element) node;

                        String id = element.getAttribute("id");
                        String value = element.getAttribute("value");

                        if (id != null && !id.isEmpty()) {
                            settings.put(id, value);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse cleanup profile XML: " + profile, e);
        }

        return settings;
    }

    private List<Path> collectChangedFiles(Change change) throws CoreException {
        List<Path> result = new ArrayList<>();
        if (change instanceof CompositeChange composite) {
            Change[] children = composite.getChildren();
            for (Change child : children) {
                result.addAll(collectChangedFiles(child));
            }
        } else if (change instanceof TextFileChange tfc) {
            TextEdit edit = tfc.getEdit();
            if (hasEffectiveEdits(edit)) {
                IFile file = tfc.getFile();
                if (file != null && file.getLocation() != null) {
                    result.add(Paths.get(file.getLocation().toOSString()));
                }
            }
        }
        return result;
    }

    private Path applyInstanceofPatternFallback(ICompilationUnit unit, LoggingMonitor monitor) {
        try {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);
            parser.setSource(unit);

            CompilationUnit root = (CompilationUnit) parser.createAST(null);
            List<InstanceofPatternCandidate> candidates = collectInstanceofPatternCandidates(root);

            if (candidates.isEmpty()) {
                return null;
            }

            String source = unit.getSource();
            String updated = applyInstanceofPatternReplacements(source, candidates);

            if (updated.equals(source)) {
                return null;
            }

            unit.getBuffer().setContents(updated);
            unit.save(monitor, true);

            IResource resource = unit.getResource();
            if (resource instanceof IFile file && file.getLocation() != null) {
                return Paths.get(file.getLocation().toOSString());
            }
        } catch (CoreException e) {
            System.err.println("Fallback cleanup.instanceof failed for " + unit.getPath() + ": " + e.getMessage());
        }

        return null;
    }

    private List<InstanceofPatternCandidate> collectInstanceofPatternCandidates(CompilationUnit root) {
        List<InstanceofPatternCandidate> candidates = new ArrayList<>();

        root.accept(new ASTVisitor() {
            @Override
            public boolean visit(IfStatement ifStatement) {
                Expression condition = ifStatement.getExpression();
                if (!(condition instanceof InstanceofExpression instanceofExpression)) {
                    return true;
                }

                Type matchedType = instanceofExpression.getRightOperand();
                if (matchedType == null || matchedType.resolveBinding() == null) {
                    return true;
                }

                Expression leftOperand = instanceofExpression.getLeftOperand();
                if (!ASTNodes.isPassive(leftOperand)) {
                    return true;
                }

                if (!(ifStatement.getThenStatement() instanceof Block thenBlock)) {
                    return true;
                }

                List<CastExpression> casts = findMatchingCasts(thenBlock, leftOperand, matchedType);
                if (casts.isEmpty()) {
                    return true;
                }

                String variableName = buildUniquePatternName(thenBlock, matchedType);
                if (variableName == null || variableName.isBlank()) {
                    return true;
                }

                candidates.add(new InstanceofPatternCandidate(instanceofExpression, casts, variableName));
                return true;
            }
        });

        return candidates;
    }

    private List<CastExpression> findMatchingCasts(Block thenBlock, Expression leftOperand, Type matchedType) {
        List<CastExpression> matches = new ArrayList<>();
        ASTMatcher matcher = new ASTMatcher();

        thenBlock.accept(new ASTVisitor() {
            @Override
            public boolean visit(CastExpression castExpression) {
                Type castType = castExpression.getType();
                if (castType == null || castType.resolveBinding() == null) {
                    return true;
                }

                if (!Objects.equals(matchedType.resolveBinding(), castType.resolveBinding())) {
                    return true;
                }

                if (!leftOperand.subtreeMatch(matcher, castExpression.getExpression())) {
                    return true;
                }

                matches.add(castExpression);
                return true;
            }
        });

        return matches;
    }

    private String buildUniquePatternName(Block scope, Type type) {
        String typeName = type.resolveBinding() != null ? type.resolveBinding().getName() : type.toString();
        String base = toLowerCamelIdentifier(typeName);
        if (base == null || base.isBlank()) {
            base = "value";
        }

        Set<String> usedNames = new HashSet<>();
        scope.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                usedNames.add(node.getIdentifier());
                return true;
            }
        });

        if (!usedNames.contains(base)) {
            return base;
        }

        int suffix = 2;
        while (usedNames.contains(base + suffix)) {
            suffix = suffix + 1;
        }
        return base + suffix;
    }

    private String toLowerCamelIdentifier(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                cleaned.append(c);
            }
        }

        if (cleaned.length() == 0) {
            return null;
        }

        char first = cleaned.charAt(0);
        if (!Character.isJavaIdentifierStart(first)) {
            cleaned.insert(0, 'v');
        }

        String base = cleaned.toString();
        return Character.toLowerCase(base.charAt(0)) + base.substring(1);
    }

    private String applyInstanceofPatternReplacements(String source, List<InstanceofPatternCandidate> candidates) {
        List<TextReplacement> replacements = new ArrayList<>();

        for (InstanceofPatternCandidate candidate : candidates) {
            InstanceofExpression instanceOf = candidate.instanceofExpression();
            Expression left = instanceOf.getLeftOperand();
            Type right = instanceOf.getRightOperand();

            int leftStart = left.getStartPosition();
            int leftEnd = leftStart + left.getLength();
            int rightStart = right.getStartPosition();
            int rightEnd = rightStart + right.getLength();

            String leftText = source.substring(leftStart, leftEnd);
            String rightText = source.substring(rightStart, rightEnd);
            String conditionReplacement = leftText + " instanceof " + rightText + " " + candidate.variableName();

            replacements.add(new TextReplacement(
                    instanceOf.getStartPosition(),
                    instanceOf.getLength(),
                    conditionReplacement
            ));

            for (CastExpression castExpression : candidate.castExpressions()) {
                ASTNode replacementTarget = castExpression;
                if (castExpression.getParent() instanceof ParenthesizedExpression parenthesized
                        && parenthesized.getExpression() == castExpression) {
                    replacementTarget = parenthesized;
                }

                replacements.add(new TextReplacement(
                        replacementTarget.getStartPosition(),
                        replacementTarget.getLength(),
                        candidate.variableName()
                ));
            }
        }

        replacements.sort((a, b) -> Integer.compare(b.start(), a.start()));

        StringBuilder builder = new StringBuilder(source);
        int lastStart = Integer.MAX_VALUE;
        for (TextReplacement replacement : replacements) {
            if (replacement.start() + replacement.length() > lastStart) {
                continue;
            }
            builder.replace(
                    replacement.start(),
                    replacement.start() + replacement.length(),
                    replacement.text()
            );
            lastStart = replacement.start();
        }

        return builder.toString();
    }

    private record InstanceofPatternCandidate(
            InstanceofExpression instanceofExpression,
            List<CastExpression> castExpressions,
            String variableName
    ) {
    }

    private record TextReplacement(int start, int length, String text) {
    }

    private boolean hasEffectiveEdits(TextEdit edit) {
        if (edit == null) {
            return false;
        }

        if (!edit.hasChildren()) {
            return !(edit instanceof MultiTextEdit);
        }

        TextEdit[] children = edit.getChildren();
        for (TextEdit child : children) {
            if (hasEffectiveEdits(child)) {
                return true;
            }
        }

        return false;
    }

}
