package io.github.absketches.plugin.concreteclazz;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassFileUtilsTest {

    @Test
    void convertsNames() {
        assertEquals("a.b.C", ClassFileUtils.toDotted("a/b/C"));
        assertEquals("x/y/Z", ClassFileUtils.toInternal("x.y.Z"));
        assertEquals("foo/bar/Baz", ClassFileUtils.formatKey("foo/bar/Baz.class"));
    }

    @Test
    void parsesBaseClasses() {
        assertEquals(List.of(), ClassFileUtils.parseBaseClasses(null));
        assertEquals(List.of(), ClassFileUtils.parseBaseClasses("   "));
        assertEquals(List.of("com/example/Service", "java/lang/Runnable"),
                ClassFileUtils.parseBaseClasses(" com.example.Service, ,java.lang.Runnable  "));
    }

    @Test
    void detectsConcreteHeaders() {
        assertFalse(ClassFileUtils.isConcrete(null));
        assertFalse(ClassFileUtils.isConcrete(new ClassHeader(0x0200, "java/lang/Object")));
        assertFalse(ClassFileUtils.isConcrete(new ClassHeader(0x0400, "java/lang/Object")));
        assertTrue(ClassFileUtils.isConcrete(new ClassHeader(0, "java/lang/Object")));
    }

    @Test
    void walksClassHierarchyUsingCache() {
        Map<String, ClassHeader> headers = new HashMap<>();
        headers.put("com/base/Base", new ClassHeader(0, "java/lang/Object"));
        headers.put("com/example/Impl", new ClassHeader(0, "com/base/Base"));
        headers.put("com/example/Other", new ClassHeader(0, "java/lang/Object"));

        Map<String, Boolean> cache = new HashMap<>();
        assertTrue(ClassFileUtils.isSubclassOfBase("com/example/Impl", headers, cache, "com/base/Base"));
        assertEquals(Boolean.TRUE, cache.get("com/example/Impl"));

        // Cached result should short-circuit
        assertTrue(ClassFileUtils.isSubclassOfBase("com/example/Impl", headers, cache, "com/base/Base"));
        assertFalse(ClassFileUtils.isSubclassOfBase("com/example/Other", headers, cache, "com/base/Base"));
    }

    @Test
    void hierarchyStopsAtJavaLangObjectOrNonConcreteSuper() {
        Map<String, ClassHeader> headers = new HashMap<>();
        headers.put("com/base/AbstractBase", new ClassHeader(0x0400, "java/lang/Object"));
        headers.put("com/example/Impl", new ClassHeader(0, "com/base/AbstractBase"));

        Map<String, Boolean> cache = new HashMap<>();
        assertTrue(ClassFileUtils.isSubclassOfBase("com/example/Impl", headers, cache, "com/base/AbstractBase"));

        headers.put("com/base/Base", new ClassHeader(0, "java/lang/Object"));
        headers.put("com/example/Child", new ClassHeader(0, "com/base/Base"));
        assertFalse(ClassFileUtils.isSubclassOfBase("com/example/Child", headers, cache, "com/unknown/Base"));
    }

    @Test
    void formatsResultMap() {
        Map<String, Set<String>> map = new HashMap<>();
        map.put("com/base/Base", new TreeSet<>(Set.of("com/example/Impl", "com/example/Alt")));
        String output = ClassFileUtils.formatResultMap(map);
        assertTrue(output.contains("com.base.Base="));
        assertTrue(output.contains("com.example.Alt"));
        assertTrue(output.contains("com.example.Impl"));
    }

    @Test
    void mergesJsonWithExistingEntries() {
        Set<String> classes = Set.of("com.example.Existing", "com.example.NewOne");
        String existing = "[{\"name\":\"com.example.Existing\",\"allDeclaredConstructors\":false},{\"name\":\"com.example.Other\"}]";
        String merged = ClassFileUtils.mergeJson(classes, existing);

        assertTrue(merged.contains("\"com.example.Existing\""));
        assertTrue(merged.contains("\"com.example.NewOne\""));
        assertTrue(merged.contains("allDeclaredConstructors\":true"));
        assertTrue(merged.contains("com.example.Other"));
    }

    @Test
    void mergesJsonWhenExistingContentMissing() {
        Set<String> classes = Set.of("com.example.Solo");
        String merged = ClassFileUtils.mergeJson(classes, null);

        assertTrue(merged.contains("com.example.Solo"));
        assertTrue(merged.contains("allDeclaredConstructors\":true"));
    }

    @Test
    void readsAllPropertiesFromJarDirectory() throws Exception {
        Path tmpDir = Files.createTempDirectory("jar-test");
        Path jarFile = tmpDir.resolve("test.jar");
        String dirPrefix = "META-INF/io/github/absketches/plugin/";
        TestUtils.createJar(jarFile, jos -> {
            try {
                String props = "com.example.Base=impl.One,impl.Two\nignored=foo";
                TestUtils.addEntry(jos, dirPrefix + "services.properties", props.getBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        Map<String, Set<String>> precomputed = new HashMap<>();
        Set<String> allowed = new HashSet<>(Set.of("com/example/Base"));
        boolean matched = ClassFileUtils.readAllPropertiesFromJarDir(new java.util.jar.JarFile(jarFile.toFile()), dirPrefix, precomputed, allowed);

        assertTrue(matched);
        assertEquals(Set.of("impl/One", "impl/Two"), precomputed.get("com/example/Base"));

        // Missing base should return false
        boolean missing = ClassFileUtils.readAllPropertiesFromJarDir(new java.util.jar.JarFile(jarFile.toFile()), dirPrefix, precomputed, Set.of("unknown/Base"));
        assertFalse(missing);
    }

    @Test
    void hierarchyReturnsFalseWhenHeaderMissingOrCached() {
        Map<String, ClassHeader> headers = new HashMap<>();
        headers.put("com/example/Child", new ClassHeader(0, "com/example/Missing"));

        Map<String, Boolean> cache = new HashMap<>();
        assertFalse(ClassFileUtils.isSubclassOfBase("com/example/Child", headers, cache, "com/example/Base"));
        assertEquals(Boolean.FALSE, cache.get("com/example/Child"));

        // Populate cache for the missing superclass and ensure traversal short-circuits
        cache.put("com/example/Missing", false);
        assertFalse(ClassFileUtils.isSubclassOfBase("com/example/Child", headers, cache, "com/example/Base"));
    }

    @Test
    void readAllPropertiesIgnoresNonMatchingEntriesAndBlankValues() throws Exception {
        Path tmpDir = Files.createTempDirectory("jar-filter");
        Path jarFile = tmpDir.resolve("filter.jar");
        String dirPrefix = "META-INF/io/github/absketches/plugin/";

        TestUtils.createJar(jarFile, jos -> {
            try {
                jos.putNextEntry(new java.util.jar.JarEntry("META-INF/"));
                jos.closeEntry();
                TestUtils.addEntry(jos, dirPrefix + "ignored.txt", "com.example.Base=impl.One".getBytes());
                TestUtils.addEntry(jos, "META-INF/other/services.properties", "com.example.Base=impl.One".getBytes());
                TestUtils.addEntry(jos, dirPrefix + "services.properties", "com.example.Base=".getBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        Map<String, Set<String>> precomputed = new HashMap<>();
        Set<String> allowed = Set.of("com/example/Base", "com/example/Missing");
        boolean matched = ClassFileUtils.readAllPropertiesFromJarDir(new java.util.jar.JarFile(jarFile.toFile()), dirPrefix, precomputed, allowed);

        assertFalse(matched, "Missing allowed base should report false");
        assertTrue(precomputed.getOrDefault("com/example/Base", Set.of()).isEmpty());
    }

    @Test
    void mergeJsonPreservesObjectsWithoutName() {
        Set<String> classes = Set.of("com.example.Added");
        String existing = "[{\"foo\":\"bar\"}]";

        String merged = ClassFileUtils.mergeJson(classes, existing);

        assertTrue(merged.contains("\"foo\":\"bar\""));
        assertTrue(merged.contains("com.example.Added"));
    }
}
