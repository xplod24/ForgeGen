package android.content;
import java.io.InputStream;
import java.io.OutputStream;
public abstract class ContentResolver {
    public android.net.Uri insert(android.net.Uri url, ContentValues values) { return null; }
    public OutputStream openOutputStream(android.net.Uri uri) throws java.io.FileNotFoundException { return null; }
    public OutputStream openOutputStream(android.net.Uri uri, String mode) throws java.io.FileNotFoundException { return openOutputStream(uri); }
    public InputStream openInputStream(android.net.Uri uri) throws java.io.FileNotFoundException { return null; }
    public android.database.Cursor query(android.net.Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    public int update(android.net.Uri uri, ContentValues values, String where, String[] selectionArgs) { return 0; }
    public int delete(android.net.Uri uri, String where, String[] selectionArgs) { return 0; }
}
