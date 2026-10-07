package androidx.core.content;
import android.content.*;
public class ContextCompat {
    public static final int RECEIVER_NOT_EXPORTED = 4;
    public static Intent registerReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter, int flags) { return null; }
    public static int checkSelfPermission(Context context, String permission) { return android.content.pm.PackageManager.PERMISSION_GRANTED; }
}
