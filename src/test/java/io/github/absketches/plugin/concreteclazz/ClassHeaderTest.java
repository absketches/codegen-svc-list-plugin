package io.github.absketches.plugin.concreteclazz;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClassHeaderTest {

    @Test
    void readsConcreteClassHeader() throws IOException {
        byte[] bytes = TestUtils.buildClassBytes("com/example/Impl", "java/lang/Object", 0);
        ClassHeader header = ClassHeader.read(new ByteArrayInputStream(bytes));

        assertEquals("java/lang/Object", header.superInternalName());
        assertFalse(header.isAbstract());
        assertFalse(header.isInterface());
    }

    @Test
    void detectsInterfaceAndAbstractFlags() throws IOException {
        byte[] iface = TestUtils.buildClassBytes("com/example/Itf", "java/lang/Object", 0x0200);
        ClassHeader ifaceHeader = ClassHeader.read(new ByteArrayInputStream(iface));
        assertTrue(ifaceHeader.isInterface());

        byte[] abs = TestUtils.buildClassBytes("com/example/Abs", "java/lang/Object", 0x0400);
        ClassHeader abstractHeader = ClassHeader.read(new ByteArrayInputStream(abs));
        assertTrue(abstractHeader.isAbstract());
    }

    @Test
    void failsOnWrongMagicNumber() {
        byte[] broken = new byte[]{0, 1, 2, 3};
        assertThrows(IOException.class, () -> ClassHeader.read(new ByteArrayInputStream(broken)));
    }
}
