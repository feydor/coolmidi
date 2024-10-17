package io.feydor.ui.impl;

import io.feydor.midi.Midi;
import io.feydor.midi.MidiChannel;
import io.feydor.ui.MidiUi;
import io.feydor.ui.TotalTime;

import java.util.concurrent.Future;

public class MidiTuiUi implements MidiUi {
    private final static String[] NOTES = new String[]{"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    @Override
    public void block(Midi midi, Future<Void> playbackThread, MidiChannel[] channels, TotalTime timeUntilLastEvent, MidiUiEventListener uiEventListener) throws Exception {
        // Display the UI while the playing thread sleeps
        nowPlayingUi(midi, channels, timeUntilLastEvent, playbackThread);
    }

    private void nowPlayingUi(Midi midi, MidiChannel[] channels, TotalTime timeRemaining, Future<Void> schedulerThread) throws InterruptedException {
        int filenamePos = 0;
        long ticks = 0;
        int tickLength = 1000; // 1 sec
        int TERM_WIDTH = 100;
        System.out.print("\033[H\033[2J");
        System.out.flush();
        while (!schedulerThread.isDone()) {
            System.out.print("\033[" + 1 + ";" + 1 + "H");
            System.out.println("CoolMidi v0.1.0 " + "/".repeat(TERM_WIDTH - 16));
            System.out.print("\033[" + 2 + ";" + 1 + "H");
            System.out.print(" ".repeat(TERM_WIDTH));
            System.out.print("\r");

            // Scrolling filename with wrap around
            int start = filenamePos;
            int end = start + midi.filename.length();
            int diff = TERM_WIDTH - end;
            String overflowChars;
            int pivot;
            if (diff <= -1* midi.filename.length()) {
                filenamePos = 0;
                start = 0;
                System.out.print(" ".repeat(start) + midi.filename);
            } else if (diff < 0) {
                // print overflow characters from filename (starting at the end)
                pivot = Math.abs(diff);
                overflowChars = midi.filename.substring(midi.filename.length()-1-pivot);
                System.out.print(overflowChars);
                System.out.print(" ".repeat(TERM_WIDTH - overflowChars.length() - (midi.filename.length()-overflowChars.length())));
                System.out.print(midi.filename.substring(0, midi.filename.length()-pivot-1));
            } else {
                System.out.print(" ".repeat(start) + midi.filename);
            }

            filenamePos = Math.min(filenamePos+1, TERM_WIDTH + midi.filename.length());

            System.out.print("\033[" + 3 + ";" + 1 + "H");
            System.out.println("time: " + ticks / 1000 + "/" + timeRemaining.asSeconds());

            System.out.print("\033[" + 4 + ";" + 1 + "H");
            for (MidiChannel channel : channels) {
                int ch = channel.channel;
                byte note = channel.note;
                int val = channel.getVolume();
                System.out.print("\r");
                System.out.print(" ".repeat(TERM_WIDTH));
                System.out.print("\r");
                int magnitude = Math.min(Math.max(val - 20, 0), TERM_WIDTH -30);
                String ansiColor = "\u001B[3" + ((magnitude % 7) + 1) + "m"; // Red -> Cyan
                int spaces = ((ch+1) / 10) > 0 ? 0 : 1; // for padding digits
                System.out.println(" ".repeat(spaces) + (ch + 1) + " " + ansiColor + "#".repeat(magnitude) + " " + toMusicalNote(note) + "\u001B[0m");
            }

            // Sleep for tickLength to set a decent refresh rate
            //noinspection BusyWait
            Thread.sleep(tickLength);
            ticks += tickLength;
        }
    }

    /** Maps a MIDI note value (0-127) to a musical note string */
    private String toMusicalNote(int note) {
        if (note < 0 || note > 128) {
            throw new IllegalArgumentException("A MIDI note value must be between the range [0, 127]: " + note);
        }
        if (note == 0) return "";
        return NOTES[note % NOTES.length];
    }
}
