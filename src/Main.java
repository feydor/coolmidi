import Midi.MidiPlayer;
import Midi.Midi;
import Midi.MidiEventType;

import javax.sound.midi.*;
import java.util.Arrays;

public class Main {
    static void printOptions() {
        String msg = "\nCOOL Midi\n\nUsage: cmidi [MIDI Files]\n\n";
        msg += "Options:\n  -V   Print version information";
        System.out.println(msg);
    }

    static void printVersion() {
        String msg = "COOl Midi 0.1.0\nCopyright (C) 2023 feydor\n";
        System.out.println(msg);
    }

    public static void main(String[] args) throws Exception {
       if (args.length < 1) {
           printOptions();
           return;
       }

       if (Arrays.stream(args).anyMatch(arg -> arg.equalsIgnoreCase("-V"))) {
           printVersion();
           return;
       }

       MidiPlayer player = new MidiPlayer(args);
       player.play();
    }
}