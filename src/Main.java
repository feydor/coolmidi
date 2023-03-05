import Midi.ByteFns;
import Midi.Midi;
import Midi.MidiEventSubType;
import Midi.MidiEventType;

import javax.sound.midi.*;
import java.io.IOException;
import java.util.Arrays;

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

    public static void main(String[] args) throws IOException, MidiUnavailableException, InvalidMidiDataException {
        Midi midi = new Midi("bowser_1.mid");

        // Get the default MIDI device
        var devices = MidiSystem.getMidiDeviceInfo();
        System.out.println("# of devices: " + devices.length);
        System.out.println(Arrays.toString(devices));

        MidiDevice device = MidiSystem.getMidiDevice(devices[2]);
        device.open();
        Receiver receiver = device.getReceiver();

        // Assuming format#1 MIDI:
        // 1. Send the track#1 as it contains timing events
        // 2. Send events from track#2+ simultaneously
        int globalTempo = 80000; // in microseconds per quarter-note
        for (var event : midi.tracks.get(0).events) {
            MetaMessage msg = new MetaMessage();

            var parsed = event.parseAsMetaEvent();
            msg.setMessage(parsed.type(), parsed.data(), parsed.len());

            // Update the global tempo
            if (event.subType == MidiEventSubType.SET_TEMPO) {
                String tempoData = ByteFns.toHex(parsed.data());
                globalTempo = Integer.parseUnsignedInt(tempoData, 16) / 5; // TODO: Why is this divided by 5?
                System.out.println("TEMPO change for track#" + 0 + ": " + globalTempo);
            }

            sleep(200);
            receiver.send(msg, -1);
        }

        int[] runningTimes = new int[midi.tracks.size()];

        for (int i = 1; i < midi.tracks.size(); ++i) {
            var track = midi.tracks.get(i);
            int finalI = i;
            int finalHeaderTempo = globalTempo;
            new Thread(() -> {
                int currentTempo = finalHeaderTempo; // in microseconds per quarter-note

                for (var event : track.events) {
                    if (event.type == MidiEventType.MIDI) {
                        ShortMessage msg = new ShortMessage();

                        if (event.subType.isTwoByteChannelType()) {
                            var parsed = event.parseAsChannelMidiEvent();
                            try {
                                msg.setMessage(parsed.cmd(), parsed.channel(), parsed.data1(), -1);
                            } catch (InvalidMidiDataException e) { throw new RuntimeException(e); }
                        } else {
                            var parsed = event.parseAsNormalMidiEvent();
                            try {
                                msg.setMessage(parsed.status(), parsed.data1(), parsed.data2());
                            } catch (InvalidMidiDataException e) { throw new RuntimeException(e); }
                        }

                        sleep(midi.eventDurationInMs(event, currentTempo));
                        receiver.send(msg, -1);
                    } else if (event.type == MidiEventType.META || event.subType.isTimingRelated()) {
                        MetaMessage msg = new MetaMessage();

                        var parsed = event.parseAsMetaEvent();
                        try {
                            msg.setMessage(parsed.type(), parsed.data(), parsed.len());
                        } catch (InvalidMidiDataException e) { throw new RuntimeException(e); }

                        System.out.println("META_MESSAGE in track # " + finalI + ", " + event.subType);

                        // Update the global tempo
                        if (event.subType == MidiEventSubType.SET_TEMPO) {
                            String tempoData = ByteFns.toHex(parsed.data());
                            currentTempo = Integer.parseUnsignedInt(tempoData, 16) / 5; // TODO: Why is this divided by 5?
                            System.out.println("TEMPO change for track#" + finalI + ": " + currentTempo);
                        }
                        sleep(midi.eventDurationInMs(event, currentTempo));
                        receiver.send(msg, -1);
                    } else {
                        throw new RuntimeException("WTF. SYEX encountered?");
                    }

                    runningTimes[finalI] += event.ticks;
                }
                 System.out.println("END of track#" + finalI + " with total runtime=" + runningTimes[finalI]);
            }).start();
        }

        System.out.println("\nPrinting runtimes of each track...");
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < midi.tracks.size(); ++i) {
            System.out.printf("track #%d: %d\n", i, runningTimes[i]);
            if (runningTimes[i] > max) max = runningTimes[i];
        }
        System.out.printf("Song runtime: %d\n", max);
    }
}