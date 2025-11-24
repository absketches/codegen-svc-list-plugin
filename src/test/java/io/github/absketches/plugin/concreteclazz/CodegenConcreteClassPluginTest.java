package io.github.absketches.plugin.concreteclazz;

import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodegenConcreteClassPluginTest {

    private CodegenConcreteClassPlugin plugin;
    private MavenProject project;

    @BeforeEach
    void setup() throws Exception {
        plugin = new CodegenConcreteClassPlugin();
        project = new MavenProject();
        Build build = new Build();
        Path buildDir = Files.createTempDirectory("classes");
        build.setOutputDirectory(buildDir.toString());
        project.setBuild(build);
        project.setGroupId("io.test");
        project.setArtifactId("plugin");
        TestUtils.setField(plugin, "project", project);
    }

    @Test
    void scanDirectoryCollectsHeaders() throws Exception {
        Path classes = Path.of(project.getBuild().getOutputDirectory());
        TestUtils.writeClassFile(classes, "com/example/Impl", "java/lang/Object", 0);

        Method scanDirectory = CodegenConcreteClassPlugin.class.getDeclaredMethod("scanDirectory", Path.class, Map.class);
        scanDirectory.setAccessible(true);

        Map<String, ClassHeader> headers = new HashMap<>();
        scanDirectory.invoke(plugin, classes, headers);

        assertEquals(1, headers.size());
        assertEquals("java/lang/Object", headers.get("com/example/Impl").superInternalName());
    }

    @Test
    void prepareToScanJarUsesPrecompiledPropertiesWhenAllBasesPresent() throws Exception {
        Path tempDir = Files.createTempDirectory("jar-precompiled");
        Path jarPath = tempDir.resolve("precompiled.jar");

        String dirPrefix = CodegenConcreteClassPlugin.outputDir;
        TestUtils.createJar(jarPath, jos -> {
            try {
                String props = "com.example.Base=com.example.ImplOne,com.example.ImplTwo";
                TestUtils.addEntry(jos, dirPrefix + "services.properties", props.getBytes());
                byte[] bytes = TestUtils.buildClassBytes("com/example/ImplOne", "java/lang/Object", 0);
                TestUtils.addEntry(jos, "com/example/ImplOne.class", bytes);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        Map<String, ClassHeader> headers = new HashMap<>();
        Map<String, Set<String>> precomputed = new LinkedHashMap<>();
        List<String> allowed = List.of("com/example/Base");

        Method prepareToScanJar = CodegenConcreteClassPlugin.class.getDeclaredMethod(
                "prepareToScanJar", java.io.File.class, Map.class, Map.class, List.class);
        prepareToScanJar.setAccessible(true);

        TestUtils.setField(plugin, "usePrecompiledLists", true);
        prepareToScanJar.invoke(plugin, jarPath.toFile(), headers, precomputed, allowed);

        assertTrue(headers.isEmpty(), "Should skip scanning classes when properties are complete");
        assertEquals(Set.of("com/example/ImplOne", "com/example/ImplTwo"), precomputed.get("com/example/Base"));
    }

    @Test
    void prepareToScanJarScansWhenPrecompiledEntriesMissing() throws Exception {
        Path tempDir = Files.createTempDirectory("jar-precompiled-missing");
        Path jarPath = tempDir.resolve("scan.jar");

        String dirPrefix = CodegenConcreteClassPlugin.outputDir;
        TestUtils.createJar(jarPath, jos -> {
            try {
                String props = "com.example.Other=impl.One";
                TestUtils.addEntry(jos, dirPrefix + "services.properties", props.getBytes());
                byte[] bytes = TestUtils.buildClassBytes("com/example/Scanned", "java/lang/Object", 0);
                TestUtils.addEntry(jos, "com/example/Scanned.class", bytes);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        Map<String, ClassHeader> headers = new HashMap<>();
        Map<String, Set<String>> precomputed = new LinkedHashMap<>();
        List<String> allowed = List.of("com/example/Missing");

        Method prepareToScanJar = CodegenConcreteClassPlugin.class.getDeclaredMethod(
                "prepareToScanJar", java.io.File.class, Map.class, Map.class, List.class);
        prepareToScanJar.setAccessible(true);

        prepareToScanJar.invoke(plugin, jarPath.toFile(), headers, precomputed, allowed);

        assertTrue(headers.containsKey("com/example/Scanned"), "Should scan classes when precompiled entries are incomplete");
        assertTrue(precomputed.isEmpty());
    }

    @Test
    void prepareToScanJarFallsBackToScanningClasses() throws Exception {
        Path tempDir = Files.createTempDirectory("jar-scan");
        Path jarPath = tempDir.resolve("scan.jar");

        TestUtils.createJar(jarPath, jos -> {
            try {
                byte[] bytes = TestUtils.buildClassBytes("com/example/JarImpl", "java/lang/Object", 0);
                TestUtils.addEntry(jos, "com/example/JarImpl.class", bytes);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        Map<String, ClassHeader> headers = new HashMap<>();
        Map<String, Set<String>> precomputed = new LinkedHashMap<>();
        List<String> allowed = List.of("com/example/Base");

        Method prepareToScanJar = CodegenConcreteClassPlugin.class.getDeclaredMethod(
                "prepareToScanJar", java.io.File.class, Map.class, Map.class, List.class);
        prepareToScanJar.setAccessible(true);

        prepareToScanJar.invoke(plugin, jarPath.toFile(), headers, precomputed, allowed);

        assertEquals(1, headers.size());
        assertTrue(headers.containsKey("com/example/JarImpl"));
    }

    @Test
    void processArtifactSkipsNonJars() throws Exception {
        Path tempDir = Files.createTempDirectory("artifact-skip");
        Path pomFile = tempDir.resolve("pom.file");
        Files.writeString(pomFile, "not-a-jar");

        DefaultArtifact artifact = new DefaultArtifact(
                "g", "a", "1", "compile", "pom", null, new DefaultArtifactHandler("pom"));
        artifact.setFile(pomFile.toFile());

        Method processArtifact = CodegenConcreteClassPlugin.class.getDeclaredMethod(
                "processArtifact", org.apache.maven.artifact.Artifact.class, Map.class, Map.class, List.class);
        processArtifact.setAccessible(true);

        Map<String, ClassHeader> headers = new HashMap<>();
        Map<String, Set<String>> precomputed = new LinkedHashMap<>();

        processArtifact.invoke(plugin, artifact, headers, precomputed, List.of("com/example/Base"));

        assertTrue(headers.isEmpty(), "Non-jar artifacts should be ignored");
        assertTrue(precomputed.isEmpty());
    }

    @Test
    void processArtifactLogsIOException() throws Exception {
        Path tempDir = Files.createTempDirectory("artifact-error");
        Path brokenJar = tempDir.resolve("broken.jar");
        Files.writeString(brokenJar, "corrupt");

        DefaultArtifact artifact = new DefaultArtifact(
                "g", "a", "1", "compile", "jar", null, new DefaultArtifactHandler("jar"));
        artifact.setFile(brokenJar.toFile());

        Method processArtifact = CodegenConcreteClassPlugin.class.getDeclaredMethod(
                "processArtifact", org.apache.maven.artifact.Artifact.class, Map.class, Map.class, List.class);
        processArtifact.setAccessible(true);

        TestLog log = new TestLog();
        plugin.setLog(log);

        Map<String, ClassHeader> headers = new HashMap<>();
        Map<String, Set<String>> precomputed = new LinkedHashMap<>();

        processArtifact.invoke(plugin, artifact, headers, precomputed, List.of("com/example/Base"));

        assertTrue(headers.isEmpty());
        assertTrue(log.errors.stream().anyMatch(msg -> msg.contains("Jar scan failed")));
    }

    @Test
    void executeWrapsExceptionsFromScanning() throws Exception {
        TestUtils.setField(plugin, "baseClasses", "org.nanonative.nano.core.model.Service");
        TestLog log = new TestLog();
        plugin.setLog(log);

        Path classes = Path.of(project.getBuild().getOutputDirectory());
        Files.writeString(classes.resolve("Bad.class"), "not-a-class");
        project.setArtifacts(new HashSet<>());

        assertThrows(MojoExecutionException.class, plugin::execute);
        assertTrue(log.errors.stream().anyMatch(msg -> msg.contains("Corrupt stream")));
    }

    @Test
    void writePropertiesSkipsWhenContentUnchanged() throws Exception {
        TestUtils.setField(plugin, "outputFile", "services.properties");

        Method writeProperties = CodegenConcreteClassPlugin.class.getDeclaredMethod("writeProperties", Path.class, Map.class);
        writeProperties.setAccessible(true);

        Path classes = Path.of(project.getBuild().getOutputDirectory());
        Map<String, Set<String>> result = new LinkedHashMap<>();
        result.put("com/example/Base", new TreeSet<>(Set.of("com/example/Impl")));

        writeProperties.invoke(plugin, classes, result);
        Path outFile = classes.resolve(CodegenConcreteClassPlugin.outputDir + "services.properties");
        String content = Files.readString(outFile);

        writeProperties.invoke(plugin, classes, result);
        String contentAfter = Files.readString(outFile);

        assertEquals(content, contentAfter);
    }

    @Test
    void writeReflectConfigMergesExistingContent() throws Exception {
        Method writeReflectConfig = CodegenConcreteClassPlugin.class.getDeclaredMethod("writeReflectConfig", Set.class, Path.class);
        writeReflectConfig.setAccessible(true);

        Path classes = Path.of(project.getBuild().getOutputDirectory());
        Path reflectPath = classes
                .resolve("META-INF/native-image")
                .resolve(project.getGroupId())
                .resolve(project.getArtifactId())
                .resolve("reflect-config.json");

        Files.createDirectories(reflectPath.getParent());
        Files.writeString(reflectPath, "[{\"name\":\"com.example.Existing\",\"allDeclaredConstructors\":false}]");

        Set<String> classesToWrite = Set.of("com.example.Existing", "com.example.New");
        writeReflectConfig.invoke(plugin, classesToWrite, classes);

        String merged = Files.readString(reflectPath);
        assertTrue(merged.contains("com.example.Existing"));
        assertTrue(merged.contains("com.example.New"));
        assertTrue(merged.contains("allDeclaredConstructors\":true"));
    }

    @Test
    void executeWritesReflectConfigWhenOnlyReflectedClassesProvided() throws Exception {
        TestUtils.setField(plugin, "generateReflectConfig", true);
        TestUtils.setField(plugin, "baseClasses", "com.example.Base");
        TestUtils.setField(plugin, "reflectedClasses", "com.example.ManualOne,com.example.ManualTwo");
        project.setArtifacts(new HashSet<>());

        plugin.execute();

        Path classes = Path.of(project.getBuild().getOutputDirectory());
        Path reflectPath = classes
                .resolve("META-INF/native-image")
                .resolve(project.getGroupId())
                .resolve(project.getArtifactId())
                .resolve("reflect-config.json");

        assertTrue(Files.exists(reflectPath));
        String json = Files.readString(reflectPath);
        assertTrue(json.contains("com.example.ManualOne"));
        assertTrue(json.contains("com.example.ManualTwo"));
    }

    @Test
    void executeSkipsWhenClassesDirectoryMissing() throws Exception {
        Path classes = Path.of(project.getBuild().getOutputDirectory());
        Files.deleteIfExists(classes);

        plugin.execute();

        Path output = classes.resolve(CodegenConcreteClassPlugin.outputDir + "services.properties");
        assertTrue(Files.notExists(output));
    }

    @Test
    void writeReflectConfigIsSkippedWhenDisabled() throws Exception {
        TestUtils.setField(plugin, "generateReflectConfig", false);
        TestUtils.setField(plugin, "baseClasses", "com.example.Base");

        plugin.execute();

        Path classes = Path.of(project.getBuild().getOutputDirectory());
        Path reflectPath = classes
                .resolve("META-INF/native-image")
                .resolve(project.getGroupId())
                .resolve(project.getArtifactId())
                .resolve("reflect-config.json");

        assertTrue(Files.notExists(reflectPath));
    }

    @Test
    void writeReflectConfigReturnsEarlyForEmptySet() throws Exception {
        Method writeReflectConfig = CodegenConcreteClassPlugin.class.getDeclaredMethod("writeReflectConfig", Set.class, Path.class);
        writeReflectConfig.setAccessible(true);

        Path classes = Path.of(project.getBuild().getOutputDirectory());
        Set<String> empty = Set.of();

        writeReflectConfig.invoke(plugin, empty, classes);

        Path reflectPath = classes
                .resolve("META-INF/native-image")
                .resolve(project.getGroupId())
                .resolve(project.getArtifactId())
                .resolve("reflect-config.json");
        assertTrue(Files.notExists(reflectPath));
    }

    @Test
    void logHonorsVerboseFlag() throws Exception {
        Method logMethod = CodegenConcreteClassPlugin.class.getDeclaredMethod("log", String.class, char.class);
        logMethod.setAccessible(true);

        TestLog log = new TestLog();
        plugin.setLog(log);

        // Default verbose = false should suppress info but keep errors
        logMethod.invoke(plugin, "info message", 'I');
        logMethod.invoke(plugin, "error message", 'E');
        assertTrue(log.infos.isEmpty());
        assertEquals(List.of("error message"), log.errors);

        TestUtils.setField(plugin, "verbose", true);
        logMethod.invoke(plugin, "another info", 'I');
        logMethod.invoke(plugin, "warn msg", 'W');
        logMethod.invoke(plugin, "debug msg", 'D');

        assertTrue(log.infos.contains("another info"));
        assertTrue(log.warns.contains("warn msg"));
        assertTrue(log.debugs.contains("debug msg"));
    }
}
