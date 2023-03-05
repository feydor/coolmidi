package Midi.exceptions;

public class InvalidVarLenParseException extends RuntimeException {
    public InvalidVarLenParseException(String message) {
        super(message);
    }
}
