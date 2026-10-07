package android.app;
public class Notification {
    public final String channelId; public final CharSequence title; public final CharSequence text;
    public boolean ongoing; public boolean requestPromotedOngoing; public String shortCriticalText;
    /** Progress of a ProgressStyle, -1 without one; progressBar is the classic bar ("max/progress", or null). */
    public int styleProgress = -1; public String progressBar; public Notification publicVersion; public int visibility;
    /** The text of a BigTextStyle, null without one. */
    public CharSequence bigText;
    /** Set by the system (in tests: by the test) when it shows the notification as a Live Update. */
    public int flags;
    public static final int FLAG_PROMOTED_ONGOING = 262144;
    public Notification(String channelId, CharSequence title, CharSequence text) { this.channelId = channelId; this.title = title; this.text = text; }
}
