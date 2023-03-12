package io.feydor.midi.exceptions;

/**
 * Throws when attempting to construct an invalid io.feydor.Midi Header
 */
public class MidiInvalidHeaderException extends RuntimeException {
    public MidiInvalidHeaderException(String message) {
        super(message);
    }
}
