package io.feydor;

import io.feydor.midi.Midi;

import java.io.IOException;

/**
 * Dumps the header (and optionally the events) in a MIDI file
 */
public class MidiDumper {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("\nCOOL Midi Dumper\n\nUsage: cmidid [MIDI File]\n\n");
            return;
        }

        dumpMidiFile(args[0]);
    }

    private static void dumpMidiFile(String midiFile) {
        Midi midi;
        try {
             midi = new Midi(midiFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        System.out.println("Dumping MIDI file...");
        String out = String.format("filename: %s\nheader: %s\nchannels used (Max MIDI channels=16): %s\n", midi.filename, midi.header,
                midiChannelsToString(midi.channelsUsed));
        double msPerTick = midi.tracks.get(0).tempo / (double)midi.header.tickdiv / 1000.0;
        int bpm = 60_000_000 / midi.tracks.get(0).tempo;
        out += String.format("ms/tick=%f bpm=%d\n", msPerTick, bpm);
        System.out.print(out);

        System.out.println("Track|Bytes|#Events");
        for (var track : midi.tracks) {
            System.out.printf("%02d|%05d|%05d\n", track.trackNum, track.len, track.events.size());
        }

        // dump bytes
        System.out.println("Dumping parsed bytes...");
        String bytes = midi.hexdump();
        System.out.println(formatBytes(bytes, 0, 60));
    }

    private static String formatBytes(String bytes, int spacing, int col) {
        var sb = new StringBuffer(bytes.length());
        for (int i=0; i<bytes.length(); i++) {
            if (spacing != 0 && i != 0 && i % spacing == 0) {
                sb.append(" ");
            }
            if (col != 0 && i != 0 && i % col == 0) {
                sb.append("\n");
            }
            sb.append(bytes.charAt(i));
        }
        return sb.toString();
    }

    private static String midiChannelsToString(boolean[] channels) {
        var sb = new StringBuilder();
        for (int i = 0; i < channels.length; i++)
            sb.append(String.format("c%d=%c ", i+1, channels[i] ? '1' : '0'));
        return sb.toString();
    }
}
