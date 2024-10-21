package io.feydor.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.feydor.ui.MidiController;

import javax.swing.*;
import java.io.*;
import java.net.URL;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FileIo {
    private static final String GM_MIDI_PROGRAM_NAMES_MENU_JSON = "gm_instrument_menu.json";
    private static final Logger LOGGER = Logger.getLogger(FileIo.class.getName());

    public static Map<String, Object> getGmMidiJsonStringMapFromResources() {
        return getJsonStringMapFromResources(GM_MIDI_PROGRAM_NAMES_MENU_JSON);
    }

    public static Map<String, Object> getJsonStringMapFromResources(String filePath) {
        try (InputStream inputStream = FileIo.class.getResourceAsStream("/" + filePath)) {
            return getJsonStringMap(inputStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Map<String, Object> getJsonStringMap(File file) {
        try (InputStream inputStream = new FileInputStream(file)) {
            return getJsonStringMap(inputStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Object> getJsonStringMap(InputStream inputStream) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(inputStream)) {
            TypeToken<Map<String, Object>> typeToken = new TypeToken<>() {};
            Gson gson = new GsonBuilder()
                    .enableComplexMapKeySerialization()
                    .create();
            return gson.fromJson(reader, typeToken);
        }
    }

    /** Returns an ImageIcon, or null if the path was invalid. */
    public static ImageIcon createImageIcon(String path) {
        URL imgURL = FileIo.class.getResource("/" + path);
        if (imgURL != null) {
            return new ImageIcon(imgURL);
        } else {
            LOGGER.log(Level.WARNING, "Couldn't find file: {0}", path);
            return null;
        }
    }
}
