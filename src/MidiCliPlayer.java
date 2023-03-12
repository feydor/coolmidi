import Midi.ByteFns;
import Midi.Midi;
import Midi.MidiEventSubType;

import javax.sound.midi.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

public final class MidiCliPlayer {
    private final List<Midi> playlist;
    private final Receiver receiver;
    private final static String[] NOTES = new String[]{"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printOptions();
            return;
        }

        if (Stream.of(args).anyMatch(arg -> arg.equalsIgnoreCase("-V"))) {
            printVersion();
            return;
        }

        MidiCliPlayer player = new MidiCliPlayer(args);
        player.play();
    }

    public MidiCliPlayer(String[] filenames) throws MidiUnavailableException {
        playlist = Arrays.stream(filenames).map(filename -> {
            try {
                return new Midi(filename);
            } catch (IOException e) {
                System.out.printf("The file failed to load: %s\n%s. Skipping...", filename, e.getMessage());
                return null;
            }
        }).filter(Objects::nonNull).toList();

        // Get the default MIDI device
        var devices = MidiSystem.getMidiDeviceInfo();
        System.out.println("# of devices: " + devices.length);
        System.out.println("Available devices: " + Arrays.toString(devices));
        receiver = MidiSystem.getReceiver();
    }

    /**
     * Play all of the loaded files
     */
    public void play() throws Exception {
        List<String> filenames = playlist.stream().map(m -> m.filename).toList();
        System.out.println("Playing the following files: " + filenames);

        // For each MIDI file,
        // i. Extract the # of channels used into a map of channel# and its current value
        // ii. Sequence and play it in a new thread
        // iii. In the thread, display the UI
        ExecutorService executor = Executors.newSingleThreadExecutor();
        for (Midi midi : playlist) {
            var channels = new HashMap<Integer, Integer>();
            for (int i = 0; i < midi.channelsUsed.length; ++i) {
                if (midi.channelsUsed[i]) {
                    channels.put(i, 0);
                }
            }
            System.out.println("# of channels used: " + channels.size());

            var timeRemaining = new TotalTime(0); // The remaining time left in the file (set by playing thread)
            int tickLength = 100; // The length of each tick in the UI thread (this thread)
            Future<Void> future = executor.submit(() -> play(midi, channels, timeRemaining, tickLength));

            int filenamePos = 0;
            long ticks = 0;
            int TERM_WIDTH = 100;
            System.out.print("\033[H\033[2J");
            System.out.flush();
            while (!future.isDone()) {
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
                if (diff <= -1*midi.filename.length()) {
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

                filenamePos = Math.min(filenamePos+1, TERM_WIDTH +midi.filename.length());

                System.out.print("\033[" + 3 + ";" + 1 + "H");
                System.out.println("time: " + ticks++ + "/" + timeRemaining);

                System.out.print("\033[" + 4 + ";" + 1 + "H");
                for (var entry : channels.entrySet()) {
                    int ch = entry.getKey();
                    int val = entry.getValue();
                    System.out.print("\r");
                    System.out.print(" ".repeat(TERM_WIDTH));
                    System.out.print("\r");
                    int magnitude = Math.min(Math.max(val - 20, 0), TERM_WIDTH -30);
                    String ansiColor = "\u001B[3" + ((magnitude % 7) + 1) + "m"; // Red -> Cyan
                    int spaces = ((ch+1) / 10) > 0 ? 0 : 1; // for padding digits
                    System.out.println(" ".repeat(spaces) + (ch + 1) + " " + ansiColor + "#".repeat(magnitude) + " " + toMusicalNote(channels.get(ch)) + "\u001B[0m");
                }
                //noinspection BusyWait
                Thread.sleep(tickLength);
            }

            System.out.println("Finished playing: " + midi.filename);
        }

        executor.shutdown();
        System.out.println("END");
    }

    public Void play(Midi midi, Map<Integer, Integer> channels, TotalTime timeRemaining, int tickLength) throws InterruptedException {
        // Schedule the events in absolute time
        var eventBatches = midi.allEventsInAbsoluteTime();
        Timer timer = new Timer();
        long beforeSequencing = System.nanoTime();
        for (var eventBatch : eventBatches) {
            long absoluteTimeInMs = Math.round(eventBatch.get(0).absoluteTime);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    for (var event : eventBatch) {
                        MidiMessage msg;
                        try {
                            msg = makeMidiMessage(event, channels);
                        } catch (InvalidMidiDataException e) {
                            throw new RuntimeException(e);
                        }
                        receiver.send(msg, absoluteTimeInMs);
                    }
                }
            }, absoluteTimeInMs);
        }

        // Calculate how long the thread has to sleep until the last event is played
        long totalSequencingTimeMs = (System.nanoTime() - beforeSequencing) / 1_000_000;
        double lastEventTime = eventBatches.get(eventBatches.size()-1).get(0).absoluteTime;
        double timeUntilLastEvent = lastEventTime - totalSequencingTimeMs;
        timeRemaining.t = (int)(timeUntilLastEvent / tickLength);
        Thread.sleep(Math.round(timeUntilLastEvent));
        return null;
    }

    private MidiMessage makeMidiMessage(Midi.MidiChunk.Event event, Map<Integer, Integer> channels) throws InvalidMidiDataException {
        return switch (event.type) {
            case MIDI -> {
                ShortMessage msg = new ShortMessage();
                var parsed = event.parseAsChannelMidiEvent();
                if (event.subType == MidiEventSubType.NOTE_ON || event.subType == MidiEventSubType.PITCH_BEND ||
                        event.subType == MidiEventSubType.POLYPHONIC_PRESSURE) {
                    channels.put(parsed.channel(), parsed.data1());
                } else if (event.subType == MidiEventSubType.NOTE_OFF) {
                    channels.put(parsed.channel(), 0);
                }

                msg.setMessage(parsed.cmd(), parsed.channel(), parsed.data1(), parsed.data2());
                yield msg;
            }
            case META -> {
                var parsed = event.parseAsMetaEvent();
                yield new MetaMessage(parsed.type(), parsed.data(), parsed.len());
            }
            case SYSEX -> {
                SysexMessage msg = new SysexMessage();
                int status = event.type.id;
                byte[] data = ByteFns.fromHex(event.message.substring(2));
                int len = data.length;
                msg.setMessage(status, data, len);
                yield msg;
            }
            case UNKNOWN -> throw new RuntimeException("Encountered a completely unknown event: " + event);
        };
    }

    /** Used to update the time from a thread */
    private static class TotalTime {
        public int t; // in ms
        public TotalTime(int t) { this.t = t; }
        public String toString() { return String.valueOf(t); }
    }

    /** Maps a MIDI note value (0-127) to a musical note string */
    private String toMusicalNote(int note) {
        if (note < 0 || note > 128) {
            throw new IllegalArgumentException("A MIDI note value must be between the range [0, 127]: " + note);
        }
        if (note == 0) return "";
        return NOTES[note % NOTES.length];
    }

    private static void printOptions() {
        String msg = "\nCOOL Midi\n\nUsage: cmidi [MIDI Files]\n\n";
        msg += "Options:\n  -V   Print version information";
        System.out.println(msg);
    }

    private static void printVersion() {
        String msg = "COOl Midi 0.1.0\nCopyright (C) 2023 feydor\n";
        System.out.println(msg);
    }
}
