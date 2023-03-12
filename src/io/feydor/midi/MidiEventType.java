package io.feydor.midi;

import io.feydor.midi.util.Pair;

/**
 * All supported io.feydor.Midi event types by status byte(s)
 */
public enum MidiEventType {
    /** 0x80 to 0xEF */
    MIDI(0x80),
    META(0xFF),
    SYSEX(0xF0),
    UNKNOWN(0xDEADBEEF);

    // META_TIMING_RELATED(0xFF0), Marker, Cue Point, Tempo, SMPTE Offset, Time Signature, and Key Signature

    public final int id;

    MidiEventType(int id) {
        this.id = id;
    }

    /**
     * Returns the even'ts type. Handles running status when the last event was a MIDI event and the status byte is 'missing'.
     *
     * @param status The upper status byte
     * @return The identifying tag
     */
    public static Pair<MidiEventType, Boolean> fromStatusByte(short status, Midi.MidiChunk.Event prevEvent) {
        short upperNibble = (short) ((status >> 4) & 0xF);
        return switch (status) {
            case 0xFF -> new Pair<>(META, false);
            case 0xF0, 0xF7 -> new Pair<>(SYSEX, false);
            default -> switch (upperNibble) {
                case 0x8, 0x9, 0xA, 0xB, 0xC, 0xD, 0xE -> new Pair<>(MIDI, false);
                default -> {
                    // TODO: Might want to pass in the prevEvent ans just return it's status?
                    // Instead of assumming it will always be MIDI, it might though
                    if (prevEvent != null && prevEvent.type == MIDI) {
                        yield new Pair<>(MIDI, true);
                    } else {
                        yield new Pair<>(UNKNOWN, false);
                    }
                }
            };
        };
    }
}
