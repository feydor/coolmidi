package io.feydor.ui.impl;

import io.feydor.midi.Midi;
import io.feydor.midi.MidiEventSubType;
import io.feydor.midi.MidiEventType;
import io.feydor.util.ByteFns;

import java.util.ArrayDeque;
import java.util.Queue;

public class MidiUiEventListener {
    private Queue<Midi.MidiChunk.Event> pendingEvents = new ArrayDeque<>();

    public synchronized Midi.MidiChunk.Event getEventOrNull(long absoluteTime) {
        if (!pendingEvents.isEmpty()) {
            var event = pendingEvents.remove();
            event.setAbsoluteTime(absoluteTime); // to play immediately
            return event;
        }
        return null;
    }

    public synchronized void addProgramChangeEvent(byte channel, byte program) {
        if (channel < 0 || channel > 15) {
            throw new RuntimeException("Attempting to parse an event with a channel NOT within 0 and 15 (inclusive): " + channel);
        }

        // 2 byte message, first byte's upper nibble is status (1100 aka 0x0C), second byte is the new program
        String message = "c" + ByteFns.toHex((byte) (channel & 0xFF)).charAt(1) + ByteFns.toHex((byte)(program & 0xFF));
        System.out.println("program change custom msg = " + message);
        var event = new Midi.MidiChunk.Event(MidiEventType.MIDI, MidiEventSubType.PROGRAM_CHANGE, 1, 1,
                message, false, 1, 1);
        pendingEvents.add(event);
    }

    public void addChannelVolumeEvent(byte channel, byte volume) {
        if (channel < 0 || channel > 15) {
            throw new RuntimeException("Attempting to parse an event with a channel NOT within 0 and 15 (inclusive): " + channel);
        }

        String message = "b" + ByteFns.toHex((byte) (channel & 0xFF)).charAt(1) + "07" + ByteFns.toHex((byte)(volume & 0xFF));
        var event = new Midi.MidiChunk.Event(MidiEventType.MIDI, MidiEventSubType.CONTROLLER, 1, 1,
                message, false, 1, 2);
        System.out.println("program volume msg = " + message);
        pendingEvents.add(event);
    }
}
