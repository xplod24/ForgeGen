package android.content;
public class ContextWrapper extends Context {
    /** Where a service created in a test gets its system services from (the fake app). */
    public static volatile Context base;
    @Override public Object getSystemService(String name) { return base != null ? base.getSystemService(name) : null; }
    @Override public <T> T getSystemService(Class<T> serviceClass) { return base != null ? base.getSystemService(serviceClass) : null; }
    @Override public String getPackageName() { return "com.example.forgegen"; }
    @Override public Context getApplicationContext() { return base != null ? base : this; }
    @Override public java.io.File getCacheDir() { return base.getCacheDir(); }
    @Override public java.io.File getExternalFilesDir(String type) { return base.getExternalFilesDir(type); }
    @Override public ContentResolver getContentResolver() { return base.getContentResolver(); }
    @Override public android.content.pm.PackageManager getPackageManager() { return base != null ? base.getPackageManager() : super.getPackageManager(); }
}
