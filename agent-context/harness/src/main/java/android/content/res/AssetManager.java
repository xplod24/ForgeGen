package android.content.res;
public class AssetManager {
    /** Test hook: asset name -> content. */
    public static final java.util.Map<String, String> files = new java.util.concurrent.ConcurrentHashMap<>();
    public java.io.InputStream open(String name) throws java.io.IOException {
        String text = files.get(name);
        if (text == null) throw new java.io.FileNotFoundException(name);
        return new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
