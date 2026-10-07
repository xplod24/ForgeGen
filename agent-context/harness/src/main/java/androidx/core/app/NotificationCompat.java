package androidx.core.app;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
public class NotificationCompat {
    public static final int PRIORITY_MAX = 2;
    public static final int VISIBILITY_PRIVATE = 0;
    public static final int VISIBILITY_PUBLIC = 1;
    public static final String CATEGORY_PROGRESS = "progress";
    public abstract static class Style { }
    public static class ProgressStyle extends Style {
        int progress; boolean indeterminate;
        public ProgressStyle setProgress(int p) { progress = p; return this; }
        public ProgressStyle setProgressIndeterminate(boolean b) { indeterminate = b; return this; }
    }
    public static class BigTextStyle extends Style {
        CharSequence big;
        public BigTextStyle bigText(CharSequence t) { big = t; return this; }
    }
    public static class Builder {
        private final String channelId; private CharSequence title; private CharSequence text; private int color;
        private boolean ongoing; private boolean promoted; private String shortText; private Style style; private String bar;
        public Builder(Context context, String channelId) { this.channelId = channelId; }
        public Builder setSmallIcon(int icon) { return this; }
        public Builder setContentTitle(CharSequence t) { title = t; return this; }
        public Builder setContentText(CharSequence t) { text = t; return this; }
        public Builder setContentIntent(PendingIntent p) { return this; }
        public Builder setDeleteIntent(PendingIntent p) { return this; }
        public Builder setAutoCancel(boolean b) { return this; }
        public Builder setOngoing(boolean b) { ongoing = b; return this; }
        public Builder setOnlyAlertOnce(boolean b) { return this; }
        public Builder setSilent(boolean b) { return this; }
        public Builder setCategory(String c) { return this; }
        public Builder setProgress(int max, int progress, boolean indeterminate) { bar = max == 0 ? null : max + "/" + progress; return this; }
        public Builder setRequestPromotedOngoing(boolean b) { promoted = b; return this; }
        public Builder setShortCriticalText(String t) { shortText = t; return this; }
        public Builder setStyle(Style s) { style = s; return this; }
        private int visibility = 0; private Notification publicVersion;
        public Builder setVisibility(int v) { visibility = v; return this; }
        public Builder setPublicVersion(Notification n) { publicVersion = n; return this; }
        public Builder addAction(int icon, CharSequence title, PendingIntent intent) { return this; }
        public int getColor() { return color; }
        public Builder setColor(int argb) { color = argb; return this; }
        public Notification build() {
            Notification n = new Notification(channelId, title, text);
            n.ongoing = ongoing; n.requestPromotedOngoing = promoted; n.shortCriticalText = shortText; n.progressBar = bar;
            if (style instanceof ProgressStyle) n.styleProgress = ((ProgressStyle) style).progress;
            if (style instanceof BigTextStyle) n.bigText = ((BigTextStyle) style).big;
            n.publicVersion = publicVersion; n.visibility = visibility;
            return n;
        }
    }
}
