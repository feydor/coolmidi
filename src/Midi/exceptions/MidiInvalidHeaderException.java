package Midi.exceptions;

/**
 * Throws when attempting to construct an invalid Midi Header
 */
public class MidiInvalidHeaderException extends RuntimeException {
    public MidiInvalidHeaderException(String message) {
        super(message);
    }
}
