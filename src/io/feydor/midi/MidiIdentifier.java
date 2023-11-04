package io.feydor.midi;

/**
 * The 2 types of MIDI Chunks
 */
public enum MidiIdentifier {
    MThd("MThd".getBytes()),
    MTrk("MTrk".getBytes());

    public final byte[] id;

    MidiIdentifier(byte[] id) {
        this.id = id;
    }

    public byte[] getBytes() {
        return id;
    }
}
