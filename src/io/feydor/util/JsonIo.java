package io.feydor.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;

public class JsonIo {
    private static final String GM_MIDI_PROGRAM_NAMES_MENU_JSON = "gm_instrument_menu.json";

    public static Map<String, Object> getGmMidiJsonStringMap() {
        return getJsonStringMap(GM_MIDI_PROGRAM_NAMES_MENU_JSON);
    }

    public static Map<String, Object> getJsonStringMap(String filePath) {
        try (InputStream inputStream = JsonIo.class.getResourceAsStream("/" + filePath)) {
            assert inputStream != null;
            try (InputStreamReader reader = new InputStreamReader(inputStream)) {
                TypeToken<Map<String, Object>> typeToken = new TypeToken<>() {};
                Gson gson = new GsonBuilder()
                        .enableComplexMapKeySerialization()
                        .create();
                return gson.fromJson(reader, typeToken);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
