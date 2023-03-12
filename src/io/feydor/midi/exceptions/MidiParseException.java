package io.feydor.midi.exceptions;

/**
 * This exception is thrown when a io.feydor.Midi parse error occurs and the parse cannot be completed
 */
public class MidiParseException extends RuntimeException {
    public MidiParseException(String msg) {
        super(msg);
    }
}
