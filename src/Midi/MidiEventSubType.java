package Midi;

import java.util.List;

/** All of the supported Midi/Meta events and their identifying byte */
public enum MidiEventSubType {
    /* MIDI events (status == 1st byte) */
    NOTE_OFF(0x8),
    NOTE_ON(0x9),
    POLYPHONIC_PRESSURE(0xA),
    CONTROLLER(0xB),
    PROGRAM_CHANGE(0xC),
    CHANNEL_PRESSURE(0xD),
    PITCH_BEND(0xE),

    /* Meta events (type == 2nd byte) */
    SEQUENCE_NUMBER(0x0),
    TEXT(0x1),
    COPYRIGHT(0x2),
    TRACK_NAME(0x3),
    INSTRUMENT_NAME(0x4),
    LYRIC(0x5),
    MARKER(0x6),
    CUEPOINT(0x7),
    PROGRAM_NAME(0x8),
    DEVICE_NAME(0x9),
    CHANNEL_PREFIX(0x20),
    MIDI_PORT(0x21),
    END_OF_TRACK(0x2F),
    SET_TEMPO(0x51),
    SMPTE_OFFSET(0x54),
    TIME_SIGNATURE(0x58),
    KEY_SIGNATURE(0x59),

    UNKNOWN(-1);

    final int idByte;

    MidiEventSubType(int id) {
        idByte = id;
    }

    private static final List<MidiEventSubType> CHANNEL_TYPES = List.of(
            PROGRAM_CHANGE, CHANNEL_PRESSURE, CONTROLLER, PITCH_BEND, NOTE_ON, NOTE_OFF, POLYPHONIC_PRESSURE
    );
    private static final List<MidiEventSubType> TIMING_RELATED_TYPES = List.of(MARKER, CUEPOINT, SET_TEMPO, SMPTE_OFFSET, TIME_SIGNATURE, KEY_SIGNATURE);

    /**
     * Only Meta-events have a type byte
     * @param type The byte that identifies a specific Meta event
     * @return The tag specifing the Meta event
     */
    public static MidiEventSubType fromTypeByte(short type) {
        return switch (type) {
            case 0x0 -> SEQUENCE_NUMBER;
            case 0x1 -> TEXT;
            case 0x2 -> COPYRIGHT;
            case 0x3 -> TRACK_NAME;
            case 0x4 -> INSTRUMENT_NAME;
            case 0x5 -> LYRIC;
            case 0x6 -> MARKER;
            case 0x7 -> CUEPOINT;
            case 0x8 -> PROGRAM_NAME;
            case 0x9 -> DEVICE_NAME;
            case 0x20 -> CHANNEL_PREFIX;
            case 0x21 -> MIDI_PORT;
            case 0x2F -> END_OF_TRACK;
            case 0x51 -> SET_TEMPO;
            case 0x54 -> SMPTE_OFFSET;
            case 0x58 -> TIME_SIGNATURE;
            case 0x59 -> KEY_SIGNATURE;
            default -> UNKNOWN;
        };
    }

    public static MidiEventSubType fromStatusNibble(byte status) {
        return switch (status) {
            case 0x8 -> NOTE_OFF;
            case 0x9 -> NOTE_ON;
            case 0xA -> POLYPHONIC_PRESSURE;
            case 0xB -> CONTROLLER;
            case 0xC -> PROGRAM_CHANGE;
            case 0xD -> CHANNEL_PRESSURE;
            case 0xE -> PITCH_BEND;
            default -> UNKNOWN;
        };
    }

    public boolean isChannelType() {
        return CHANNEL_TYPES.stream().anyMatch(st -> this == st);
    }

    /** META_TIMING_RELATED(0xFF0), Marker, Cue Point, Tempo, SMPTE Offset, Time Signature, and Key Signature */
    public boolean isTimingRelated() {
        return TIMING_RELATED_TYPES.stream().anyMatch(st -> this == st);
    }
}