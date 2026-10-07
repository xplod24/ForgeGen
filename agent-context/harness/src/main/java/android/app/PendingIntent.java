package android.app;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
public final class PendingIntent {
    public static final int FLAG_UPDATE_CURRENT = 0x08000000; public static final int FLAG_IMMUTABLE = 0x04000000;
    public static final int FLAG_MUTABLE = 0x02000000;
    public Intent intent;
    public static PendingIntent getActivity(Context c, int code, Intent intent, int flags) { PendingIntent p = new PendingIntent(); p.intent = intent; return p; }
    public static PendingIntent getBroadcast(Context c, int code, Intent intent, int flags) { PendingIntent p = new PendingIntent(); p.intent = intent; return p; }
    public static PendingIntent getService(Context c, int code, Intent intent, int flags) { PendingIntent p = new PendingIntent(); p.intent = intent; return p; }
    public IntentSender getIntentSender() { return new IntentSender(); }
}
