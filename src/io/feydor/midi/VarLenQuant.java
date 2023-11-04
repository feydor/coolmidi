package io.feydor.midi;

import io.feydor.midi.exceptions.InvalidVarLenParseException;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Iterator;
import java.util.stream.IntStream;

/**
 * A variable length quantity.
 * //  where: <delta-time> variable-length quantity which means:
 * 		//		Most numbers are 7 bits per byte, most significant bits first.
 * 		//		All bytes except the last have bit 7 set, and the last has bit 7 clear.
 * 		//		if the number is between 0 and 127, it is represented as 1 byte
 * 		//		Some examples:
 * 		//		Number        Variable Length Quantity
 * 		//		--------------------------------------
 * 		//		00000040 	      40
 * 		//		0000007F (128) 	  7F
 * 		//		00000080 (128) 	  81 00
 * 		//		00002000 (8192)	  C0 00
 * 		//		00003FFF (16383)  FF 7F
 * 		//      00004000 (16384)  81 80 00
 */
public class VarLenQuant {
    /** The quantity itself */
    public int value;

    /** The number of bytes used to store the quantity */
    public short nbytes;

    private static final int MAX_BYTES = 4;

    private VarLenQuant(int value, short nbytes) {
        this.value = value;
        this.nbytes = nbytes;
    }

    /**
     * Decode a VarLen from a filestream. Modifies the current file pointer in the input stream.
     * @param file The filestream to read from
     * @return The value encoded and the # of bytes read to decode it
     * @throws IOException When the file is empty or at EoF
     * @throws InvalidVarLenParseException When more than 4 bytes are used to encode the VarLen
     */
    static public VarLenQuant readBytes(BufferedInputStream file) {
        return decode(new FileStreamByteIterator(file));
    }

    static public VarLenQuant decode(int[] bytes) {
        return decode(IntStream.of(bytes).iterator());
    }

    /**
     * src: <a href="https://en.wikipedia.org/wiki/Variable-length_quantity">Variable-length quantity</a>
     * @param bytes returns bytes from MSB to LSB
     * @return the decoded value and the number of bytes it took up
     */
    static public VarLenQuant decode(Iterator<Integer> bytes) {
        /*
         * Varlen quantity consists of 7-bit chunks, with the MSB of each signaling the last chunk.
         *
         * Only the bottom 7 bits of each byte contributes to the delta-time, the MSB indicates (when set) that another byte follows.
         * This means, the last byte of a delta-time will have its top bit clear.
         */
        int val = 0;
        short nbytes = 0;
        int i = 0;
        while (true) {
            int b = bytes.next();

            val = (val << 7) | (b & 0x7f); // concat the 7 least significant bits
            nbytes++;

            if ((b & 0x80) == 0) {
                break;
            }
        }

        if (nbytes > 4) {
            throw new InvalidVarLenParseException("The nbytes in the Varlen representation was greater than 4 bytes!");
        } else if (nbytes == 0) {
            String msg = "Failed to parse Varlen: nbytes=0";
            throw new InvalidVarLenParseException(msg);
        }

        return new VarLenQuant(val, nbytes);
    }

    /**
     * Converts a number into VLQ bytes. Reverse of decode.
     */
    static public String encode(int n) {
        int i = 0;
        byte[] bytes = new byte[MAX_BYTES];
        while (n > 0x7f) {
            byte lsb = (byte) (n & 0x7f);
            if (i != 0) lsb |= (byte) 0x80; // set sb for all but first group
            bytes[i++] = lsb;
            n >>= 7;
        }
        if (i != 0) n |= 0x80; // set last msb, unless first
        bytes[i++] = (byte) n;

        // bytes is in reverse order
        byte[] buf = new byte[i];
        for (int j=0; j<buf.length; ++j) {
            buf[j] = bytes[buf.length-j-1];
        }
        return ByteFns.toHex(buf);
    }

    @Override
    public String toString() {
        return "VarLenQuant{" +
                "value=" + value +
                ", nbytes=" + nbytes +
                '}';
    }

    static class FileStreamByteIterator implements Iterator<Integer> {
        private final BufferedInputStream file;
        public FileStreamByteIterator(BufferedInputStream file) {
            this.file = file;
        }

        @Override
        public boolean hasNext() {
            try {
                return file.available() > 0;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Integer next() {
            try {
                var ret = file.read();
                if (ret == -1) throw new EOFException("End of the file reached while attempting to read from it.");
                return ret;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
