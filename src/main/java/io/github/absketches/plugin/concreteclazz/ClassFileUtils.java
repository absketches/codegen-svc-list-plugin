package io.github.absketches.plugin.concreteclazz;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ClassFileUtils {
    private static final String BASE_JAVA_CLASS = "java/lang/Object";
    private static final Pattern NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    private ClassFileUtils() {}

    static String toDotted(String internalName) {
        return internalName.replace('/', '.');
    }

    static String toInternal(String dottedName) {
        return dottedName.replace('.', '/');
    }

    static boolean isConcrete(ClassHeader h) {
        return h != null && !h.isInterface() && !h.isAbstract();
    }

    // strip .class from name
    static String formatKey(String classFilePath) {
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

    // Read ALL *.properties under the outputDir inside the JAR and merge.
    // Returns true if all allowedBases were present across the discovered files.
    // TODO: Refactor - multiple nested loops
    static boolean readAllPropertiesFromJarDir(
        JarFile jf,
        String dirPrefix,
        Map<String, Set<String>> precomputed,
        Set<String> allowedBases
    ) throws IOException {

        Set<String> matched = new HashSet<>();

        for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
            JarEntry e = en.nextElement();
            String name = e.getName();

            if (e.isDirectory() || !name.startsWith(dirPrefix) || !name.endsWith(".properties"))
                continue;

            try (InputStream in = jf.getInputStream(e); BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

                Properties props = new Properties();
                props.load(br);

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
        }
        return matched.containsAll(allowedBases);
    }


    // Class hierarchy walk
    static boolean isSubclassOfBase(String internal,
                                    Map<String, ClassHeader> headers,
                                    Map<String, Boolean> cache,
                                    String baseInternal) {
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

    static String mergeJson(final Set<String> classNames, final String existingJson) {
        // create full JSON object (merge existing flags)
        final LinkedHashMap<String, String> classIndexMap = new LinkedHashMap<>();

        // Keep existing jsonObjects (if any), in their current order
        if (null != existingJson && !existingJson.isBlank()) {
            for (String jsonObj : extractTopLevelObjects(existingJson)) {
                final String name = toDotted(extractName(jsonObj));
                if (!name.isBlank() && !classIndexMap.containsKey(name)) {
                    classIndexMap.put(toDotted(name), jsonObj.trim());
                }
            }
        }

        // Add new classes if not present
        for (String cn : classNames) {
            cn = toDotted(cn.trim());
            if (!classIndexMap.containsKey(cn)) {
                classIndexMap.put(cn, "{\"name\":\"" + cn + "\", \"allDeclaredConstructors\": true}");
                continue;
            }

            String existingJsonObj = String.valueOf(classIndexMap.get(cn)).trim();
            if (existingJsonObj.matches("(?s).*\"allDeclaredConstructors\"\\s*:\\s*true.*")) {
                // No need to do anything
            } else if (existingJsonObj.matches("(?s).*\"allDeclaredConstructors\"\\s*:\\s*false.*")) {
                existingJsonObj = existingJsonObj.replaceFirst("\"allDeclaredConstructors\"\\s*:\\s*false", "\"allDeclaredConstructors\": true");
            } else {
                int end = existingJsonObj.lastIndexOf('}');
                if (end > 0) {
                    String head = existingJsonObj.substring(0, end).trim();
                    String sep = head.endsWith("{") ? "" : ",";
                    existingJsonObj = head + sep + " \"allDeclaredConstructors\": true}";
                } else {
                    existingJsonObj = "{\"name\":\"" + cn + "\", \"allDeclaredConstructors\": true}";
                }
            }
            classIndexMap.put(cn, existingJsonObj);
        }
        return createJson(classIndexMap);
    }

    private static String createJson(final LinkedHashMap<String, String> classIndexMap) {
        final StringBuilder output = new StringBuilder();
        output.append("[\n");
        boolean first = true;
        for (String obj : classIndexMap.values()) {
            if (!first)
                output.append(",\n");
            output.append("  ").append(obj);
            first = false;
        }
        output.append("\n]\n");
        return output.toString();
    }

    /**
     * Pulls top-level JSON objects from the reflect-config.json.
     */
    private static List<String> extractTopLevelObjects(final String json) {
        final List<String> jsonArray = new ArrayList<>();
        int level = 0, start = -1;
        boolean inString = false, esc = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                if (0 == level) {
                    start = i;
                    ++level;
                }
            } else if (c == '}') {
                if (--level == 0 && start >= 0) {
                    jsonArray.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return jsonArray;
    }

    /**
     * Extracts the "name" field from a single Json object.
     */
    private static String extractName(final String jsonObj) {
        final Matcher m = NAME_PATTERN.matcher(jsonObj);
        return m.find() ? m.group(1) : "";
    }

    private static void markVisited(List<String> visited, Map<String, Boolean> cache, boolean v) {
        for (String s : visited) cache.putIfAbsent(s, v);
    }
}
