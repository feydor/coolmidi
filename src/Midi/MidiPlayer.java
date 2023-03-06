package Midi;

import javax.sound.midi.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MidiPlayer {
    private final List<Midi> midiFiles;
    private static final int DEVICE_NUM = 3;

    private final Receiver receiver;

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
        System.out.println("Choosing device#" + DEVICE_NUM + ": " + devices[DEVICE_NUM]);

        MidiDevice device = MidiSystem.getMidiDevice(devices[DEVICE_NUM]);
        device.open();
        receiver = device.getReceiver();
    }

    /**
     * Play all of the loaded files
     */
    public void play() throws Exception {
        var filenames = midiFiles.stream().map(m -> m.filename).toList();
        System.out.println("Playing the following files: " + filenames);

        for (var midi : midiFiles) {
            var futures = play(midi);

            long startTime = System.currentTimeMillis() / 1000;
            Timer timer = new Timer();
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    long dt = (System.currentTimeMillis() / 1000) - startTime;
                    System.out.print(dt + " ");
                }
            }, 0, 1000);


            for (var future : futures) {
                future.get();
            }

            System.out.println("Finished playing: " + midi.filename);
            timer.cancel();
        }

        System.out.println("END");
    }

    /**
     * Plays a single MIDI file until completion
     * @param midi The parsed MIDI file to play
     * @return A future letting the caller know when playback is ended
     */
    private List<Future<Void>> play(Midi midi) throws Exception {

        // Assuming format#1 MIDI:
        // 1. Send the track#1 as it contains timing events
        // 2. Send events from track#2+ simultaneously
        int globalTempo = 80000; // in microseconds per quarter-note
        for (var event : midi.tracks.get(0).events) {
            MetaMessage msg = new MetaMessage();

            var parsed = event.parseAsMetaEvent();
            msg.setMessage(parsed.type(), parsed.data(), parsed.len());

            System.out.printf("%s: %s: %s\n", event.type, event.subType, event.message);

            // Update the global tempo
            if (event.subType == MidiEventSubType.SET_TEMPO) {
                String tempoData = ByteFns.toHex(parsed.data());
                globalTempo = Integer.parseUnsignedInt(tempoData, 16) / 3; // TODO: Why is this divided by 5?
                System.out.println("TEMPO change for track#" + 1 + ": " + globalTempo);
            }

            sleep(200);
            receiver.send(msg, -1);
        }

        int nthreads = midi.tracks.size()-1;
        var threadPool = Executors.newFixedThreadPool(nthreads);
        var futures = new ArrayList<Future<Void>>();

        for (int i = 1; i < midi.tracks.size(); ++i) {
            var track = midi.tracks.get(i);
            var trackThread = new SingleTrackPlayback(midi, track, receiver, i+1, globalTempo);
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

        public SingleTrackPlayback(Midi midi, Midi.MidiChunk.Track track, Receiver receiver, int trackNum, int initialTempo) {
            this.midi = midi;
            this.track = track;
            this.trackNum = trackNum;
            this.receiver = receiver;
            this.tempo = initialTempo;
        }

        @Override
        public Void call() {
            for (var event : track.events) {
                int duration = midi.eventDurationInMs(event, tempo);
                if (event.type == MidiEventType.MIDI) {
                    ShortMessage msg = new ShortMessage();

                    if (event.subType == MidiEventSubType.UNKNOWN) {
                        System.out.println("Skipped: " + event.message);
                        continue;
                    }

                    if (event.subType.isChannelType()) {
                        var parsed = event.parseAsChannelMidiEvent();

                        if (event.subType == MidiEventSubType.PROGRAM_CHANGE) {
                            System.out.printf("Track #%d: %s: %s\n", trackNum, event.message, parsed);
                        }

                        try {
                            msg.setMessage(parsed.cmd(), parsed.channel(), parsed.data1(), parsed.data2());
                        } catch (InvalidMidiDataException e) { throw new RuntimeException(e); }
                    } else {
                        var parsed = event.parseAsNormalMidiEvent();
                        if (event.subType == MidiEventSubType.PROGRAM_CHANGE) {
                            System.out.printf("IN NORMAL: %s: Track #%d: %s: %s\n", trackNum, event.subType, event.message, parsed);
                        }
                        try {
                            msg.setMessage(parsed.status(), parsed.data1(), parsed.data2());
                        } catch (InvalidMidiDataException e) { throw new RuntimeException(e); }
                    }

                    sleep(duration);
                    receiver.send(msg, duration);
                } else if (event.type == MidiEventType.META || event.subType.isTimingRelated()) {
                    MetaMessage msg = new MetaMessage();

                    var parsed = event.parseAsMetaEvent();
                    System.out.printf("In track#%d: %s: %s: %s parsed=%s\n",
                            1, event.type, event.subType, event.message, parsed);
                    try {
                        msg.setMessage(parsed.type(), parsed.data(), parsed.len());
                    } catch (InvalidMidiDataException e) { throw new RuntimeException(e); }

                    // Update the global tempo
                    if (event.subType == MidiEventSubType.SET_TEMPO) {
                        String tempoData = ByteFns.toHex(parsed.data());
                        tempo = Integer.parseUnsignedInt(tempoData, 16) / 3; // TODO: Why is this divided by 5?
                        System.out.println("TEMPO change for track#" + trackNum + ": " + tempo);
                    }

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
