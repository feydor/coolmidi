import Midi.Midi;
import Midi.MidiEventType;

import javax.sound.midi.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Stream;

public class Main {
    public static void sendMidiMessage(String midiMessage) {
        System.out.println(midiMessage);
    }

    public static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch(InterruptedException ex) {
            throw new RuntimeException("Thread.sleep blew up: " + ms);
        }
    }

    static String[] getBytes(Midi.MidiChunk.Event event) {
        String[] bytes = new String[event.nbytes()];
        int bp = 0;
        for (int i = 1; i < event.message.length(); i += 2) {
            bytes[bp++] = event.message.charAt(i-1) + "" + event.message.charAt(i);
        }
        return bytes;
    }

    public static void main(String[] args) throws IOException, MidiUnavailableException, InvalidMidiDataException {
        Midi midi = new Midi("bowser_1.mid");

        // Get the default MIDI device
        Synthesizer synth = MidiSystem.getSynthesizer();
        synth.open();
        Receiver receiver = synth.getReceiver();

        // Assuming format#1 MIDI:
        // 1. Send the track#1 as it contains timing events
        // 2. Send events from track#2+ simultaneously
        int globalTempo = 150; // in microseconds per quarter-note
        for (var event : midi.tracks.get(0).events) {
            sendMidiMessage(event.message);

            String[] bytes = getBytes(event);
            MetaMessage msg = new MetaMessage();
            int type = Integer.parseInt(bytes[1], 16);
            byte[] data = Arrays.stream(bytes).skip(2).toString().getBytes();
            int len = event.nbytes() - 2;

            if (event.type == MidiEventType.SET_TEMPO) {
                data = Arrays.stream(bytes).skip(3).toString().getBytes();
                len = 3;

                String[] tempoBytes = new String[len];
                System.arraycopy(bytes, len, tempoBytes, 0, len);
                String tempo = String.join("", tempoBytes);
                globalTempo = Integer.parseInt(tempo, 16) / 5;
                System.out.println("TEMPO change for track#" + 0 + ": " + globalTempo);
            }

            msg.setMessage(type, data, len);

            sleep(200);
            receiver.send(msg, -1);
        }

        int[] runningTimes = new int[midi.tracks.size()];

        for (int i = 1; i < midi.tracks.size(); ++i) {
            var track = midi.tracks.get(i);
            int finalI = i;
            int finalHeaderTempo = globalTempo;
            Thread t = new Thread(() -> {
                int currentTempo = finalHeaderTempo; // in microseconds per quarter-note

                for (var event : track.events) {
                    sendMidiMessage(event.message);

                    String[] bytes = getBytes(event);
                    if (event.type == MidiEventType.MIDI) {
                        ShortMessage msg = new ShortMessage();

                        if (bytes[0].charAt(0) == 'C') {
                            // channel messages
                            if (bytes.length != 2) {
                                throw new RuntimeException("C0 is fucked: " + Arrays.toString(bytes));
                            }

                            int cmd = Integer.parseInt(bytes[0], 16);
                            int channel = Integer.parseInt(String.valueOf(bytes[0].charAt(1)), 16);
                            int data1 = Integer.parseInt(bytes[1], 16);
                            try {
                                msg.setMessage(cmd, channel, data1, -1);
                            } catch (InvalidMidiDataException e) {
                                throw new RuntimeException(e);
                            }
                        } else {
                            int status = Integer.parseInt(bytes[0], 16);
                            int data1 = Integer.parseInt(bytes[1], 16);
                            int data2 = Integer.parseInt(bytes[2], 16);
                            try {
                                msg.setMessage(status, data1, data2);
                            } catch (InvalidMidiDataException e) {
                                throw new RuntimeException(e);
                            }
                        }

                        sleep(dtToMs(midi.header, event.ticks, currentTempo));
                        receiver.send(msg, -1);
                    } else if (event.type == MidiEventType.META ||
                            event.type == MidiEventType.META_TIMING_RELATED ||
                            event.type == MidiEventType.SET_TEMPO) {
                        MetaMessage msg = new MetaMessage();
                        int type = Integer.parseInt(bytes[1], 16);
                        byte[] data = Arrays.stream(bytes).skip(2).toString().getBytes();
                        int len = event.nbytes() - 2;

                        if (event.type == MidiEventType.SET_TEMPO) {
                            data = Arrays.stream(bytes).skip(3).toString().getBytes();
                            len = 3;
                            currentTempo = Integer.parseInt(Arrays.stream(bytes).skip(3).toString(), 16);
                            System.out.println("TEMPO change for track#" + finalI + ": " + currentTempo);
                        }

                        try {
                            msg.setMessage(type, data, len);
                        } catch (InvalidMidiDataException e) {
                            throw new RuntimeException(e);
                        }

                        sleep(dtToMs(midi.header, event.ticks, currentTempo));
                        receiver.send(msg, -1);
                    } else {
                        throw new RuntimeException("WTF. SYEX encountered?");
                    }

                    runningTimes[finalI] += event.ticks;
                }
                 System.out.println("END of track#" + finalI + " with total runtime=" + runningTimes[finalI]);
            });
            t.start();
        }

        System.out.println("\nPrinting runtimes of each track...");
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < midi.tracks.size(); ++i) {
            System.out.printf("track #%d: %d\n", i, runningTimes[i]);
            if (runningTimes[i] > max) max = runningTimes[i];
        }
        System.out.printf("Song runtime: %d\n", max);
    }

    /**
     *
     * @param header
     * @param ticks Ticks to convert
     * @param tempo In microseconds per quarter note
     * @return The # of milliseconds represented by ticks
     */
    private static int dtToMs(Midi.MidiChunk.Header header, int ticks, int tempo) {
        if (header.useMetricalTiming) {
            // ticksPerSecond = resolution * (currentTempoInBeatsPerMinute / 60.0)
            // tickSize = 1.0/ticksPerSecong

            /*
             *  If metrical timing is set, bits 0-14 of tickdivision indicates the # of sub-divisions of a quarter note.
                When timcode is set, the first byte specifies the FPS,
                and will be one of the SMPTE standards: 24, 25, 29, or 30.
                Meanwhile the second byte is the # of sub-divisions of a frame.
             */
            System.out.print("tempo(microsec): " + tempo + ", ");
            int ticksPerQuarterNote = (header.tickdiv & 0xFF) & 0x7F; // ppqn
            System.out.print("ppqn: " + ticksPerQuarterNote + "\n");

            double millisecondsPerTick = (((double)tempo / ticksPerQuarterNote) / 1000);
            System.out.println(millisecondsPerTick);
            double timeInMs = ticks * millisecondsPerTick;
            System.out.println(timeInMs);

            // 60000 / (BPM * PPQ)
            return (int)timeInMs;
        } else {
            int fps = (header.tickdiv >> 8) & 0xFF;
            if (Stream.of(24, 25, 29, 30).noneMatch(n -> n == fps)) {
                throw new RuntimeException("FPS was not a valid SMPTE option: " + fps);
            }

            int subdivsPerFrame = (header.tickdiv & 0x00FF);
            return (fps * subdivsPerFrame) / 1000;
        }
    }
}