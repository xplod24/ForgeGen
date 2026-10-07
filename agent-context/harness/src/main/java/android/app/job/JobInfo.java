package android.app.job;
import android.content.ComponentName;
public class JobInfo {
    public static final int NETWORK_TYPE_ANY = 1; public static final int NETWORK_TYPE_UNMETERED = 2;
    public final int id;
    private final long intervalMillis;
    JobInfo(int id, long intervalMillis) { this.id = id; this.intervalMillis = intervalMillis; }
    public int getId() { return id; }
    public long getIntervalMillis() { return intervalMillis; }
    public static class Builder {
        private final int id;
        private long interval;
        public Builder(int id, ComponentName service) { this.id = id; }
        public Builder setPeriodic(long interval, long flex) { this.interval = interval; return this; }
        public Builder setRequiredNetworkType(int type) { return this; }
        public Builder setPersisted(boolean persisted) { return this; }
        public JobInfo build() { return new JobInfo(id, interval); }
    }
}
