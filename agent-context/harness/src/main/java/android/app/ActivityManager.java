package android.app;
public class ActivityManager {
    public static class MemoryInfo { public long availMem; public long totalMem; public boolean lowMemory; }
    public void getMemoryInfo(MemoryInfo outInfo) { outInfo.availMem = 1536L << 20; outInfo.totalMem = 8192L << 20; }
    public static class RunningAppProcessInfo {
        public static final int IMPORTANCE_FOREGROUND = 100;
        public static final int IMPORTANCE_VISIBLE = 200;
        public static final int IMPORTANCE_CACHED = 400;
        public int importance = IMPORTANCE_CACHED;
    }
    /** Tests set how visible the app is. */
    public static volatile int testImportance = RunningAppProcessInfo.IMPORTANCE_CACHED;
    public static void getMyMemoryState(RunningAppProcessInfo info) { info.importance = testImportance; }
}
