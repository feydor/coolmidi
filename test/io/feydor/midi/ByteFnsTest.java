package io.feydor.midi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ByteFnsTest {
    @Test
    void toHexWorks() {
        // {0xF, 0xF, 0xFF, 0x5} => 0F0FFF05
        byte[] buf1 = new byte[]{0x0F, 0x0F, (byte)0xFF, 0x05};
        assertEquals("0F0FFF05", ByteFns.toHex(buf1));

        // {0xFF, 0xFF, 0xFF, 0xFF} => FFFFFFFF
        byte[] allOnes = new byte[]{(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF};
        assertEquals("FFFFFFFF", ByteFns.toHex(allOnes));

        // {0, 0, 0, 0} => 00000000
        byte[] allZeros = new byte[]{0, 0, 0, 0};
        assertEquals("00000000", ByteFns.toHex(allZeros));
    }

    @Test
    void toUnsignedIntWorks() {
        // Test that the result is really unsigned
        assertEquals(255, ByteFns.toUnsignedInt(new byte[]{(byte)0xFF}));
        assertEquals(65535, ByteFns.toUnsignedInt(new byte[]{(byte)0xFF, (byte)0xFF}));
        assertEquals(16777215, ByteFns.toUnsignedInt(new byte[]{(byte)0xFF, (byte)0xFF, (byte)0xFF}));
        assertEquals(Integer.MAX_VALUE, ByteFns.toUnsignedInt(new byte[]{(byte)0x7F, (byte)0xFF, (byte)0xFF, (byte)0xFF}));

        // Throws an error when the value is greater than Integer.MAX_VALUE
        assertThrows(ArithmeticException.class, () -> ByteFns.toUnsignedInt(new byte[]{(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF}));
    }

    @Test
    void fromHexWorks() {
        assertArrayEquals(new byte[]{(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF}, ByteFns.fromHex("FFFFFFFF"));
        assertArrayEquals(new byte[]{(byte)0xFF, (byte)0xFF, (byte)0xFF}, ByteFns.fromHex("FFFFFF"));
        assertArrayEquals(new byte[]{(byte)0xFF, (byte)0xFF}, ByteFns.fromHex("FFFF"));
        assertArrayEquals(new byte[]{(byte)0xFF}, ByteFns.fromHex("FF"));
    }

    @Test
    void toHexWorks_byte() {
        assertEquals("ff", ByteFns.toHex((byte)255));
        assertEquals("7f", ByteFns.toHex((byte)127));
        assertEquals("00", ByteFns.toHex((byte)0));
    }
}