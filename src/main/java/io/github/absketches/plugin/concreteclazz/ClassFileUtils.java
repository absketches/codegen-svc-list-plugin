package io.github.absketches.plugin.concreteclazz;

import berlin.yuna.typemap.logic.TypeConverter;
import berlin.yuna.typemap.model.LinkedTypeMap;
import berlin.yuna.typemap.model.TypeInfo;
import berlin.yuna.typemap.model.TypeList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static berlin.yuna.typemap.logic.JsonDecoder.jsonListOf;

final class ClassFileUtils {
    private static final String BASE_JAVA_CLASS = "java/lang/Object";

    private ClassFileUtils() {}

    static String toDotted(final String internalName) {
        return internalName.replace('/', '.');
    }

    static String toInternal(final String dottedName) {
        return dottedName.replace('.', '/');
    }

    static boolean isConcrete(final ClassHeader h) {
        return h != null && !h.isInterface() && !h.isAbstract();
    }

    // strip .class from name
    static String formatKey(final String classFilePath) {
        return classFilePath.substring(0, classFilePath.length() - 6);
    }

    // Accept comma-separated dot-notation base classes -> INTERNAL names
    static List<String> parseBaseClasses(String input) {
        if (input == null || input.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : input.split(",")) {
            s = s.trim();
            if (!s.isEmpty()) out.add(toInternal(s));
        }
        return out;
    }

    /**
     * Read ALL *.properties under the outputDir inside the JAR and merge.
     * Returns true if all allowedBases were present across the discovered files.
     */
    static boolean readAllPropertiesFromJarDir(final JarFile jf, final String dirPrefix, final Map<String, Set<String>> precomputed, final Set<String> allowedBases) throws IOException {

        Set<String> matched = new HashSet<>();
        for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
            JarEntry e = en.nextElement();
            String name = e.getName();

            if (e.isDirectory() || !name.startsWith(dirPrefix) || !name.endsWith(".properties"))
                continue;

            try (InputStream in = jf.getInputStream(e); BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                Properties props = new Properties();
                props.load(br);
                processEachJar(matched, props, allowedBases, precomputed);
            }
        }
        return matched.containsAll(allowedBases);
    }


    // Class hierarchy walk
    static boolean isSubclassOfBase(final String internal, final Map<String, ClassHeader> headers, final Map<String, Boolean> cache, final String baseInternal) {
        Boolean cached = cache.get(internal);
        if (cached != null) return cached;

        ClassHeader h = headers.get(internal);
        if (!isConcrete(h)) {
            cache.put(internal, false);
            return false;
        }

        String cur = h.superInternalName();
        List<String> visited = new ArrayList<>();
        visited.add(internal);

        for (int hops = 0; cur != null && hops <= 256; hops++) {
            if (baseInternal.equals(cur)) {
                markVisited(visited, cache, true);
                return true;
            }
            if (BASE_JAVA_CLASS.equals(cur)) {
                markVisited(visited, cache, false);
                return false;
            }

            Boolean c2 = cache.get(cur);
            if (c2 != null) {
                markVisited(visited, cache, c2);
                return c2;
            }

            ClassHeader sup = headers.get(cur);
            if (!isConcrete(sup)) {
                markVisited(visited, cache, false);
                return false;
            }

            visited.add(cur);
            cur = sup.superInternalName();
        }
        markVisited(visited, cache, false);
        return false;
    }

    static String formatResultMap(final Map<String, Set<String>> resultMap) {
        StringBuilder sb = new StringBuilder();
        for (var entry : resultMap.entrySet()) {
            String key = toDotted(entry.getKey());
            String value = entry.getValue().stream()
                .map(ClassFileUtils::toDotted)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
            sb.append(key).append("=").append(value).append("\n");
        }
        return String.valueOf(sb);
    }

    /**
     * To create/modify reflect-config JSON file, we will merge existing flags
     */
    static String mergeJson(final Set<String> classNames, final String existingJson) {
        final TypeList existingJsonArr = jsonListOf(existingJson);
        final Set<String> nameSet = classNames.stream().map(ClassFileUtils::toDotted).collect(Collectors.toSet());
        final TypeList resultJsonArr = new TypeList();

        // Keep existing jsonObjects (if any), in their current order
        for (Object json : existingJsonArr) {
            TypeInfo<?> jsonObj = TypeConverter.convertObj(json, TypeInfo.class);
            if (jsonObj.isPresent("name")) {
                String name = jsonObj.get(String.class, "name");
                if (nameSet.contains(name)) {
                    jsonObj.setPath("allDeclaredConstructors", true);
                    nameSet.remove(name);
                }
            }
            resultJsonArr.add(jsonObj);
        }

        for (String name : nameSet) {
            TypeInfo<?> jsonObj = new LinkedTypeMap();
            jsonObj.setPathR("name", name).setPath("allDeclaredConstructors", true);
            resultJsonArr.add(jsonObj);
        }
        return resultJsonArr.toJson();
    }

    private static void processEachJar(final Set<String> matched, final Properties props, final Set<String> allowedBases, final Map<String, Set<String>> precomputed) {
        for (String key : props.stringPropertyNames()) {
            String keyInternal = toInternal(key);
            if (!allowedBases.contains(keyInternal))
                continue;

            matched.add(keyInternal);
            String val = props.getProperty(key);
            if (null == val)
                continue;

            Set<String> set = precomputed.computeIfAbsent(keyInternal, k -> new TreeSet<>());
            for (String clazz : val.split(",", -1)) {
                if (!clazz.isBlank())
                    set.add(toInternal(clazz.strip()));
            }
        }
    }

    private static void markVisited(final List<String> visited, final Map<String, Boolean> cache, final boolean v) {
        for (String s : visited) cache.putIfAbsent(s, v);
    }
}
