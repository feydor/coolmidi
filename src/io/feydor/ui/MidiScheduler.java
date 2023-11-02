package io.feydor.ui;

import io.feydor.midi.*;

import javax.sound.midi.*;
import java.util.*;
import java.util.concurrent.*;

public class MidiScheduler {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MidiUi ui;
    private final List<Midi> playlist;
    private final Receiver receiver;
    private final boolean verbose;

    public MidiScheduler(MidiUi ui, List<Midi> playlist, Receiver receiver, boolean verbose) {
        this.ui = ui;
        this.playlist = playlist;
        this.receiver = receiver;
        this.verbose = verbose;
    }

    /** Play all of the loaded files */
    public void scheduleEventsAndWait(boolean loop) throws Exception {
        // For each MIDI file,
        // i. Extract the # of channels used into a map of channel# and its current value
        // ii. Sequence and play the file in a new thread, passing in the channels map to keep track of note values
        // iii. In the thread, display the UI
        do {
            for (Midi midi : playlist) {
                System.out.println("Playing: " + midi.filename);
                var channels = new HashMap<Integer, Integer>();
                for (int i = 0; i < midi.channelsUsed.length; ++i) {
                    if (midi.channelsUsed[i]) {
                        channels.put(i, 0);
                    }
                }
                if (verbose)
                    System.out.println("# of channels used: " + channels.size());

                // Start playback in a new thread which will update the channel map and the time remaining
                // and then sleep until the last event in the file
                var timeRemaining = new TotalTime(0);
                int tickLength = 100; // The length of each tick in the UI thread (this thread)
                Future<Void> schedulerThread = executor.submit(() -> scheduleEventsAndWait(midi, channels, timeRemaining, tickLength));

                // Display the UI while the playing thread sleeps
                if (ui != null) {
                    ui.block(midi, schedulerThread, channels);
                } else {
                    while (!schedulerThread.isDone());
                }
            }
        } while (loop);

        executor.shutdown();
        System.out.println("END");
        System.exit(0);
    }

    /**
     * Sequences the events in a MIDI file and schedules when they will be sent according to each event's deltatime (in absolute time).
     * Should be run in a new thread.
     * @param midi The file to playback
     * @param channels A map of channels used to their values. Set by this method.
     * @param timeRemaining The file's remaining time. Set by this method.
     * @param tickLength The length of each tick in the calling thread. Used to set the timeRemaining.
     * @throws InterruptedException When the thread is interrupted somehow
     */
    private Void scheduleEventsAndWait(Midi midi, Map<Integer, Integer> channels, TotalTime timeRemaining, int tickLength) throws InterruptedException {
        // Schedule the events in absolute time
        // each batch is scheduled for the same time
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
        // Making sure to set that time in timeRemaining
        long totalSequencingTimeMs = (System.nanoTime() - beforeSequencing) / 1_000_000;
        double lastEventTime = eventBatches.get(eventBatches.size()-1).get(0).absoluteTime;
        double timeUntilLastEvent = lastEventTime - totalSequencingTimeMs;
        timeRemaining.t = (int)(timeUntilLastEvent / tickLength);
        Thread.sleep(Math.round(timeUntilLastEvent));
        return null;
    }

    /**
     * Parses each event into the format required to the Java MidiSystem Receiver.
     * @param event The MIDI event to parse
     * @param channels The map of channels and their values to update
     * @return The formatted message ready to be sent
     * @throws InvalidMidiDataException When an invalid MIDI event is encountered
     */
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

}
