package io.github.absketches.plugin.concreteclazz;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static io.github.absketches.plugin.concreteclazz.ClassFileUtils.formatKey;
import static io.github.absketches.plugin.concreteclazz.ClassFileUtils.formatResultMap;
import static io.github.absketches.plugin.concreteclazz.ClassFileUtils.isConcrete;
import static io.github.absketches.plugin.concreteclazz.ClassFileUtils.isSubclassOfBase;
import static io.github.absketches.plugin.concreteclazz.ClassFileUtils.mergeJson;
import static io.github.absketches.plugin.concreteclazz.ClassFileUtils.parseBaseClasses;
import static io.github.absketches.plugin.concreteclazz.ClassFileUtils.readAllPropertiesFromJarDir;
import static io.github.absketches.plugin.concreteclazz.ClassFileUtils.toDotted;

/**
 * Generates META-INF/io/github/absketches/plugin/services.index (module + dependencies) containing all concrete subclasses of the configured baseClass(es).
 * Merges into reflect-config.json to make applications GraalVM Native Image compatible.
 * Uses precompiled indexes from dependencies if they exist.
 * Stores subclasses in memory and checks to avoid re-walking super chains.
 * Skips writing the index if content didn't change.
 * Bails early if the base type isn't present on the classpath.
 * Easy to locate all implementations of a base class across dependencies and consumers.
 */
