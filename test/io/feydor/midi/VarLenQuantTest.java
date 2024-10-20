package io.feydor.midi;

import io.feydor.midi.exceptions.MidiParseException;
import io.feydor.util.VarLenQuant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VarLenQuantTest {
    static FileInputStream fileInputStream;
    static BufferedInputStream inputFile;
    static byte[] firstBuffer = new byte[]{(byte)0xFF, 0x7F};
    static byte[] secondBuffer = new byte[]{(byte)0x81, (byte)0x80, (byte)0x80, 0};
    static byte[] thirdBuffer = new byte[]{(byte)0x84, 0x3c};

    @BeforeAll
    static void setup() {
        // write the binary file to use in the tests
        try {
            String binaryFile = "test/varlen.bin";
            FileOutputStream fileOutStream = new FileOutputStream(binaryFile);
            BufferedOutputStream fileOut = new BufferedOutputStream(fileOutStream);
            fileOut.write(firstBuffer);
            fileOut.write(secondBuffer);
            fileOut.write(thirdBuffer);
            fileOut.close();

            // now setup up the readers
            fileInputStream = new FileInputStream(binaryFile);
            inputFile = new BufferedInputStream(fileInputStream);
        } catch (IOException ex) {
            throw new MidiParseException("File not found!");
        }
    }

    @Test
    void varlenParseWorks() throws IOException {
        /*
         * Examples of numbers represented as variable-length quantities
         * Value (hex)	Representation (hex)
         * 00000000 	00
         * 00000040 	40
         * 0000007F 	7F
         * 00000080 	81 00
         * 00002000 	C0 00
         * 00003FFF 	FF 7F
         * 00004000 	81 80 00
         * 00100000 	C0 80 00
         * 001FFFFF 	FF FF 7F
         * 00200000 	81 80 80 00
         * 08000000 	C0 80 80 00
         * 0FFFFFFF 	FF FF FF 7F
         */

        // FF 7F -> 00 00 3F FF
        var res = VarLenQuant.readBytes(inputFile);
        assertEquals(0x3FFF, res.value);
        assertEquals(2, res.nbytes);

        // 81 80 80 00 -> 00200000
        var res1 = VarLenQuant.readBytes(inputFile);
        assertEquals(0x00200000, res1.value);
        assertEquals(4, res1.nbytes);

        // 84 3c -> 02 3c
        var res2 = VarLenQuant.readBytes(inputFile);
        assertEquals(0x023c, res2.value);
        assertEquals(2, res2.nbytes);
    }

    @Test
    void varlenDecodeWithBytesWorks() {
        // FF 7F -> 00 00 3F FF
        var res = VarLenQuant.decode(new int[]{0xFF, 0x7F});
        assertEquals(0x3FFF, res.value);
        assertEquals(2, res.nbytes);

        // 81 80 80 00 -> 00200000
        var res1 = VarLenQuant.decode(new int[]{0x81, 0x80, 0x80, 0x0});
        assertEquals(0x00200000, res1.value);
        assertEquals(4, res1.nbytes);

        // 84 3c -> 02 3c
        var res2 = VarLenQuant.decode(new int[]{0x84, 0x3c});
        assertEquals(0x023c, res2.value);
        assertEquals(2, res2.nbytes);

        var x = VarLenQuant.decode(new int[]{0x98, 0x0});
        assertEquals(3072, x.value);
        assertEquals(2, x.nbytes);
    }

    @Test
    void varlenEncodeWorks() {
        // 00 00 3F FF -> FF 7F
        String res = VarLenQuant.encode(0x00003FFF);
        assertEquals("ff7f", res);

        // 00200000 -> 81 80 80 00
        String res1 = VarLenQuant.encode(0x00200000);
        assertEquals("81808000", res1);

        // 02 3c -> 84 3c
        var res2 = VarLenQuant.encode(0x023c);
        assertEquals("843c", res2);

        String x = VarLenQuant.encode(3072);
        assertEquals("9800", x);

        assertEquals("00", VarLenQuant.encode(0));
    }
}