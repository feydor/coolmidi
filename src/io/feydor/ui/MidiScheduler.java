package io.feydor.ui;

import io.feydor.midi.*;
import io.feydor.midi.MidiChannel;

import javax.sound.midi.*;
import javax.sound.midi.spi.MidiDeviceProvider;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

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
                MidiChannel[] channels = new MidiChannel[16];
                for (int i=0; i<16; ++i) {
                    channels[i] = new MidiChannel(i+1, midi.channelsUsed[i]);
                }

                if (verbose)
                    System.out.println("# of channels used: " + Arrays.stream(channels).mapToInt(ch -> ch.used ? 1 : 0).sum());

                // Start playback in a new thread which will update the channel map and the time remaining
                // and then sleep until the last event in the file
                var eventBatches = midi.allEventsInAbsoluteTime();
                TotalTime timeUntilLastEvent = new TotalTime(eventBatches.get(eventBatches.size()-1).get(0).absoluteTime);
                Future<Void> schedulerThread = executor.submit(() -> scheduleEventsAndWait(midi, channels, timeUntilLastEvent));

                // Display the UI while the playing thread sleeps
                if (ui != null) {
                    ui.block(midi, schedulerThread, channels, timeUntilLastEvent);
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
     * @param timeUntilLastEvent The file's remaining time. The time of the last event in absolute time.
     * @throws InterruptedException When the thread is interrupted somehow
     */
    private Void scheduleEventsAndWait(Midi midi, MidiChannel[] channels, TotalTime timeUntilLastEvent) throws InterruptedException {
        // Schedule the events in absolute time
        // each batch is scheduled for the same time
        var eventBatches = midi.allEventsInAbsoluteTime();
        Timer timer = new Timer();
        long beforeSequencing = System.nanoTime();
        LongAdder tempoChanges = new LongAdder();
        for (var eventBatch : eventBatches) {
            long absoluteTimeInMs = Math.round(eventBatch.get(0).absoluteTime);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    for (var event : eventBatch) {
                        MidiMessage msg;
                        try {
                            msg = makeMidiMessage(event, channels, tempoChanges);
                        } catch (InvalidMidiDataException e) {
                            throw new RuntimeException(e);
                        }

                        if (msg != null)
                            receiver.send(msg, -1);
                    }
                }
            }, absoluteTimeInMs);
        }

        // Sleep until the last event is played (accounting for how long the above sequencing loop took)
        long totalSequencingTimeMs = (System.nanoTime() - beforeSequencing) / 1_000_000;
        Thread.sleep(Math.round(timeUntilLastEvent.ms() - totalSequencingTimeMs));
        return null;
    }

    /**
     * Parses each event into the format required to the Java MidiSystem Receiver.
     * @param event The MIDI event to parse
     * @param channels The map of channels and their values to update
     * @return The formatted message ready to be sent
     * @throws InvalidMidiDataException When an invalid MIDI event is encountered
     */
    private MidiMessage makeMidiMessage(Midi.MidiChunk.Event event, MidiChannel[] channels, LongAdder tempoChanges) throws InvalidMidiDataException {
        return switch (event.type) {
            case MIDI -> {
                var parsed = event.parseAsChannelMidiEvent();
                updateChannels(event, parsed, channels);
                // TODO: Test with different message if using runningStatus
                // use bach_348.mid
                yield new ShortMessage(parsed.cmd(), parsed.channel(), parsed.data1(), parsed.data2());
//                yield new ShortMessage(ByteFns.fromHex(event.message));
            }
            case META -> {
                var parsed = event.parseAsMetaEvent();
                // TODO: Send only the last SET_TEMPO change? instead of the first
                // also try KEY_SIGNATURE, MARKER, TIME_SIGNATURE
                if (event.subType == MidiEventSubType.SET_TEMPO && tempoChanges.intValue() == 0) {
                    // TODO: SET_TEMPO is not for the Receiver its for me to manually adjust the rest of the event's absolute times
                    // FORMAT_1 means track 1 has all of the Global tempo changes
                    // FORMAT_2 means each track has its own tempo changes
                    tempoChanges.increment();
                } else if (event.subType == MidiEventSubType.SET_TEMPO && tempoChanges.intValue() != 0) {
                    yield null;
                } else if (event.subType == MidiEventSubType.TIME_SIGNATURE || event.subType == MidiEventSubType.KEY_SIGNATURE) {
                    yield null;
                }

                yield new MetaMessage(parsed.type(), ByteFns.fromHex(event.message), event.message.length()/2);
//                yield new MetaMessage(parsed.type(), parsed.data(), parsed.len());
            }
            case SYSEX -> {
                var parsed = event.parseAsSysexEvent();
                yield new SysexMessage(parsed.type(), parsed.data(), parsed.len());
            }
            case UNKNOWN -> throw new RuntimeException("Encountered a completely unknown event: " + event);
        };
    }

    private void updateChannels(Midi.MidiChunk.Event event, Midi.MidiChunk.ChannelMidiEventParseResult parsed, MidiChannel[] channels) {
        var channel = channels[parsed.channel()];
        switch (event.subType) {
            case NOTE_ON -> {
                channel.setNoteOn(true);
                channel.setNote((byte) parsed.data1());
                channel.setNoteVelocity((byte) parsed.data2());
            }
            case NOTE_OFF -> {
                channel.setNoteOn(false);
                channel.setNote((byte) 0);
                channel.setNoteVelocity((byte) 0);
            }
            case POLYPHONIC_PRESSURE -> channel.setPolyphonicPressure((byte) parsed.data1(), (byte) parsed.data2());
            case PITCH_BEND -> {
                int pitch = (parsed.data2() << 7) | parsed.data1();
                channel.setPitchBend(pitch);
            }
            case PROGRAM_CHANGE -> channel.setProgram((byte) parsed.data1());
            case CHANNEL_PRESSURE -> channel.setPressure((byte) parsed.data1());
            case CONTROLLER -> channel.setController((byte) parsed.data1(), (byte) parsed.data2());
        }
    }

}
