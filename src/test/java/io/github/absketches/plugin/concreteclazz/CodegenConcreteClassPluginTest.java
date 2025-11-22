package io.github.absketches.plugin.concreteclazz;

import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
