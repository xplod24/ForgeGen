package android.app;
import java.util.*;
public class NotificationManager {
    public static final int IMPORTANCE_LOW = 2; public static final int IMPORTANCE_DEFAULT = 3; public static final int IMPORTANCE_HIGH = 4;
    public final List<Notification> posted = Collections.synchronizedList(new ArrayList<>());
    public void notify(int id, Notification notification) { posted.add(notification); }
    public void cancel(int id) { }
    /** What the system shows now (a test value, like its promotion flags). */
    public static volatile android.service.notification.StatusBarNotification[] active = new android.service.notification.StatusBarNotification[0];
    public android.service.notification.StatusBarNotification[] getActiveNotifications() { return active; }
    public void createNotificationChannel(NotificationChannel channel) { }
}
