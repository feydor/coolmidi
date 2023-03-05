package Midi;

/**
 * All supported Midi event types by status byte(s)
 */
public enum MidiEventType {
    MIDI(0x80),
    META(0xFF),

    // Marker, Cue Point, Tempo, SMPTE Offset, Time Signature, and Key Signature
    META_TIMING_RELATED(0xFF0),
    SYSEX(0xF0),
    SET_TEMPO(0x51);

    final int id;

    MidiEventType(int id) {
        this.id = id;
    }
}
