package android.content;
import java.util.HashMap;
public final class ContentValues {
    public final HashMap<String, Object> values = new HashMap<>();
    public void put(String key, String value) { values.put(key, value); }
    public void put(String key, Integer value) { values.put(key, value); }
    public void put(String key, Long value) { values.put(key, value); }
}
