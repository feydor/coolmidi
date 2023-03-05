package Midi.exceptions;

/**
 * This exception is thrown when a Midi parse error occurs and the parse cannot be completed
 */
public class MidiParseException extends RuntimeException {
    public MidiParseException(String msg) {
        super(msg);
    }
}
