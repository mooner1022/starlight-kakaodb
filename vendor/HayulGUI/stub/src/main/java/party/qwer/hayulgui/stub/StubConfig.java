package party.qwer.hayulgui.stub;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * APK 루트의 {@code hayulgui.cfg} 마커를 읽어 서명 위조/위임 정보를 노출한다.
 * PatchEngine(party.qwer.hayulgui.core.PatchEngine) 과 파일명/키를 공유한다.
 *
 * <pre>
 * hayulgui.cfg:
 *   sig=    &lt;hex&gt;  원본 서명 인증서 DER hex (위조 목표)
 *   factory=&lt;fqn&gt;   원본 appComponentFactory (위임 대상, 없으면 key 없음)
 * </pre>
 */
final class StubConfig {

    static final String CONFIG_FILE = "hayulgui.cfg";

    static volatile byte[] originalSignature;
    static volatile String originalFactory;
    private static volatile boolean loaded;

    private StubConfig() { }

    static void load() {
        if (loaded) return;
        synchronized (StubConfig.class) {
            if (loaded) return;
            try (InputStream in = StubConfig.class.getClassLoader()
                    .getResourceAsStream(CONFIG_FILE)) {
                if (in != null) {
                    Properties p = new Properties();
                    p.load(new ByteArrayInputStream(readAll(in)));
                    String hex = p.getProperty("sig");
                    if (hex != null) originalSignature = hexToBytes(hex.trim());
                    originalFactory = p.getProperty("factory");
                } else {
                    Log.w("HayulGUI", "stub: " + CONFIG_FILE + " not found in APK root");
                }
            } catch (Throwable t) {
                Log.w("HayulGUI", "stub: config load failed", t);
            }
            loaded = true;
        }
    }

    static byte[] hexToBytes(String hex) {
        String s = hex.replaceAll("[^0-9a-fA-F]", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
