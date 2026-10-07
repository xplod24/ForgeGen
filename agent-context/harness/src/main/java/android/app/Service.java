package android.app;
import android.content.Intent;
import android.os.IBinder;
public abstract class Service extends android.content.ContextWrapper {
    public static final int START_STICKY = 1; public static final int START_NOT_STICKY = 2;
    public void onCreate() { }
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    public abstract IBinder onBind(Intent intent);
    public void onTaskRemoved(Intent rootIntent) { }
    public void onDestroy() { }
    public volatile int stops;
    public final void stopSelf() { stops++; }
    public final void stopSelf(int startId) { stops++; }
    public final void startForeground(int id, Notification notification) { }
}
