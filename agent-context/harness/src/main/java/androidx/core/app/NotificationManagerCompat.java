package androidx.core.app;
import android.app.Notification;
import android.content.Context;
import java.util.*;
public final class NotificationManagerCompat {
    /** Every notify/cancel of the process, so tests can check what the user would see. */
    public static final List<Object[]> posted = Collections.synchronizedList(new ArrayList<>());
    public static final List<Integer> cancelled = Collections.synchronizedList(new ArrayList<>());
    public static NotificationManagerCompat from(Context context) { return new NotificationManagerCompat(); }
    public boolean areNotificationsEnabled() { return true; }
    /** Whether the system lets the app post live notifications (a test switch). */
    public static volatile boolean promotedAllowed = true;
    public boolean canPostPromotedNotifications() { return promotedAllowed; }
    public void notify(int id, Notification notification) { posted.add(new Object[] { id, notification }); }
    public void cancel(int id) { cancelled.add(id); }
}
