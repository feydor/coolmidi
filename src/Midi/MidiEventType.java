package Midi;

/**
 * All supported Midi event types by status byte(s)
 */
public enum MidiEventType {
    MIDI(0x80),
    META(0xFF),
    SYSEX(0xF0),
    UNKNOWN(-1);

    // META_TIMING_RELATED(0xFF0), Marker, Cue Point, Tempo, SMPTE Offset, Time Signature, and Key Signature

    final int id;

    MidiEventType(int id) {
        this.id = id;
    }

    /**
     * @param status The upper status byte
     * @return The identifying tag
     */
    public static MidiEventType fromStatusByte(short status) {
        return switch (status) {
            case 0xFF -> META;
            case 0xF0, 0xF7 -> SYSEX;
            default -> MIDI;
        };
    }
}
