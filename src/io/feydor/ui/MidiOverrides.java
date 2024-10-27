package io.feydor.ui;

import io.feydor.midi.Midi;
import io.feydor.util.DynAccess;
import io.feydor.util.FileIo;

import java.io.File;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MidiOverrides {
    private static final Logger LOGGER = Logger.getLogger(MidiOverrides.class.getName());
    private static final String OVERRIDE_FILE_PREFIX = "coolmidi-";
    private final Map<String, Object> jsonMap;

    public MidiOverrides(File midiFile) {
        this.jsonMap = findOverrides(midiFile);
    }

    private Map<String, Object> findOverrides(File file) {
        String filename = file.getName().split("\\.")[0];
        String overrideFileName = OVERRIDE_FILE_PREFIX + filename + ".json";
        File overrideFile = new File(overrideFileName);
        if (overrideFile.exists()) {
            return FileIo.getJsonStringMap(overrideFile);
        } else {
            LOGGER.log(Level.WARNING, "Override json file not found for: {0}", overrideFile.getAbsolutePath());
            return new HashMap<>();
        }
    }

    @SuppressWarnings({"unchecked", "rawTypes"})
    public List<Midi.MidiChunk.Event> toMidiEvents() {
        var res = new ArrayList<Midi.MidiChunk.Event>();
        List<Map<String, Object>> channelOverrides = DynAccess.getAsList(jsonMap, "channelOverrides");
        if (channelOverrides == null)
            return res;
        for (var overrideObject : channelOverrides) {
            Double channel = DynAccess.getAsDouble(overrideObject, "channel");
            if (channel == null) {
                LOGGER.log(Level.WARNING, "Overrides need a channel, but not given: {0}", overrideObject);
                continue;
            }
            Double program = DynAccess.getAsDouble(overrideObject, "program");
            Double volume = DynAccess.getAsDouble(overrideObject, "volume");
            if (program != null) {
                res.add(Midi.MidiChunk.Event.createProgramChangeEvent(channel.byteValue() - 1, program.byteValue()));
            }
            if (volume != null) {
                res.add(Midi.MidiChunk.Event.createChannelVolumeEvent(channel.byteValue() - 1, volume.byteValue()));
            }
        }
        return res;
    }
}
