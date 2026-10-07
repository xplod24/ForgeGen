package android.provider;
import android.net.Uri;
public final class MediaStore {
    public interface MediaColumns {
        String DISPLAY_NAME = "_display_name"; String MIME_TYPE = "mime_type"; String RELATIVE_PATH = "relative_path"; String IS_PENDING = "is_pending";
    }
    public static final class Downloads { public static final Uri EXTERNAL_CONTENT_URI = new Uri("content://media/external/downloads"); }
    public static final class Images {
        public static final class Media { public static final Uri EXTERNAL_CONTENT_URI = new Uri("content://media/external/images/media"); }
    }
}
