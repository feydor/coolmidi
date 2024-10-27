package io.feydor.util;

import java.util.List;
import java.util.Map;

@SuppressWarnings("rawtypes")
public class DynAccess {
    @SuppressWarnings("unchecked")
    public static Object get(Map<String, Object> jsonMap, String[] path) {
        Object val = jsonMap;
        for (String key : path) {
            val = get((Map)val, key);
            if (val == null) {
                return null;
            }
        }
        return val;
    }

    public static Object get(Map<String, Object> m, String k) {
        return m.get(k);
    }

    public String getAsString(Map<String, Object> m, String k) {
        return (String)get(m, k);
    }

    public static Double getAsDouble(Map<String, Object> m, String k) {
        return (Double)get(m, k);
    }

    public static List getAsList(Map<String, Object> m, String k) {
        return (List)get(m, k);
    }

    /**
     * Get the JSON List in the map using the path
     * @param m JSON object map
     */
    public static List getAsList(Map<String, Object> m, String[] path) {
        return (List)get(m, path);
    }
}