@Mojo(
    name = "generate",
    defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    threadSafe = true,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME
)
public final class CodegenConcreteClassPlugin extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "codegenConcreteClass.verbose", defaultValue = "false")
    private boolean verbose;

    /**
     * The base Classes can be set via -DcodegenConcreteClass.baseClasses=org.nanonative.nano.core.model.Service,...
     */
    @Parameter(property = "codegenConcreteClass.baseClasses", defaultValue = "org.nanonative.nano.core.model.Service")
    private String baseClasses;

    @Parameter(property = "codegenConcreteClass.outputFile", defaultValue = "services.properties")
    private String outputFile;

    @Parameter(property = "codegenConcreteClass.usePrecompiledLists", defaultValue = "true")
    private boolean usePrecompiledLists;

    /**
     * Enable/disable generating reflect-config.json - this can help with using reflection in Native images
     */
    @Parameter(property = "codegenConcreteClass.generateReflectConfig", defaultValue = "true")
    private boolean generateReflectConfig;

    static final String outputDir = "META-INF/io/github/absketches/plugin/";

    @Override
    public void execute() throws MojoExecutionException {
        final Map<String, ClassHeader> headers = new HashMap<>(); // Headers for each class
        final Map<String, Set<String>> precompiledMap = new LinkedHashMap<>(); // precomputed impls per base
        final Map<String, Set<String>> result = new LinkedHashMap<>();

        try {
            final Path classesDir = Path.of(project.getBuild().getOutputDirectory());
            if (!Files.isDirectory(classesDir)) {
                log("[codegen-svc-list] No classes dir (skipping): " + classesDir, 'I');
                return;
            }

            // Build allowed base types
            final List<String> requestedClasses = parseBaseClasses(baseClasses);

            // Scan own classes
            scanDirectory(classesDir, headers);

            // Scan dependencies (use precomputed properties when available) - or always scan using property usePrecompiled=false
            for (Artifact artifact : project.getArtifacts()) {
                processArtifact(artifact, headers, precompiledMap, requestedClasses);
            }
            log("[codegen-svc-list] headers size = " + headers.size(), 'I');

            // For each configured base type, collect implementations
            for (String base : requestedClasses) {
                final Map<String, Boolean> cache = new HashMap<>(); // Cache already iterated paths
                gatherConcreteClasses(base, headers, result, cache, precompiledMap);
            }

            writeProperties(classesDir, result);
            if (generateReflectConfig) {
                writeReflectConfig(result.values().stream().flatMap(Set::stream).collect(Collectors.toCollection(LinkedHashSet::new)), classesDir);
            } else {
                log("[codegen-svc-list] reflect-config.json generation disabled", 'I');
            }
        } catch (Exception ex) {
            log("Exception occurred: " + ex, 'E');
            throw new MojoExecutionException("codegen-svc-list failed", ex);
        }
    }

    private void scanDirectory(final Path root, final Map<String, ClassHeader> out) throws IOException {
        try (var stream = Files.walk(root)) {
            var it = stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".class"))
                .iterator();
            while (it.hasNext()) {
                Path p = it.next();
                String internal = root.relativize(p).toString().replace('\\', '/');
                try (InputStream in = Files.newInputStream(p)) {
                    out.put(formatKey(internal), ClassHeader.read(in));
                }
            }
        }
    }

    private void processArtifact(Artifact artifact, final Map<String, ClassHeader> headers, final Map<String, Set<String>> precompiledMap, final List<String> requestedClasses) {
        File jar = artifact.getFile();
        if (null != jar && jar.isFile() && "jar".equalsIgnoreCase(artifact.getType())) {
            try {
                prepareToScanJar(jar, headers, precompiledMap, requestedClasses);
            } catch (IOException ioe) {
                log("[codegen-svc-list] Jar scan failed for " + artifact + " (" + jar + "): " + ioe, 'E');
            }
        }
    }

    private void prepareToScanJar(final File jar, final Map<String, ClassHeader> out, final Map<String, Set<String>> precomputed, final List<String> allowedBases) throws IOException {
        try (JarFile jf = new JarFile(jar)) {
            if (usePrecompiledLists && null != allowedBases && !allowedBases.isEmpty()) {
                if (readAllPropertiesFromJarDir(jf, outputDir, precomputed, new HashSet<>(allowedBases))) {
                    log("[codegen-svc-list] using precomputed properties from " + jar.getName(), 'I');
                    return;
                }
                log("[codegen-svc-list] precomputed files missing entries for configured bases, will scan classes...", 'I');
            }

            // Either no properties or incomplete -> scan classes into headers
            scanHeadersInJar(jf, jar.getName(), out);
        }
    }

    private void scanHeadersInJar(final JarFile jf, final String name, final Map<String, ClassHeader> out) throws IOException {
        log("[codegen-svc-list] Scanning classes in " + name, 'I');
        Enumeration<JarEntry> en = jf.entries();
        while (en.hasMoreElements()) {
            JarEntry e = en.nextElement();
            String classFileName = e.getName();
            if (!classFileName.endsWith(".class"))
                continue;
            try (InputStream in = jf.getInputStream(e)) {
                out.putIfAbsent(formatKey(classFileName), ClassHeader.read(in));
            }
        }
    }

    private void gatherConcreteClasses(final String base, final Map<String, ClassHeader> headers, final Map<String, Set<String>> result, final Map<String, Boolean> cache, final Map<String, Set<String>> precompiledMap) {

        Set<String> services = new TreeSet<>(precompiledMap.getOrDefault(base, Set.of()));

        for (var e : headers.entrySet()) {
            String className = e.getKey();
            ClassHeader header = e.getValue();
            if (!isConcrete(header))
                continue;

            if (isSubclassOfBase(className, headers, cache, base)) {
                services.add(className);
            }
        }

        log("[codegen-svc-list] Implementations found for " + toDotted(base) + " = " + services.size(), 'I');
        result.put(base, services);
    }

    // Write results as a .properties file: key = base class (dotted), value = comma-separated implementations
    private void writeProperties(final Path classesDir, final Map<String, Set<String>> resultMap) throws IOException {
        final Path outputPath = classesDir.resolve(outputDir + outputFile);
        final Path parent = outputPath.getParent();
        if (null == parent)
            return;

        Files.createDirectories(parent);
        String newContent = formatResultMap(resultMap);
        String oldContent = Files.exists(outputPath) ? Files.readString(outputPath, StandardCharsets.UTF_8) : null;
        if (newContent.equals(oldContent)) {
            log("[codegen-svc-list] Unchanged - skipping", 'I');
            return;
        }

        final Path tmp = Files.createTempFile(parent, "services", ".tmp");
        try {
            Files.writeString(tmp, newContent, StandardCharsets.UTF_8);
            log("[codegen-svc-list] Copying tmp file to actual destination", 'I');
            Files.move(tmp, outputPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log("[codegen-svc-list] Wrote properties for base types = " + resultMap.size(), 'I');
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void writeReflectConfig(final Set<String> classNames, final Path classesDir) throws IOException {
        if (null == classNames || classNames.isEmpty()) {
            log("[codegen-svc-list] Nothing to write", 'I');
            return;
        }
        final Path configOutput = classesDir
            .resolve("META-INF/native-image")
            .resolve(project.getGroupId())
            .resolve(project.getArtifactId())
            .resolve("reflect-config.json");

        Files.createDirectories(configOutput.getParent());

        final String existing = Files.exists(configOutput) ? Files.readString(configOutput, StandardCharsets.UTF_8) : null;

        final String json = mergeJson(classNames, existing);
        Files.writeString(configOutput, json, StandardCharsets.UTF_8);
        log("[codegen-svc-list] Updated " + classNames.size() + " classes into " + configOutput, 'I');
    }

    private void log(final String msg, final char level) {
        if ('E' == level) {
            getLog().error(msg);
        } else if (verbose) {
            switch (level) {
                case 'I' -> getLog().info(msg);
                case 'W' -> getLog().warn(msg);
                case 'D' -> getLog().debug(msg);
            }
        }
    }
}
