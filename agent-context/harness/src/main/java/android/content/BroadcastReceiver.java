package android.content;
public abstract class BroadcastReceiver {
    public abstract void onReceive(Context context, Intent intent);
    /** goAsync(): the receiver finishes later (3.6.0 AutoUnloadReceiver). */
    public static class PendingResult { public volatile boolean finished = false; public final void finish() { finished = true; } }
    public final PendingResult goAsync() { return new PendingResult(); }
}
