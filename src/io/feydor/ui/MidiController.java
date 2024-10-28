package io.feydor.ui;

import io.feydor.midi.Midi;
import io.feydor.midi.MidiChannel;
import io.feydor.midi.MidiEventSubType;
import io.feydor.midi.MidiEventType;

import javax.sound.midi.*;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static io.feydor.midi.Midi.MidiChunk.Event.assertValidChannel;

public class MidiController implements Closeable {
    private final IMidiUi midiUi;
    private final MidiScheduler midiScheduler;
    private final List<Midi> midiPlaylist;
    private final MidiChannel[] channels;
    private Receiver receiver;
    private int currentMidiIndex;
    private boolean currentMidiLooping;
    private final boolean verbose;
    private TotalTime timeUntilLastEvent;
    private volatile boolean isPlaying;
    private volatile boolean quitPlayingImmediately;
    private final BlockingQueue<Midi.MidiChunk.Event> pendingMidiEvents = new LinkedBlockingQueue<>();
    private final BlockingQueue<MidiChannelEvent> pendingChannelEvents = new ArrayBlockingQueue<>(16);
    private static final String MID_EXT_REGEX = "^.*\\.(mid|midi)$";
    private static final Logger LOGGER = Logger.getLogger(MidiController.class.getName());

    public record MidiChannelEvent(MidiChannel channel, MidiEventSubType eventSubType) {}

    public MidiController(IMidiUi midiUi, boolean verbose) throws MidiUnavailableException {
        // Get the default MIDI device and its receiver
        if (verbose) {
            var devices = MidiSystem.getMidiDeviceInfo();
            LOGGER.log(Level.INFO, "Available devices: {0}\n", Arrays.toString(devices));
        }

        this.midiUi = midiUi;
        this.midiScheduler = new MidiScheduler(this, verbose);
        this.verbose = verbose;
        this.midiPlaylist = new ArrayList<>();
        this.receiver = MidiSystem.getReceiver();
        this.channels = new MidiChannel[16];
        this.currentMidiIndex = -1;
    }

    public void loadMidiFile(File file) {
        if (!file.exists())
            throw new IllegalArgumentException("File does not exist: " + file.getAbsolutePath());
        midiPlaylist.addAll(parseMidiFiles(file));
    }

