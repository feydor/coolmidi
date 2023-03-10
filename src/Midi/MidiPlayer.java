package Midi;

import javax.sound.midi.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MidiPlayer {
    private final List<Midi> midiFiles;

    private final Receiver receiver;

    private final int TERM_WIDTH = 100;
    private final static String[] NOTES = new String[]{"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    public MidiPlayer(String[] filenames) throws MidiUnavailableException {
        midiFiles = Arrays.stream(filenames).map(filename -> {
            try {
                return new Midi(filename);
            } catch (IOException e) {
                System.out.printf("The file failed to load: %s\n%s", filename, e.getMessage());
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
        var filenames = midiFiles.stream().map(m -> m.filename).toList();
        System.out.println("Playing the following files: " + filenames);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        for (var midi : midiFiles) {

            var channels = new HashMap<Integer, Integer>();
            for (int i = 0; i < midi.channelUsed.length; ++i) {
                if (midi.channelUsed[i]) {
                    channels.put(i, 0);
                }
            }
            System.out.println("# of channels used: " + channels.size());

            Future<Void> future = executor.submit(() -> {
                play(midi, channels);
                return null;
            });

            final int[] filenamePos = {0};
            long ms = 0;
            while (!future.isDone()) {
//                System.out.print("\033[H\033[2J");
//                System.out.flush();
                System.out.print("\033[" + 1 + ";" + 1 + "H");
                System.out.println("CoolMidi v0.1.0 " + "/".repeat(TERM_WIDTH-16));

                System.out.print("\033[" + 3 + ";" + 1 + "H");
                System.out.print(" ".repeat(TERM_WIDTH));
                System.out.print("\r");
                int start = filenamePos[0];
                int end = start + midi.filename.length();
                int diff = TERM_WIDTH - end;
                String overflowChars;
                int pivot;
                if (diff <= -1*midi.filename.length()) {
                    filenamePos[0] = 0;
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

                filenamePos[0] = Math.min(filenamePos[0]+1, TERM_WIDTH+midi.filename.length());

                System.out.print("\033[" + 4 + ";" + 1 + "H");
                for (var entry : channels.entrySet()) {
                    int ch = entry.getKey();
                    int val = entry.getValue();
                    System.out.print("\r");
                    System.out.print(" ".repeat(TERM_WIDTH));
                    System.out.print("\r");
                    int magnitude = Math.min(Math.max(val - 20, 0), TERM_WIDTH-30);
                    String ansiColor = "\u001B[3" + ((magnitude % 7) + 1) + "m"; // Red -> Cyan
                    int spaces = ((ch+1) / 10) > 0 ? 0 : 1; // for padding digits
                    System.out.println(" ".repeat(spaces) + (ch + 1) + " " + ansiColor + "#".repeat(magnitude) + " " + hexNote(channels.get(ch)) + "\u001B[0m");
                }
                System.out.print(ms++);
                Thread.sleep(100);
            }

            System.out.println("Finished playing: " + midi.filename);
        }

        executor.shutdown();
        System.out.println("END");
    }

    private String hexNote(int note) {
        if (note == 0) return "";
        int index = note % NOTES.length;
        return NOTES[index];
    }

    private MidiMessage makeMidiMessage(Midi.MidiChunk.Event event, HashMap<Integer, Integer> channels) throws InvalidMidiDataException {
        return switch (event.type) {
            case MIDI -> {
                ShortMessage msg = new ShortMessage();
                if (event.subType.isChannelType()) {
                    var parsed = event.parseAsChannelMidiEvent();
                    if (event.subType == MidiEventSubType.NOTE_ON || event.subType == MidiEventSubType.PITCH_BEND ||
                            event.subType == MidiEventSubType.POLYPHONIC_PRESSURE) {
                        channels.put(parsed.channel(), parsed.data1());
                    } else if (event.subType == MidiEventSubType.NOTE_OFF) {
                        channels.put(parsed.channel(), 0);
                    }

                    msg.setMessage(parsed.cmd(), parsed.channel(), parsed.data1(), parsed.data2());
                    yield msg;
                } else {
                    throw new RuntimeException("A NON-Channel MIDI event was encountered!: " + event.message);
                }
            }
            case META -> {
                MetaMessage msg = new MetaMessage();
                var parsed = event.parseAsMetaEvent();
                msg.setMessage(parsed.type(), parsed.data(), parsed.len());

                if (event.subType == MidiEventSubType.SEQUENCER_SPECIFIC) {
                    System.out.println("SEQUENCER SPECIFIC meta-event detected: " + event);
                } else if (event.subType == MidiEventSubType.SET_TEMPO) {
                    System.out.println("TEMPO change in event detected: " + event);
                }

                yield msg;
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

    /**
     * Plays a single MIDI file until completion
     * @param midi The parsed MIDI file to play
     */
    private void play(Midi midi, HashMap<Integer, Integer> channels) throws Exception {
        // Schedule the events in absolute time
        var eventBatches = midi.allEventsInAbsoluteTime();
        Timer timer = new Timer();
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

        // This is how long we have to sleep until the last event is played
        double lastAbsoluteTime = eventBatches.get(eventBatches.size()-1).get(0).absoluteTime;
        System.out.println("Finished sending the last midi events, now sleeping for: " + lastAbsoluteTime + " ms...");
        Thread.sleep(Math.round(lastAbsoluteTime)); // TODO: This is not an accurate time, should add how long it took to schedule the events to this
    }
}
