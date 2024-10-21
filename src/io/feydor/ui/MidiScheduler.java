package io.feydor.ui;

import io.feydor.midi.Midi;
import io.feydor.midi.MidiEventSubType;
import io.feydor.util.ByteFns;

import javax.sound.midi.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MidiScheduler {

    private final ExecutorService executor = Executors.newFixedThreadPool(2 * Runtime.getRuntime().availableProcessors());
    private final MidiUi ui;
    private final List<Midi> playlist;
    private final boolean verbose;
    private volatile boolean listeningToController;

    record EventBatch(int relativeTicks, List<Midi.MidiChunk.Event> events) {}

    public MidiScheduler(MidiUi ui, List<Midi> playlist, boolean verbose) {
        this.ui = ui;
        this.playlist = playlist;
        this.verbose = verbose;
    }

    /** Play all the loaded files */
    public void scheduleEventsAndWait(boolean loop) throws Exception {
        // For each MIDI file,
        // i. Extract the # of channels used into a map of channel# and its current value
        // ii. Sequence and play the file in a new thread, passing in the channels map to keep track of note values
        // iii. In the thread, display the UI
        MidiController midiController = new MidiController(playlist, verbose);
        Midi currentlyPlaying;
        while ((currentlyPlaying = midiController.getNextMidi()) != null) {
            System.out.println("INFO: Playing: " + currentlyPlaying.filename + "...");
            var scheduledThreads = doSingleThreadedScheduling(currentlyPlaying, midiController);
            ui.block(currentlyPlaying, midiController);
            if (!listeningToController)
                spawnMidiControllerListeningThread(midiController);
            var futures = executor.invokeAll(scheduledThreads);
            for (var f : futures) {
                f.get();
            }
        }

        midiController.close();
        executor.shutdown();
        System.out.println("END");
        System.exit(0);
    }

    private List<Callable<Object>> doSingleThreadedScheduling(Midi midi, MidiController midiController) {
        List<EventBatch> eventsByRelTicks = getEventBatchesByRelativeTicks(midi);

        List<Callable<Object>> scheduledThreads = new ArrayList<>();
        scheduledThreads.add(Executors.callable(() -> scheduleEvents(midi, eventsByRelTicks, midiController)));
        return scheduledThreads;
    }

    private List<Callable<Object>> doThreadPerTrackScheduling(Midi midi, MidiController midiController) {
        List<Callable<Object>> scheduledThreads = new ArrayList<>(midi.numTracks());
        for (var track : midi.getTracks()) {
            scheduledThreads.add(Executors.callable(() -> {
                try {
                    scheduleTrack(midi, track, midiController);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
        }
        return scheduledThreads;
    }

    private List<EventBatch> getEventBatchesByRelativeTicks(Midi midi) {
        List<Midi.MidiChunk.Track> tracks = midi.getTracks();
        List<Midi.MidiChunk.Track> absoluteTicksTracks = new ArrayList<>();
        for (var track : tracks) {
            List<Midi.MidiChunk.Event> absEvents = new ArrayList<>();
            int t = 0;
            for (var event : track.events) {
                t += event.ticks;
                Midi.MidiChunk.Event absEvent = new Midi.MidiChunk.Event(event, t);
                absEvents.add(absEvent);
            }

            absoluteTicksTracks.add(new Midi.MidiChunk.Track(track, absEvents));
        }

        List<Midi.MidiChunk.Event> allEvents = absoluteTicksTracks.stream().flatMap(track -> track.events.stream()).toList();
        Map<Integer, List<Midi.MidiChunk.Event>> byAbsTicks = new TreeMap<>();
        for (var event : allEvents) {
            if (!byAbsTicks.containsKey(event.ticks)) {
                byAbsTicks.put(event.ticks, new ArrayList<>());
            }
            var list = byAbsTicks.get(event.ticks);
            list.add(event);
            byAbsTicks.put(event.ticks, list);
        }

        List<EventBatch> byRelTicks = new ArrayList<>();
        byRelTicks.add(new EventBatch(0, byAbsTicks.get(0))); // seed with time = 0 events
        Map.Entry<Integer, List<Midi.MidiChunk.Event>> prevEntry = null;
        for (var entry : byAbsTicks.entrySet()) {
            if (prevEntry != null) {
                int relTicks = entry.getKey() - prevEntry.getKey();
                byRelTicks.add(new EventBatch(relTicks, entry.getValue()));
            }
            prevEntry = entry;
        }

        return byRelTicks;
    }

    private void scheduleEvents(Midi midi, List<EventBatch> eventsByRelTicks, MidiController midiController) {
        long ticks = 0;

        for (var eventBatch : eventsByRelTicks) {
            while (!midiController.isPlaying())
                Thread.onSpinWait();
            if (midiController.hasQuitPlayingImmediately()) {
                midiController.closeReceiver();
                break;
            }

            double elapsedMicros = eventBatch.relativeTicks() * midi.microsPerTick();
            long toDelay = (long) (elapsedMicros * 1_000.0);
            busySleep(toDelay);
            ticks += eventBatch.relativeTicks();

            // Now play all the events for this tick
            for (var event : eventBatch.events()) {
                if (verbose && event.subType == MidiEventSubType.SET_TEMPO) {
                    int newTempo = Integer.parseUnsignedInt(event.message.substring(6), 16);
                    System.out.printf("WARN: Encountered SET_TEMPO event @tick=%d! currentGlobalTempo=%s, newTempo=%d\n", ticks, midi.getTracks().get(0).getTempo(), newTempo);
                    midi.updateGlobalTempo(newTempo);
                }
                midiController.sendEvent(event);
            }
        }
    }

    private void scheduleTrack(Midi midi, Midi.MidiChunk.Track track, MidiController midiController) {
        long ticks = 0;
        long time = 0;

        for (int i=0; i < track.events.size(); ++i) {
            var event = track.events.get(i);
            if (event.ticks > 0) {
                double elapsedMicros = event.ticks * midi.microsPerTick();
                busySleep((long) (elapsedMicros * 1_000.0));
                ticks += event.ticks;
                time += (long) elapsedMicros;
            }

            if (event.subType == MidiEventSubType.SET_TEMPO) {
                int newTempo = Integer.parseUnsignedInt(event.message.substring(6), 16);
                System.out.printf("WARN: Encountered SET_TEMPO event @event=%d! currentGlobalTempo=%s, newTempo=%d, track=%d\n", i, midi.getTracks().get(0).getTempo(), newTempo, track.trackNum);
                midi.updateGlobalTempo(newTempo);
            } else if (event.subType == MidiEventSubType.TIME_SIGNATURE) {
                System.out.printf("WARN: Encountered TIME_SIGNATURE change! oldTimeSig=%s, event=%s\n", midi.getTracks().get(0).timeSignature, event);
            } else if (event.subType == MidiEventSubType.MARKER) {
                byte[] msgBytes = ByteFns.fromHex(event.message.substring(event.dataStart * 2, (event.dataStart + event.dataLen)*2));
                String msg = ByteFns.toAscii(msgBytes);
                System.out.println("WARN: Encountered MARKER! " + event);
            } else if (event.subType == MidiEventSubType.SMPTE_OFFSET) {
                System.out.printf("WARN: Encountered SMPTE_OFFSET event! event=%s\n", event);
            }

            midiController.sendEvent(event);
        }
    }

    public static void busySleep(long nanos) {
        long elapsed;
        final long startTime = System.nanoTime();
        do {
            elapsed = System.nanoTime() - startTime;
        } while (elapsed < nanos);
    }

    private void spawnMidiControllerListeningThread(MidiController midiController) {
        listeningToController = true;
        executor.submit(() -> {
            // TODO: shutdown and restart on each midi track?
           while (listeningToController) {
               var event = midiController.listenForMidiEvent();
               midiController.sendEvent(event);
           }
        });
    }
}
