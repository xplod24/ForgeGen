package android.content.pm;
import android.content.IntentSender;
import java.io.IOException;
import java.io.OutputStream;
public class PackageInstaller {
    public static final String EXTRA_STATUS = "android.content.pm.extra.STATUS";
    public static final String EXTRA_STATUS_MESSAGE = "android.content.pm.extra.STATUS_MESSAGE";
    public static final int STATUS_PENDING_USER_ACTION = -1; public static final int STATUS_SUCCESS = 0; public static final int STATUS_FAILURE = 1;
    public static class SessionParams {
        public static final int MODE_FULL_INSTALL = 1; public static final int USER_ACTION_NOT_REQUIRED = 2;
        public SessionParams(int mode) { }
        public void setAppPackageName(String name) { }
        public void setSize(long size) { }
        public void setRequireUserAction(int action) { }
        public void setRequestUpdateOwnership(boolean own) { }
    }
    public static class Session implements java.io.Closeable {
        public OutputStream openWrite(String name, long offset, long length) throws IOException { return new java.io.ByteArrayOutputStream(); }
        public void fsync(OutputStream out) throws IOException { }
        public void commit(IntentSender sender) { }
        @Override public void close() { }
    }
    public int createSession(SessionParams params) throws IOException { return 1; }
    public Session openSession(int id) throws IOException { return new Session(); }
    public void abandonSession(int id) { }
}
