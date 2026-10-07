package android.content.pm;
public abstract class PackageManager {
    public static final int PERMISSION_GRANTED = 0;
    public static final int PERMISSION_DENIED = -1;
    public static class NameNotFoundException extends Exception { }
    /** System features of the fake phone (tests add Samsung's One UI feature). */
    public static final java.util.Set<String> FEATURES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public abstract PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException;
    public boolean hasSystemFeature(String name) { return FEATURES.contains(name); }
    public PackageInstaller getPackageInstaller() { return new PackageInstaller(); }
    /** The package names inside fake APK files (path -> package), for SelfUpdate.movesTo. */
    public static final java.util.Map<String, String> ARCHIVES = new java.util.concurrent.ConcurrentHashMap<>();
    public static final class PackageInfoFlags {
        public static PackageInfoFlags of(long value) { return new PackageInfoFlags(); }
    }
    public PackageInfo getPackageArchiveInfo(String path, int flags) {
        String name = ARCHIVES.get(path);
        if (name == null) return null;
        PackageInfo info = new PackageInfo();
        info.packageName = name;
        return info;
    }
    public PackageInfo getPackageArchiveInfo(String path, PackageInfoFlags flags) { return getPackageArchiveInfo(path, 0); }
}
