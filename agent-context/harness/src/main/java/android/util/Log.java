package android.util;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
public final class Log {
    public static final List<String> messages = Collections.synchronizedList(new ArrayList<>());
    private static int log(String level, String tag, String msg, Throwable tr) {
        messages.add(level + "/" + tag + ": " + msg + (tr != null ? " (" + tr + ")" : ""));
        return 0;
    }
    public static int d(String tag, String msg) { return log("D", tag, msg, null); }
    public static int i(String tag, String msg) { return log("I", tag, msg, null); }
    public static int w(String tag, String msg) { return log("W", tag, msg, null); }
    public static int w(String tag, String msg, Throwable tr) { return log("W", tag, msg, tr); }
    public static int e(String tag, String msg) { return log("E", tag, msg, null); }
    public static int e(String tag, String msg, Throwable tr) { return log("E", tag, msg, tr); }
}
