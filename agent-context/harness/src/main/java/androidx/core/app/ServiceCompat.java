package androidx.core.app;
import android.app.Notification;
import android.app.Service;
public final class ServiceCompat {
    public static final int STOP_FOREGROUND_REMOVE = 1;
    public static void startForeground(Service service, int id, Notification notification, int type) { }
    public static void stopForeground(Service service, int flags) { }
}
