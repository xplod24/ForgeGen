package android.provider;
public final class Settings {
    public static final String ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS = "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS";
    public static final String ACTION_APP_NOTIFICATION_SETTINGS = "android.settings.APP_NOTIFICATION_SETTINGS";
    public static final String ACTION_APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS";
    public static final String ACTION_APPLICATION_DEVELOPMENT_SETTINGS = "android.settings.APPLICATION_DEVELOPMENT_SETTINGS";
    public static final String ACTION_DEVICE_INFO_SETTINGS = "android.settings.DEVICE_INFO_SETTINGS";
    public static final String ACTION_SETTINGS = "android.settings.SETTINGS";
    public static final String EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE";
    public static final class Global {
        public static final String DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled";
        public static volatile int developmentSettings = 0;
        public static int getInt(android.content.ContentResolver cr, String name, int def) { return developmentSettings; }
    }
}
