package Midi;

import javax.sound.midi.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;
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

        for (var midi : midiFiles) {

            var channels = new HashMap<Integer, Integer>();
            for (int i = 0; i < midi.channelUsed.length; ++i) {
                if (midi.channelUsed[i]) {
                    channels.put(i, 0);
                }
            }
            System.out.println("# of channels used: " + channels.size());

            var futures = play(midi, channels);

            final int[] filenamePos = {0};
            for (var future : futures) {
                long ms = 0;
//                System.out.print("\033[H\033[2J");
//                System.out.flush();

                while (future.state() == Future.State.RUNNING) {
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
            }

            System.out.println("Finished playing: " + midi.filename);
//            timer.cancel();
        }

        System.out.println("END");
    }

    private String hexNote(int note) {
        if (note == 0) return "";
        int index = note % NOTES.length;
        return NOTES[index];
    }

    /**
     * Plays a single MIDI file until completion
     * @param midi The parsed MIDI file to play
     * @return A future letting the caller know when playback is ended
     */
    private List<Future<Void>> play(Midi midi, HashMap<Integer, Integer> channels) throws Exception {

        int globalTempo = 0x07A120; // 120 bpm in microseconds per quarter-note
        if (midi.header.format == MidiFileFormat.FORMAT_1) {
            // Assuming format#1 MIDI:
            // 1. Send the track#1 as it contains timing events
            // 2. Send events from track#2+ simultaneously
            System.out.println("FORMAT 1 MIDI, sending the first track's global tempo info...");
            for (var event : midi.tracks.get(0).events) {
                if (event.type == MidiEventType.SYSEX) {
                    SysexMessage msg = new SysexMessage();
                    int status = event.type.id;
                    byte[] data = ByteFns.fromHex(event.message.substring(2));
                    int len = data.length;
                    msg.setMessage(status, data, len);
                    sleep(50);
                    receiver.send(msg, -1);
                    continue;
                }

                MetaMessage msg = new MetaMessage();

                var parsed = event.parseAsMetaEvent();
                msg.setMessage(parsed.type(), parsed.data(), parsed.len());

                System.out.printf("%s: %s: %s\n", event.type, event.subType, event.message);

                // Update the global tempo
                if (event.subType == MidiEventSubType.SET_TEMPO) {
                    String tempoData = ByteFns.toHex(parsed.data());
                    globalTempo = Integer.parseUnsignedInt(tempoData, 16) / 2; // 3

                    System.out.println("TEMPO change for track#" + 1 + ": " + globalTempo);
                }

                sleep(50);
                receiver.send(msg, -1);
            }
        }

        int startingTrack = midi.header.format == MidiFileFormat.FORMAT_1 ? 1 : 0;
        int nthreads = midi.header.format == MidiFileFormat.FORMAT_1 ? midi.tracks.size()-1
                                                                     : midi.tracks.size();
        var threadPool = Executors.newFixedThreadPool(nthreads);
        var futures = new ArrayList<Future<Void>>();

        for (int i = startingTrack; i < midi.tracks.size(); ++i) {
            var track = midi.tracks.get(i);
            var trackThread = new SingleTrackPlayback(midi, track, receiver, i, globalTempo, channels);
            futures.add(threadPool.submit(trackThread));
        }

        threadPool.shutdown();

        return futures;
    }

    static class SingleTrackPlayback implements Callable<Void> {
        Midi midi;
        Midi.MidiChunk.Track track;
        int trackNum;
        int tempo; // in microseconds per quarter-note
        Receiver receiver;
        HashMap<Integer, Integer> channels;

        public SingleTrackPlayback(Midi midi, Midi.MidiChunk.Track track,
                                   Receiver receiver, int trackNum, int initialTempo, HashMap<Integer, Integer> channels) {
            this.midi = midi;
            this.track = track;
            this.trackNum = trackNum;
            this.receiver = receiver;
            this.tempo = initialTempo;
            this.channels = channels;
        }

        @Override
        public Void call() throws InvalidMidiDataException {
            for (var event : track.events) {
//                System.out.printf("In track#%d: %s: %s: %s: dt:%02X\n",
//                        trackNum, event.type, event.subType, event.message, event.ticks);

                int duration = midi.eventDurationInMs(event, tempo);
                if (event.type == MidiEventType.MIDI) {
                    ShortMessage msg = new ShortMessage();

                    if (event.subType == MidiEventSubType.UNKNOWN) {
                        System.out.println("Skipped: " + event.message);
                        continue;
                    }

                    if (event.subType.isChannelType()) {
                        var parsed = event.parseAsChannelMidiEvent();
                        if (event.subType == MidiEventSubType.NOTE_ON || event.subType == MidiEventSubType.PITCH_BEND ||
                                 event.subType == MidiEventSubType.POLYPHONIC_PRESSURE) {
                            channels.put(parsed.channel(), parsed.data1());
                        } else if (event.subType == MidiEventSubType.NOTE_OFF) {
                            channels.put(parsed.channel(), 0);
                        }

                        msg.setMessage(parsed.cmd(), parsed.channel(), parsed.data1(), parsed.data2());
                    } else {
                        throw new RuntimeException("A NON-Channel MIDI event was encountered!: " + event.message);
                    }

                    sleep(duration);
                    receiver.send(msg, duration);
                } else if (event.type == MidiEventType.META || event.subType.isTimingRelated()) {
                    MetaMessage msg = new MetaMessage();

                    var parsed = event.parseAsMetaEvent();
                    msg.setMessage(parsed.type(), parsed.data(), parsed.len());

                    // Update the global tempo
                    if (event.subType == MidiEventSubType.SET_TEMPO) {
                        String tempoData = ByteFns.toHex(parsed.data());
                        tempo = Integer.parseUnsignedInt(tempoData, 16) / 3; // TODO: Why is this divided by 5?
                        System.out.println("TEMPO change for track#" + trackNum + ": " + tempo);
                    }

                    sleep(duration);
                    receiver.send(msg, duration);
                } else if (event.type == MidiEventType.SYSEX) {
                  SysexMessage msg = new SysexMessage();
                  int status = event.type.id; // TODO: SYEX HERE?
                  byte[] data = ByteFns.fromHex(event.message.substring(2));
                  int len = data.length;
                  msg.setMessage(status, data, len);
                  sleep(duration);
                  receiver.send(msg, duration);
                } else {
                    throw new RuntimeException("WTF. SYEX encountered?");
                }
            }
            System.out.println("END of track#" + trackNum);
            return null;
        }
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch(InterruptedException ex) {
            throw new RuntimeException("Thread.sleep blew up: " + ms);
        }
    }
}
