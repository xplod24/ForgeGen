package android.content;
public interface SharedPreferences {
    String getString(String key, String defValue);
    boolean getBoolean(String key, boolean defValue);
    long getLong(String key, long defValue);
    int getInt(String key, int defValue);
    Editor edit();
    interface Editor {
        Editor putString(String key, String value);
        Editor putBoolean(String key, boolean value);
        Editor putLong(String key, long value);
        Editor putInt(String key, int value);
        Editor remove(String key);
        Editor clear();
        void apply();
    }
}
