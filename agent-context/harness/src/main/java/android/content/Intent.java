package android.content;

import java.util.HashMap;
import java.util.Map;

public class Intent {
    public static final String ACTION_SEND = "android.intent.action.SEND";
    public static final String ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE";
    public static final String ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED";
    public static final String EXTRA_INTENT = "android.intent.extra.INTENT";
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final String EXTRA_STREAM = "android.intent.extra.STREAM";
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_ACTIVITY_CLEAR_TOP = 0x04000000;
    public static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 0x00000001;

    private String action;
    private String type;
    private int flags;
    private Class<?> component;
    private android.net.Uri data;
    public final Map<String, Object> extras = new HashMap<>();

    public Intent() { }
    public Intent(String action) { this.action = action; }
    public Intent(String action, android.net.Uri uri) { this.action = action; this.data = uri; }
    public Intent(Context context, Class<?> cls) { this.component = cls; }

    private String pkg;
    public Intent setPackage(String p) { this.pkg = p; return this; }
    public String getPackage() { return pkg; }
    public String getAction() { return action; }
    public Intent setAction(String action) { this.action = action; return this; }
    public String getType() { return type; }
    public Intent setType(String type) { this.type = type; return this; }
    public int getFlags() { return flags; }
    public Intent setFlags(int flags) { this.flags = flags; return this; }
    public Intent addFlags(int flags) { this.flags |= flags; return this; }
    public Class<?> getComponentClass() { return component; }
    public android.net.Uri getData() { return data; }
    public Intent setDataAndType(android.net.Uri data, String type) { this.data = data; this.type = type; return this; }
    public Intent putExtra(String name, android.os.Parcelable value) { extras.put(name, value); return this; }
    public Intent putExtra(String name, String value) { extras.put(name, value); return this; }
    public Intent putExtra(String name, Object value) { extras.put(name, value); return this; }
    public Intent putParcelableArrayListExtra(String name, java.util.ArrayList<? extends android.os.Parcelable> value) { extras.put(name, value); return this; }
    public static Intent createChooser(Intent target, CharSequence title) {
        Intent i = new Intent("android.intent.action.CHOOSER");
        i.extras.put("target", target);
        return i;
    }
    public int getIntExtra(String name, int def) { Object v = extras.get(name); return v instanceof Integer ? (Integer) v : def; }
    public long getLongExtra(String name, long def) { Object v = extras.get(name); return v instanceof Long ? (Long) v : (v instanceof Integer ? (Integer) v : def); }
    public String getStringExtra(String name) { Object v = extras.get(name); return v instanceof String ? (String) v : null; }
    @SuppressWarnings("unchecked") public <T> T getParcelableExtra(String name, Class<T> cls) { return (T) extras.get(name); }
    @SuppressWarnings("unchecked") public <T extends android.os.Parcelable> T getParcelableExtra(String name) { return (T) extras.get(name); }
}
