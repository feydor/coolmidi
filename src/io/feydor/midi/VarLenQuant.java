package io.feydor.midi;

import io.feydor.midi.exceptions.InvalidVarLenParseException;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;

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

    private VarLenQuant(int value, short nbytes) {
        this.value = value;
        this.nbytes = nbytes;
    }

    /**
     * Decode a VarLen from a filestream
     * @param file The filestream to read from
     * @return The value encoded and the # of bytes read to decode it
     * @throws IOException When the file is empty or at EoF
     * @throws InvalidVarLenParseException When more than 4 bytes are used to encode the VarLen
     */
    static public VarLenQuant from(BufferedInputStream file) throws IOException {
        /*
         * Varlen quantity consists of 7-bit chunks, with the MSB of each signaling the last chunk.
         *
         * Only the bottom 7 bits of each byte contributes to the delta-time, the MSB indicates (when set) that another byte follows.
         * This means, the last byte of a delta-time will have its top bit clear.
         */

        /*
         *   VLQ Byte
         * 7 6 5 4 3 2 1 0
         * ---------------
         * A <-   Bn    ->
         *
         * If A is 0, then this is the last VLQ byte in the value.
         * If A is 1, then another VLQ byte follows.
         *
         * B is a 7-bit number (0x00-0x7F) and n is the position in the byte where B0 is the least significant
         */

        // Example:
        // 0x81 -> 1000 0001 -> 000 0001                   = 0x01
        // 0x80 -> 1000 0000 -> 000 0001 000 0000          = 0x80
        // 0x80 -> ""        -> 000 0001 000 0000 000 0000 = 0x4000
        // 0x00 -> 0000 0000 -> 000 0001 000 0000 000 0000 000 0000 = 0x200000

        int val = 0;
        short nbytes = 0;
        while (true) {
            int b = file.read();
            if (b == -1) {
                throw new EOFException("End of the file reached while attempting to read from it.");
            }

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
}
