package io.feydor.midi;

/**
 * There are three types of io.feydor.Midi File formats:
 * <ul>
 *     <li>0: a single multi-channel track</li>
 *     <li>1: two or more tracks all played simultaneously</li>
 *     <li>2: one or more tracks played independently</li>
 * </ul>
 */
public enum MidiFileFormat {
    /**
     * Single multi-channel track
     */
    FORMAT_0((short) 0),

    /**
     * The most common format in MIDI.
     * Two or more track chunks (header.ntracks) to be played simultaneously:
     * <ul>
     *     <li>the first is the tempo track,</li>
     *     <li>the second is the note data</li>
     * </ul>
     */
    FORMAT_1((short) 1),

    /**
     * one or more Track chunks to be played independently
     */
    FORMAT_2((short) 2);

    public final short word;

    MidiFileFormat(short word) {
        this.word = word;
    }
}
