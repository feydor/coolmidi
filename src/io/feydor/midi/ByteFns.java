package io.feydor.midi;

import java.nio.ByteBuffer;

/**
 * Static functions for working with byte buffers
 */
public class ByteFns {
    /**
     * Converts a byte array into 2-digit hexadecimal representation.
     * For example, {0xF, 0xF, 0xFF, 0x5} => 0F0FFF05
     *
     * @param buf the buffer to convert
     * @return a hexadecimal representation of the buffer
     */
    public static String toHex(byte[] buf) {
        var sb = new StringBuilder();
        for (byte b : buf) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static byte[] fromHex(String hexString) {
        if (hexString.length() % 2 != 0) {
            throw new IllegalArgumentException("Hexstring must be a valid hexadecimal number (it's length must be even). " + hexString);
        }

        byte[] buf = new byte[hexString.length() / 2];
        int bp = 0;
        for (int i = 0; i <= hexString.length() - 2; i += 2) {
            short b = (short) Integer.parseUnsignedInt(hexString.substring(i, i + 2), 16);
            buf[bp++] = (byte) b;
        }
        return buf;
    }

    /**
     * Converts a byte buffer into an unsigned integer.
     * If the byte buffer is less than 4 bytes, the byte buffer is zero-extended & widened to 4 bytes.
     *
     * @param buf the buffer to convert
     * @return the unsigned integer value of the buffer
     * @throws ArithmeticException When the provided buffer represents a value > Integer.MAX_VALUE.
     *                             For example, {0x8F, 0xFF, 0xFF, 0xFF} is Integer.MAX_VALUE + 1 which would wrap back to Intger.MIN_VALUE.
     */
    public static int toUnsignedInt(byte[] buf) {
        if (buf.length < 4) {
            buf = widenWithZeros(buf, 4);
        }

        if ((buf[0] & 0xFF) > 0x7F) {
            throw new ArithmeticException("Attempting to convert a buffer whose value is greater than Integer.MAX_VALUE.");
        }

        int val = ByteBuffer.wrap(buf).getInt();
        return (int) (val & 0xffffffffL); // drop sign bit
    }

    /**
     * Converts a byte buffer into an unsigned short
     *
     * @param buf the buffer to convert
     * @return the unsigned short value of the buffer
     * @throws ArithmeticException When the provided buffer's upper byte is greater than 0x7F, and so would overflow a short.
     */
    public static short toUnsignedShort(byte[] buf) {
        if (buf.length < 2) {
            buf = widenWithZeros(buf, 2);
        }

        if ((buf[0] & 0xFF) > 0x7F) {
            throw new ArithmeticException("Attempting to convert a buffer whose value is greater than Short.MAX_VALUE.");
        }

        int val = ByteBuffer.wrap(buf).getShort();
        return (short) (val & 0xffff); // drop sign bit
    }

    /**
     * Widens a buffer to a target length and zero-extends the most significant bytes.
     * Example: widenWithZeros([0xFF, 0xFF], 4) => [0, 0, 0xFF, 0xFF]
     *
     * @param buf       The buffer to widen
     * @param targetLen The length of the widened buffer
     * @return The buffer widened to targetLength and zero-extended
     */
    private static byte[] widenWithZeros(byte[] buf, int targetLen) {
        if (buf.length == targetLen) {
            return buf;
        }

        byte[] widened = new byte[targetLen];
        int offset = targetLen - buf.length;
        System.arraycopy(buf, 0, widened, offset, buf.length);
        return widened;
    }

    public static String toHex(byte n) {
        return String.format("%02x", (0xFF & n));
    }
}
