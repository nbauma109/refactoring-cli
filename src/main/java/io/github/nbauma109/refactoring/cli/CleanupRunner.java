package io.github.nbauma109.refactoring.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.fix.CleanUpRefactoring;
import org.eclipse.jdt.internal.corext.fix.CleanUpRegistry;
import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.fix.ImportsCleanUp;
import org.eclipse.jdt.internal.ui.fix.MapCleanUpOptions;
import org.eclipse.jdt.internal.ui.preferences.JavaPreferencesSettings;
import org.eclipse.jdt.ui.cleanup.ICleanUp;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.TextEdit;
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
        if (!project.exists()) {
            project.create(monitor);
        }
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
        configureClasspath(javaProject, linkedFolders);

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

        System.out.println("Loading available cleanups...");
        CleanUpRegistry registry = JavaPlugin.getDefault().getCleanUpRegistry();
        ICleanUp[] cleanUps = registry.createCleanUps(null);
        System.out.println("Loaded " + cleanUps.length + " cleanup modules.");

        MapCleanUpOptions options = new MapCleanUpOptions(cleanupSettings);

        for (ICleanUp cleanUp : cleanUps) {
            cleanUp.setOptions(options);
        }

        List<Path> changed = new ArrayList<>();

        for (ICleanUp cleanUp : cleanUps) {

            System.out.println("=== Running cleanup: " + cleanUp.getClass().getSimpleName() + " ===");

            for (ICompilationUnit unit : units) {

                if (cleanUp.getClass() == ImportsCleanUp.class) {
	            	if (cleanupSettings.getOrDefault("cleanup.organize_imports", "false").equals("true")) {
	            	    boolean modified = applyOrganizeImports(unit, monitor);
	            	    if (modified) {
	            	        System.out.println("[organize-imports] " + unit.getElementName());
	            	    }
	            	}
	            	continue;
                }

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

                System.out.println("Collecting changed files...");
                List<Path> changedFiles = collectChangedFiles(change);
                if (changedFiles.isEmpty()) {
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

    private void configureClasspath(IJavaProject javaProject, Map<IPath, IFolder> linkedFolders) throws CoreException {
        List<IClasspathEntry> entries = new ArrayList<>();

        for (IFolder folder : linkedFolders.values()) {
            entries.add(JavaCore.newSourceEntry(folder.getFullPath()));
        }

        entries.add(JavaCore.newContainerEntry(
                new org.eclipse.core.runtime.Path("org.eclipse.jdt.launching.JRE_CONTAINER")));

        for (String cp : extraClasspath) {
            org.eclipse.core.runtime.Path path = new org.eclipse.core.runtime.Path(cp);
            entries.add(JavaCore.newLibraryEntry(path, null, null));
        }

        javaProject.setRawClasspath(entries.toArray(new IClasspathEntry[0]), null);
    }

    private void configureCompilerOptions(IJavaProject javaProject) {
        Map<String, String> options = javaProject.getOptions(false);
        options.put(JavaCore.COMPILER_SOURCE, sourceLevel);
        options.put(JavaCore.COMPILER_COMPLIANCE, sourceLevel);
        options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, sourceLevel);
        javaProject.setOptions(options);
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
		    if (edit != null && edit.hasChildren()) {
		        IFile file = tfc.getFile();
		        result.add(Paths.get(file.getLocation().toOSString()));
		    }
		}
        return result;
    }

    private boolean applyOrganizeImports(ICompilationUnit unit, IProgressMonitor monitor) throws Exception {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(unit);
        parser.setResolveBindings(true);

        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

        CodeGenerationSettings settings = JavaPreferencesSettings.getCodeGenerationSettings(unit.getJavaProject());

        OrganizeImportsOperation op = new OrganizeImportsOperation(
                unit,
                ast,
                settings.importIgnoreLowercase,
                false,
                false,
                (openChoices, ranges) -> new TypeNameMatch[0] // no UI queries
        );

        TextEdit edit = op.createTextEdit(monitor);

        if (edit == null || (edit instanceof MultiTextEdit && edit.getChildrenSize() == 0)) {
            return false;
        }

        unit.applyTextEdit(edit, monitor);
        unit.save(monitor, true);
        return true;
    }
}
