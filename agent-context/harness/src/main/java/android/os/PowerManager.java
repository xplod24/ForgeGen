package android.os;
public class PowerManager {
    public static final int PARTIAL_WAKE_LOCK = 1;
    public final class WakeLock {
        private boolean held;
        public volatile int acquires;
        public volatile boolean referenceCounted = true;
        public boolean isHeld() { return held; }
        public void setReferenceCounted(boolean value) { referenceCounted = value; }
        public void acquire(long timeout) { held = true; acquires++; }
        public void release() { held = false; }
    }
    public volatile WakeLock lastLock;
    public WakeLock newWakeLock(int levelAndFlags, String tag) { lastLock = new WakeLock(); return lastLock; }
    public boolean isIgnoringBatteryOptimizations(String pkg) { return false; }
}
