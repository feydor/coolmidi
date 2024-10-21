package io.feydor.ui;

import io.feydor.midi.Midi;
import io.feydor.midi.MidiChannel;
import io.feydor.midi.MidiEventSubType;
import io.feydor.midi.MidiEventType;
import io.feydor.util.ByteFns;

import javax.sound.midi.*;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MidiController implements Closeable {
    private static final Logger LOGGER = Logger.getLogger( MidiController.class.getName() );

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

    public record MidiChannelEvent(MidiChannel channel, MidiEventSubType eventSubType) {}

    public MidiController(List<Midi> midiPlaylist, boolean verbose) throws MidiUnavailableException {
        if (midiPlaylist.isEmpty())
            throw new IllegalArgumentException("Midi playlist cannot be empty");
        // Get the default MIDI device and its receiver
        if (verbose) {
            var devices = MidiSystem.getMidiDeviceInfo();
            LOGGER.log(Level.INFO, "Available devices: {0}\n", Arrays.toString(devices));
        }

        this.receiver = MidiSystem.getReceiver();
        this.midiPlaylist = midiPlaylist;
        this.channels = new MidiChannel[16];
        this.currentMidiIndex = -1;
        this.verbose = verbose;
        refreshChannels(midiPlaylist.get(0));
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
        midiPlaylist.add(newMidi);
        muteAllChannels(); // Needed for smooth transition to next file
        quitPlayingImmediately = true;
        currentMidiLooping = false;
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
        assertValidChannel(channel);
        if (program < 0 || program > 127) {
            throw new IllegalArgumentException("Program must be between 0 and 127 (inclusive): " + program);
        }

        // 2 byte message, first byte's upper nibble is status (1100 aka 0x0C), second byte is the new program
        String message = "c" + ByteFns.toHex((byte) (channel & 0xFF)).charAt(1) + ByteFns.toHex((byte)(program & 0xFF));
        createAndPutNewMidiEvent(message, MidiEventSubType.PROGRAM_CHANGE, 1);
        LOGGER.log(Level.INFO, "Added PROGRAM_CHANGE event: channel={0}, program={1}", new Object[]{channel, program});
    }

    public void addChannelVolumeEvent(byte channel, byte volume) {
        assertValidChannel(channel);

        String message = "b" + ByteFns.toHex((byte) (channel & 0xFF)).charAt(1) + "07" + ByteFns.toHex((byte)(volume & 0xFF));
        createAndPutNewMidiEvent(message, MidiEventSubType.CONTROLLER, 2);
        LOGGER.log(Level.INFO, "Added CHANNEL_VOLUME event: channel={0}, volume={1}", new Object[]{channel, volume});
    }

    private void addNoteOffEvent(MidiChannel channel) {
        String msg = "8" + ByteFns.toHex((byte) (channel.channel & 0xFF)).charAt(1) + ByteFns.toHex(channel.note) + "7F";
        createAndPutNewMidiEvent(msg, MidiEventSubType.NOTE_OFF, 2);
    }

    private void createAndPutNewMidiEvent(String message, MidiEventSubType subType, int dataLen) {
        var event =  new Midi.MidiChunk.Event(MidiEventType.MIDI, subType, 1, 1,
                message, false, 1, dataLen);
        try {
            pendingMidiEvents.put(event);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void refreshChannels(Midi midi) {
        for (int j = 0; j < 16; ++j) {
            channels[j] = new MidiChannel(j + 1, midi.channelsUsed[j]);
        }
        if (verbose)
            LOGGER.log(Level.INFO, "# of channels used: {0}",
                    Arrays.stream(channels).mapToInt(ch -> ch.used ? 1 : 0).sum());
    }

    private static void assertValidChannel(byte channel) {
        if (channel < 0 || channel > 15) {
            throw new IllegalArgumentException("Channel must be within 0 and 15 (inclusive): " + channel);
        }
    }

    /**
     * Used to update and broadcast events for single channels
     * @param event usually a midi event
     * @param parsed data parsed from the event
     */
    public synchronized void updateChannels(Midi.MidiChunk.Event event, Midi.MidiChunk.ChannelMidiEventParseResult parsed) {
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

        try {
            pendingChannelEvents.put(new MidiChannelEvent(channel, event.subType));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
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

    public void sendEvent(Midi.MidiChunk.Event event) {
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
    private MidiMessage makeMidiMessage(Midi.MidiChunk.Event event) throws InvalidMidiDataException {
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
