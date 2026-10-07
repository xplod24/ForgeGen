package android.service.notification;
import android.app.Notification;
/** A notification as the system shows it (a test value). */
public class StatusBarNotification {
    private final int id; private final Notification notification;
    public StatusBarNotification(int id, Notification notification) { this.id = id; this.notification = notification; }
    public int getId() { return id; }
    public Notification getNotification() { return notification; }
}
