package io.feydor.midi;

import io.feydor.util.JsonIo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The current state of a single MIDI channel
 */
public class MidiChannel {
    public final int channel; // voice, 1 to 16
    public final boolean used;
    public byte note; // 0 to 127
    public boolean noteOn;
    public byte noteVelocity; // 0 to 127
    public final byte[] controllers; // controller values, 0 to 127, https://anotherproducer.com/online-tools-for-musicians/midi-cc-list/
    public int lastController; // index of the last controller message on this channel
    public byte program; // currently selected instrument, 0 to 127
    public int pitchBend; // 0 to 16,383, 8,192 means no pitch bend
    public byte pressure; // the pressure applied to all notes on the channel 0 to 127
    public final byte[] polyphonicPressure; // polyphonic aftertouch values for individual notes on the channel, 0 to 127

    public static final int CONTROLLER_VOLUME = 7;
    public static final int CONTROLLER_PAN = 10;
    private static final Map<Byte, String> GM_PROGRAM_TO_NAME = new HashMap<>();
    private static final Map<String, Byte> GM_NAME_TO_PROGRAM = new HashMap<>();

    // Populate GM program names map using csv file
    static {
        Map<String, Object> instrumentMenu = JsonIo.getGmMidiJsonStringMap();
        int count = 0;
        for (var entry : instrumentMenu.entrySet()) {
            if (entry.getValue() instanceof List instrNames) {
                for (Object name : instrNames) {
                    GM_PROGRAM_TO_NAME.put((byte)(count & 0xff), (String)name);
                    count++;
                }
            } else {
                throw new RuntimeException("Not a list: " + entry.getValue());
            }
        }

        for (var entry : GM_PROGRAM_TO_NAME.entrySet()) {
            GM_NAME_TO_PROGRAM.put(entry.getValue(), entry.getKey());
        }
    }


    public MidiChannel(int channel, boolean used) {
        if (channel < 1 || channel > 16)
            throw new IllegalArgumentException("MIDI has channels 1 to 16: channel=" + channel);
        this.channel = channel;
        this.controllers = new byte[128]; // MIDI CC 120 to 127 are “Channel Mode Messages.”
        this.polyphonicPressure = new byte[128];
        this.used = used;
    }

    public static short gmProgramNameToByte(String programName) {
        return GM_NAME_TO_PROGRAM.get(programName);
    }

    /** Set pressure for note */
    public void setPolyphonicPressure(byte note, byte pressure) {
        if (note < 0)
            throw new IllegalArgumentException("MIDI notes must be between 0 and 127: note=" + note);
        else if (pressure < 0)
            throw new IllegalArgumentException("MIDI pressure must be between 0 and 127: pressure=" + pressure);
        polyphonicPressure[note] = pressure;
    }

    /** Set value for channel */
    public void setController(byte controller, byte value) {
        if (controller < 0)
            throw new IllegalArgumentException("MIDI controller must be between 0 and 127: controller=" + controller);
        controllers[controller] = value;
        lastController = controller;
    }

    public void setNote(byte note) {
        this.note = note;
    }

    public void setNoteOn(boolean noteOn) {
        this.noteOn = noteOn;
    }

    public void setProgram(byte program) {
        if (program < 0)
            throw new RuntimeException("New program must be between 0 and 127 (inclusive): " + program);

        if (this.program != program)
            this.program = program;
    }

    public void setPitchBend(int pitchBend) {
        if (pitchBend < 0 || pitchBend > 16383)
            throw new IllegalArgumentException("MIDI pitch bend must be between 0 and 16383: pitchBend=" + pitchBend);
        this.pitchBend = pitchBend;
    }

    public void setPressure(byte pressure) {
        this.pressure = pressure;
    }

    public void setNoteVelocity(byte noteVelocity) {
        this.noteVelocity = noteVelocity;
    }

    public byte getVolume() {
        return controllers[CONTROLLER_VOLUME];
    }

    public String controllerName(int controller) {
        return switch (controller) {
            case CONTROLLER_VOLUME -> "Volume";
            case CONTROLLER_PAN -> "Pan";
            default -> String.valueOf(controller);
        };
    }

    public String getCurrentGmProgramName() {
        return GM_PROGRAM_TO_NAME.get(program);
    }

    @Override
    public String toString() {
        return "MidiChannel{" +
                "channel=" + channel +
                ", note=" + String.format("%03d", note) +
                ", noteOn=" + (noteOn ? '1' : '0') +
                ", noteVelocity=" + String.format("%03d", noteVelocity) +
                ", volume=" + getVolume() +
                ", lastController=" + controllerName(lastController) +
                ", program=" + program +
                ", pitchBend=" + pitchBend +
                ", pressure=" + pressure +
                '}';
    }
}
