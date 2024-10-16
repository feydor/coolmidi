package io.feydor.ui;

import io.feydor.midi.*;
import io.feydor.midi.MidiChannel;
import io.feydor.util.ByteFns;

import javax.sound.midi.*;
import java.util.*;
import java.util.concurrent.*;

public class MidiScheduler {

    private final ExecutorService executor = Executors.newFixedThreadPool(20);
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
//                var eventBatches = midi.allEventsInAbsoluteTime();
//                TotalTime timeUntilLastEvent = new TotalTime(eventBatches.get(eventBatches.size()-1).get(0).absoluteTime);
                List<Callable<Object>> scheduledThreads = new ArrayList<>(midi.numTracks());
                for (var track : midi.getTracks()) {
                    scheduledThreads.add(Executors.callable(() -> {
                        try {
                            scheduleTrack(midi, track, channels);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }));
                }
                var futures = executor.invokeAll(scheduledThreads);

                // Display UI while playing thread

                // block until all tracks completed
                for (var f : futures) {
                    f.get();
                }

                // Display the UI while the playing thread sleeps
//                var schedulerThread = scheduledThreads.get(scheduledThreads.size()-1);
//                if (ui != null) {
//                    ui.block(midi, schedulerThread, channels, timeUntilLastEvent);
//                } else {
//                    while (!schedulerThread.isDone());
//                }
            }
        } while (loop);

        executor.shutdown();
        System.out.println("END");
        System.exit(0);
    }

    private void scheduleTrack(Midi midi, Midi.MidiChunk.Track track, MidiChannel[] channels) throws Exception {
        long ticks = 0;
        long time = 0;
        long processingTime = 0;

        for (int i=0; i <track.events.size(); ++i) {
            var event = track.events.get(i);
            double elapsedTime = event.ticks * midi.msPerTick();
            Thread.sleep((long)elapsedTime);
            ticks += event.ticks;
            time += (long)elapsedTime;

            if (event.subType == MidiEventSubType.SET_TEMPO) {
                // handle global tempo change
                int newTempo = Integer.parseUnsignedInt(event.message.substring(6), 16);
//                midi.updateGlobalTempo(newTempo);
                System.out.printf("Detected unsupported SET_TEMPO event with newTempo=%d with trknum=%d @ time=%d, ticks=%d, globalTempo=%d\n", newTempo, track.trackNum, time, ticks, midi.getTracks().get(1).getTempo());
            } else if (event.subType == MidiEventSubType.TIME_SIGNATURE) {
                System.out.println("WARNING: Encountered TIME_SIGNATURE change! " + event);
            } else if (event.subType == MidiEventSubType.MARKER) {
//                System.out.println("WARNING: Encountered MARKER! " + event);
                byte[] msgBytes = ByteFns.fromHex(event.message.substring(event.dataStart * 2, (event.dataStart + event.dataLen)*2));
                String msg = ByteFns.toAscii(msgBytes);
                System.out.println(msg);
            }

            sendEvent(event, channels);
        }
    }

    private void sendEvent(Midi.MidiChunk.Event event, MidiChannel[] channels) {
        MidiMessage msg;
        try {
            msg = makeMidiMessage(event, channels);
        } catch (InvalidMidiDataException e) {
            throw new RuntimeException(e);
        }
        if (msg != null) {
            receiver.send(msg, -1);
        }
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
        // Schedule the events in absolute time, each batch is scheduled for the same time

        List<List<Midi.MidiChunk.Event>> eventsByAbsoluteTime = midi.allEventsInAbsoluteTime();
        long start = System.nanoTime();
        for (int i=0; i<eventsByAbsoluteTime.size(); ++i) {
            var batch = eventsByAbsoluteTime.get(i);
            List<Midi.MidiChunk.Event> nextBatch = null;
            if (i < (eventsByAbsoluteTime.size() - 1)) {
                nextBatch = eventsByAbsoluteTime.get(i+1);
            }
            double ms = batch.get(0).absoluteTime;
            // sleep the difference between when the event should be fired and the current song time in ms
            Thread.sleep((long)(ms - (System.nanoTime() - start)/1_000_000));
            long elapsedTime = (System.nanoTime() - start)/1_000_000; // ms

            boolean tempoChanged = false;
            for (var event : batch) {
                if (event.subType == MidiEventSubType.SET_TEMPO) {
                    if (tempoChanged) {
                        throw new IllegalArgumentException("Cannot change tempo twice in same batch");
                    }
                    if (elapsedTime > 100) {
//                        System.out.println("YEET");
//                        System.out.println(nextBatch);
                    }
                    System.out.println("tempo change @ " + elapsedTime);
                    // handle global tempo change
                    int newTempo = Integer.parseUnsignedInt(event.message.substring(6), 16);
                    midi.updateGlobalTempo(newTempo);
                    eventsByAbsoluteTime = midi.allEventsInAbsoluteTime(elapsedTime); // only affects batches in the future, same number of batches
                    i = 0; // reset i to first batch (the next future batch)
                    tempoChanged = true;
                    // TODO: update timeUntilLastEvent
                }

                sendEvent(event, channels);
            }
        }

//        for (int i=0; i<eventsByAbsoluteTime.entrySet().size(); ++i) {
//            double time = timeItr.next();
//            Thread.sleep(Math.round(time));
//            t += Math.round(time);
//            for (var event : eventsByAbsoluteTime.get(time)) {
//                if (event.subType == MidiEventSubType.SET_TEMPO) {
//                    // handle global tempo change
//                    int newTempo = Integer.parseUnsignedInt(event.message.substring(3), 16);
//                    midi.updateGlobalTempo(newTempo);
//                    eventsByAbsoluteTime = midi.updateAbsoluteTimes(t);
//                }
//
//                MidiMessage msg;
//                try {
//                    msg = makeMidiMessage(event, channels);
//                } catch (InvalidMidiDataException e) {
//                    throw new RuntimeException(e);
//                }
//                if (msg != null)
//                    receiver.send(msg, -1);
//            }
//        }

//        var eventBatches = midi.allEventsInAbsoluteTime();
//        Timer timer = new Timer();
//        long beforeSequencing = System.nanoTime();
//        for (var eventBatch : eventBatches) {
//            long absoluteTimeInMs = Math.round(eventBatch.get(0).absoluteTime);
//            timer.schedule(new TimerTask() {
//                @Override
//                public void run() {
//                    for (var event : eventBatch) {
//                        MidiMessage msg;
//                        try {
//                            msg = makeMidiMessage(event, channels);
//                        } catch (InvalidMidiDataException e) {
//                            throw new RuntimeException(e);
//                        }
//
//                        if (msg != null)
//                            receiver.send(msg, -1);
//                    }
//                }
//            }, absoluteTimeInMs);
//        }

        // Sleep until the last event is played (accounting for how long the above sequencing loop took)
//        long totalSequencingTimeMs = (System.nanoTime() - beforeSequencing) / 1_000_000;
//        Thread.sleep(Math.round(timeUntilLastEvent.ms()));
        System.out.println("DONE playing!");
        return null;
    }

    /**
     * Parses each event into the format required to the Java MidiSystem Receiver.
     * @param event The MIDI event to parse
     * @param channels The map of channels and their values to update
     * @return The formatted message ready to be sent
     * @throws InvalidMidiDataException When an invalid MIDI event is encountered
     */
    private MidiMessage makeMidiMessage(Midi.MidiChunk.Event event, MidiChannel[] channels) throws InvalidMidiDataException {
        return switch (event.type) {
            case MIDI -> {
                var parsed = event.parseAsChannelMidiEvent();
                updateChannels(event, parsed, channels);
                yield new ShortMessage(parsed.cmd(), parsed.channel(), parsed.data1(), parsed.data2());
            }
            case META -> {
                // TODO: META events are not for the Receiver, they are for me to manually adjust
                //  the rest of the event's absolute times
                // FORMAT_1 means track 1 has all of the Global tempo changes
                // FORMAT_2 means each track has its own tempo changes
                yield null;
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
