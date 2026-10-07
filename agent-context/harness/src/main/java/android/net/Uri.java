package android.net;
import java.util.Objects;
public class Uri implements android.os.Parcelable {
    private final String value;
    public Uri(String value) { this.value = value; }
    public static Uri parse(String s) { return new Uri(s); }
    public static Uri fromFile(java.io.File f) { return new Uri("file://" + f.getAbsolutePath()); }
    @Override public String toString() { return value; }
    @Override public boolean equals(Object o) { return o instanceof Uri && Objects.equals(((Uri) o).value, value); }
    @Override public int hashCode() { return Objects.hashCode(value); }
}
