package io.github.absketches.plugin.concreteclazz;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class TestUtils {

    private TestUtils() {}

    static byte[] buildClassBytes(final String classNameInternal, final String superNameInternal, final int accessFlags) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // minor
            out.writeShort(61); // major

            boolean hasSuper = superNameInternal != null;
            int cpCount = hasSuper ? 5 : 3;
            out.writeShort(cpCount);

            // #1 class name Utf8
            out.writeByte(1);
            out.writeUTF(classNameInternal);

            // #2 Class pointing to #1
            out.writeByte(7);
            out.writeShort(1);

            if (hasSuper) {
                // #3 super name Utf8
                out.writeByte(1);
                out.writeUTF(superNameInternal);
                // #4 Class pointing to #3
                out.writeByte(7);
                out.writeShort(3);
            }

            out.writeShort(accessFlags);
            out.writeShort(2); // this_class
            out.writeShort(hasSuper ? 4 : 0); // super_class

            out.writeShort(0); // interfaces_count
            out.writeShort(0); // fields_count
            out.writeShort(0); // methods_count
            out.writeShort(0); // attributes_count
        }
        return baos.toByteArray();
    }

    static Path writeClassFile(final Path root, final String internalName, final String superName, final int accessFlags) throws IOException {
        Path target = root.resolve(internalName + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, buildClassBytes(internalName, superName, accessFlags));
        return target;
    }

    static Path createJar(final Path target, final Consumer<JarOutputStream> writer) throws IOException {
        Files.createDirectories(target.getParent());
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(target))) {
            writer.accept(jos);
        }
        return target;
    }

    static void addEntry(final JarOutputStream jos, final String name, final byte[] data) throws IOException {
        JarEntry entry = new JarEntry(name);
        jos.putNextEntry(entry);
        jos.write(data);
        jos.closeEntry();
    }

    static void setField(final Object target, final String fieldName, final Object value) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
