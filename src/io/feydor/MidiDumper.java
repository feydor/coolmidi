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
    }

    private static String midiChannelsToString(boolean[] channels) {
        var sb = new StringBuilder();
        for (int i = 0; i < channels.length; i++)
            sb.append(String.format("c%d=%c ", i+1, channels[i] ? '1' : '0'));
        return sb.toString();
    }

    private static <T> String addNewlinesToList(List<T> list) {
        var sb = new StringBuilder();
        for (var item : list)
            sb.append(item).append("\n");
        return sb.toString();
    }
}
