package android.content.pm;
public class PackageInfo {
    public long versionCode;
    public String versionName;
    public String packageName;
    public long firstInstallTime;
    public long lastUpdateTime;
    public long getLongVersionCode() { return versionCode; }
}