    public void startPlaybackFromBeginning() throws Exception {
        if (midiPlaylist.isEmpty())
            throw new IllegalStateException("Midi playlist is empty");
        currentMidiIndex = 0;
        Midi firstMidi = midiPlaylist.get(0);
        refreshChannels(firstMidi);
        isPlaying = true;
        new Thread(() -> {
            while (midiScheduler.isPlayingEvents())
                Thread.onSpinWait();
            try {
                midiScheduler.scheduleEventsAndWait(firstMidi);
            } catch (InterruptedException e) {
                System.out.println("Interrupted: " + e.getMessage());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).start();
    }

    public void initUi(Midi midi) {
        midiUi.initialize(midi, this);
    }

    /** Blocks waiting for input */
    public void waitForInput() {
        midiScheduler.spawnMidiControllerListeningThread(this);
        LockSupport.park();
    }

    public List<Midi> parseMidiFiles(File input) {
        if (input.isDirectory()) {
            File[] dirFiles = input.listFiles((dir, name) -> name.toLowerCase().matches(MID_EXT_REGEX));
            if (dirFiles == null || dirFiles.length == 0)
                throw new IllegalArgumentException("Directory did not contain any .mid or .midi files: " + input.getAbsolutePath());
            return Arrays.stream(dirFiles).map(this::parseMidi).filter(Optional::isPresent).map(Optional::get).toList();
        } else {
            if (!input.getAbsolutePath().matches(MID_EXT_REGEX))
                throw new IllegalArgumentException("Not a valid .mid or .midi file: " + input.getAbsolutePath());
            return Stream.of(parseMidi(input)).filter(Optional::isPresent).map(Optional::get).toList();
        }
    }

    private Optional<Midi> parseMidi(File file) {
        try {
            MidiOverrides overrides = new MidiOverrides(file);
            return Optional.of(new Midi(file.getAbsolutePath(), overrides, verbose));
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to parse file: {0}. Exception: {1}. Skipping...",
                    new Object[]{file.getAbsolutePath(), e.toString()});
            return Optional.empty();
        }
    }

    public Midi getCurrentlyPlaying() {
        return midiPlaylist.get(currentMidiIndex);
    }

    /**
     * @return the next Midi to play, or null if none
     */
    public Midi getNextMidi() throws MidiUnavailableException {
        if (currentMidiLooping) {
            var next = getCurrentlyPlaying();
            // TODO: simplify
            var eventBatches = next.allEventsInAbsoluteTime();
            timeUntilLastEvent = new TotalTime(eventBatches.get(eventBatches.size() - 1).get(0).absoluteTime);
            isPlaying = true;
            quitPlayingImmediately = false;
            receiver = MidiSystem.getReceiver();
            return next;
        } else if (currentMidiIndex + 1 < midiPlaylist.size()) {
            currentMidiIndex++;
            var next = midiPlaylist.get(currentMidiIndex);
            refreshChannels(next);
            // TODO: simplify
            var eventBatches = next.allEventsInAbsoluteTime();
            timeUntilLastEvent = new TotalTime(eventBatches.get(eventBatches.size() - 1).get(0).absoluteTime);
            isPlaying = true;
            quitPlayingImmediately = false;
            receiver = MidiSystem.getReceiver();
            return next;
        } else {
            isPlaying = false;
            receiver.close();
            return null;
        }
    }

    public void toggleCurrentMidiLooping() {
        currentMidiLooping = !currentMidiLooping;
    }

    public synchronized void togglePlaying() {
        isPlaying = !isPlaying;
        if (!isPlaying) {
            muteAllChannels();
        } else {
            for (var channel : channels) {
                addChannelVolumeEvent((byte) (channel.channel - 1), channel.getLastVolume());
            }
        }
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public void replaceCurrentlyPlaying(File file) throws IOException {
        LOGGER.log(Level.INFO, "Stopping play of {0}...", getCurrentlyPlaying().filename);
        Midi newMidi = new Midi(file.getAbsolutePath());
        midiPlaylist.clear(); // TODO
        midiPlaylist.add(newMidi);
        muteAllChannels(); // Needed for smooth transition to next file
//        allNotesOff();
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        midiScheduler.stopAllEvents();
//        quitPlayingImmediately = true;
//        currentMidiLooping = false;
    }

    private void allNotesOff() {
        for (var channel : channels)
            if (channel.used)
                addNoteOffEvent(channel);
    }

    public boolean hasQuitPlayingImmediately() {
        return quitPlayingImmediately;
    }

    private void muteAllChannels() {
        for (MidiChannel channel : channels) {
            addChannelVolumeEvent((byte) (channel.channel - 1), (byte)0);
        }
    }

    public void closeReceiver() {
        receiver.close();
    }

    /**
     * Blocks the calling thread to wait for an event from this controller
     * @return a new Midi event to transmit/handle
     */
    public Midi.MidiChunk.Event listenForMidiEvent() {
        // TODO: Ensure that only 1 "consumer" thread can exist
        try {
            return pendingMidiEvents.take();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Blocks the calling thread to wait for an event from this controller
     * @return a new Midi channel event that occured
     */
    public MidiChannelEvent listenForMidiChannelEvent() {
        // TODO: Ensure that only 1 "consumer" thread can exist
        try {
            return pendingChannelEvents.take();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void addProgramChangeEvent(byte channel, short program) {
        if (program < 0 || program > 127) {
            throw new IllegalArgumentException("Program must be between 0 and 127 (inclusive): " + program);
        }

        var event = Midi.MidiChunk.Event.createProgramChangeEvent(channel, program);
        putNewMidiEvent(event);
        LOGGER.log(Level.INFO, "Added PROGRAM_CHANGE event: channel={0}, program={1}", new Object[]{channel, program});
    }

    public void addChannelVolumeEvent(byte channel, byte volume) {
        var event = Midi.MidiChunk.Event.createChannelVolumeEvent(channel, volume);
        putNewMidiEvent(event);
        LOGGER.log(Level.INFO, "Added CHANNEL_VOLUME event: channel={0}, volume={1}", new Object[]{channel, volume});
    }

    private void addNoteOffEvent(MidiChannel channel) {
        var event = Midi.MidiChunk.Event.createNoteOffEvent(channel.channel, channel.note, 127);
        putNewMidiEvent(event);
    }

    private void putNewMidiEvent(Midi.MidiChunk.Event event) {
        try {
            pendingMidiEvents.put(event);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Refresh the channels with the given midi file
     */
    private synchronized void refreshChannels(Midi midi) {
        for (int j = 0; j < 16; ++j) {
            channels[j] = new MidiChannel(j + 1, midi.channelsUsed[j]);
        }
        if (verbose)
            LOGGER.log(Level.INFO, "# of channels used: {0}",
                    Arrays.stream(channels).mapToInt(ch -> ch.used ? 1 : 0).sum());
    }

    /**
     * Used to update and broadcast events for single channels
     * @param event usually a midi event
     * @param parsed data parsed from the event
     */
    public synchronized void updateChannels(Midi.MidiChunk.Event event, Midi.MidiChunk.ChannelMidiEventParseResult parsed) throws InterruptedException {
        assertValidChannel((byte) parsed.channel());
        MidiChannel channel = channels[parsed.channel()];
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

//        try {
            pendingChannelEvents.put(new MidiChannelEvent(channel, event.subType));
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
    }

    /**
     * Used for broadcasting events that affect all channels
     * @param event usually a meta event
     */
    public synchronized void updateChannels(Midi.MidiChunk.Event event) {
        if (event.type != MidiEventType.META)
            throw new IllegalArgumentException("Only META events can forgo a channel");

        try {
            pendingChannelEvents.put(new MidiChannelEvent(channels[0], event.subType));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }


    public synchronized MidiChannel[] getChannels() {
        return channels;
    }

    public TotalTime getCurrentRemainingTime() {
        return timeUntilLastEvent;
    }

    public void sendEvent(Midi.MidiChunk.Event event) throws InterruptedException {
        MidiMessage msg;
        try {
            msg = makeMidiMessage(event);
        } catch (InvalidMidiDataException e) {
            throw new RuntimeException(e);
        }
        if (msg != null) {
            receiver.send(msg, -1);
        }
    }

    /**
     * Parses each event into the format required to the Java MidiSystem Receiver.
     * @param event The MIDI event to parse
     * @return The formatted message ready to be sent
     * @throws InvalidMidiDataException When an invalid MIDI event is encountered
     */
    private MidiMessage makeMidiMessage(Midi.MidiChunk.Event event) throws InvalidMidiDataException, InterruptedException {
        return switch (event.type) {
            case MIDI -> {
                var parsed = event.parseAsChannelMidiEvent();
                updateChannels(event, parsed);
                yield new ShortMessage(parsed.cmd(), parsed.channel(), parsed.data1(), parsed.data2());
            }
            case META -> {
                // TODO: META events are not for the Receiver, they are for me to manually adjust
                //  the rest of the event's absolute times
                // FORMAT_1 means track 1 has all of the Global tempo changes
                // FORMAT_2 means each track has its own tempo changes
                // TODO
                if (event.subType == MidiEventSubType.SET_TEMPO) {
                    updateChannels(event);
                }

                yield null;
            }
            case SYSEX -> {
                var parsed = event.parseAsSysexEvent();
                yield new SysexMessage(parsed.type(), parsed.data(), parsed.len());
            }
            case UNKNOWN -> throw new RuntimeException("Encountered a completely unknown event: " + event);
        };
    }

    @Override
    public void close() throws IOException {
        receiver.close();
    }
}
