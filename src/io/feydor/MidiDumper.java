package io.feydor;

import io.feydor.midi.Midi;
import io.feydor.midi.MidiFileFormat;

import java.io.IOException;
import java.util.List;

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
        String out = String.format("filename: %s\nheader: %s\nchannels used (Max MIDI channels=16):\n%s\n", midi.filename, midi.header,
                midiChannelsToString(midi.channelsUsed));
        out += String.format("tracks: \n%s\n", addNewlinesToList(midi.tracks));

        if (midi.header.format == MidiFileFormat.FORMAT_1) {
            out = "Dumping track 1's events...\n";
            out += addNewlinesToList(midi.tracks.get(0).events);
        }

        System.out.println(out);
    }

    private static String midiChannelsToString(boolean[] channels) {
        var sb = new StringBuilder();
        for (int i = 0; i < channels.length; i++)
            sb.append(String.format("c%d=%c ", i+1, channels[i] ? 't' : 'f'));
        return sb.toString();
    }

    private static <T> String addNewlinesToList(List<T> list) {
        var sb = new StringBuilder();
        for (var item : list)
            sb.append(item).append("\n");
        return sb.toString();
    }
}
