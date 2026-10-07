package android.app;
/** Records the alarms the app sets, for the tests. */
public class AlarmManager {
    public static final int RTC_WAKEUP = 0;
    public static volatile boolean exactAllowed = false;
    public final java.util.List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
    public volatile Long pendingAt = null;
    public boolean canScheduleExactAlarms() { return exactAllowed; }
    public void setExactAndAllowWhileIdle(int type, long at, PendingIntent intent) { calls.add("exact@" + at); pendingAt = at; }
    public void setAndAllowWhileIdle(int type, long at, PendingIntent intent) { calls.add("inexact@" + at); pendingAt = at; }
    public void cancel(PendingIntent intent) { calls.add("cancel"); pendingAt = null; }
}
