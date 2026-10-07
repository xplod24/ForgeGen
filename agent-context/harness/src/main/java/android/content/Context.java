package android.content;

import java.io.File;

/** JVM stand-in for android.content.Context; tests subclass it (FakeApp). */
public abstract class Context {
    public static final String POWER_SERVICE = "power";
    public static final String ACTIVITY_SERVICE = "activity";
    public static final String NOTIFICATION_SERVICE = "notification";
    public static final String CONNECTIVITY_SERVICE = "connectivity";
    public static final int MODE_PRIVATE = 0;

    public Context getApplicationContext() { return this; }
    public File getCacheDir() { throw new UnsupportedOperationException(); }
    public File getExternalFilesDir(String type) { throw new UnsupportedOperationException(); }
    public File getFilesDir() { return getCacheDir(); }
    public String getPackageName() { return "com.example.forgegen"; }
    public android.content.pm.PackageManager getPackageManager() { throw new UnsupportedOperationException(); }
    public android.content.res.AssetManager getAssets() { return new android.content.res.AssetManager(); }
    public ContentResolver getContentResolver() { throw new UnsupportedOperationException(); }
    public Object getSystemService(String name) { return null; }
    // A test may set a JobScheduler stand-in (G34); none otherwise, as before.
    public static android.app.job.JobScheduler testJobScheduler;
    public <T> T getSystemService(Class<T> serviceClass) {
        if (serviceClass == android.app.job.JobScheduler.class) return serviceClass.cast(testJobScheduler);
        return serviceClass == android.app.NotificationManager.class ? serviceClass.cast(new android.app.NotificationManager()) : null;
    }
    public ComponentName startService(Intent intent) { return null; }
    public ComponentName startForegroundService(Intent intent) { return null; }
    public boolean stopService(Intent intent) { return true; }
    public void startActivity(Intent intent) { }
    public void unregisterReceiver(BroadcastReceiver receiver) { }
    public void sendBroadcast(Intent intent) { }

    /** In-memory SharedPreferences, shared by every context of the JVM (one app per test JVM). */
    public static final java.util.Map<String, java.util.Map<String, String>> PREFS = new java.util.concurrent.ConcurrentHashMap<>();
    public SharedPreferences getSharedPreferences(String name, int mode) {
        java.util.Map<String, String> map = PREFS.computeIfAbsent(name, k -> new java.util.concurrent.ConcurrentHashMap<>());
        return new SharedPreferences() {
            public String getString(String key, String defValue) { return map.getOrDefault(key, defValue); }
            public boolean getBoolean(String key, boolean defValue) { String v = map.get(key); return v == null ? defValue : Boolean.parseBoolean(v); }
            public long getLong(String key, long defValue) { String v = map.get(key); return v == null ? defValue : Long.parseLong(v); }
            public int getInt(String key, int defValue) { String v = map.get(key); return v == null ? defValue : Integer.parseInt(v); }
            public Editor edit() {
                return new Editor() {
                    public Editor putString(String key, String value) { map.put(key, value); return this; }
                    public Editor putBoolean(String key, boolean value) { map.put(key, String.valueOf(value)); return this; }
                    public Editor putLong(String key, long value) { map.put(key, String.valueOf(value)); return this; }
                    public Editor putInt(String key, int value) { map.put(key, String.valueOf(value)); return this; }
                    public Editor remove(String key) { map.remove(key); return this; }
                    public Editor clear() { map.clear(); return this; }
                    public void apply() { }
                };
            }
        };
    }
}
