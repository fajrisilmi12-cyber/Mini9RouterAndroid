package id.my.minirouter;

import android.app.Application;
import android.os.Build;

import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Android 5 shipped with an old CA store. chatgpt.com currently chains through
 * modern Let's Encrypt / ISRG certificates that are missing on some Lollipop
 * devices. This adds a narrow fallback trust path while keeping the normal
 * Android trust manager as the first choice.
 *
 * We do NOT disable certificate or hostname verification. If Android's normal
 * trust check fails, the fallback only accepts a correctly signed chain that
 * reaches one of the pinned public ISRG trust certificates below.
 */
public final class Mini9RouterApp extends Application {
    // ISRG Root X1 (self-signed) SHA-256.
    private static final String ISRG_ROOT_X1 =
            "96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6";

    // ISRG Root X2 cross-signed by ISRG Root X1 SHA-256.
    // Useful when the server omits X1 itself but sends the X2 cross-signed cert.
    private static final String ISRG_ROOT_X2_CROSS =
            "8B05B68CC659E5ED0FCB38F2C942FBFD200E6F2FF9F85D63C6994EF5E0B02701";

    @Override
    public void onCreate() {
        super.onCreate();
        // Only needed for legacy Android. Modern Android already has current roots.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
            try {
                installLegacyTrustBridge();
            } catch (Throwable ignored) {
                // Leave the platform TLS stack untouched if setup fails.
            }
        }
    }

    private static void installLegacyTrustBridge() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((java.security.KeyStore) null);

        X509TrustManager platform = null;
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                platform = (X509TrustManager) tm;
                break;
            }
        }
        if (platform == null) throw new IllegalStateException("No platform X509TrustManager");

        final X509TrustManager system = platform;
        X509TrustManager bridge = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                system.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    system.checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException original) {
                    if (chain == null || chain.length == 0) throw original;

                    // Validate certificate dates first.
                    for (X509Certificate cert : chain) cert.checkValidity();

                    // Find a pinned ISRG certificate in the supplied chain.
                    int anchor = -1;
                    for (int i = 0; i < chain.length; i++) {
                        String fp = sha256(chain[i]);
                        if (ISRG_ROOT_X1.equals(fp) || ISRG_ROOT_X2_CROSS.equals(fp)) {
                            anchor = i;
                            break;
                        }
                    }
                    if (anchor < 0) throw original;

                    // Verify every signature from the leaf up to the pinned anchor.
                    try {
                        for (int i = 0; i < anchor; i++) {
                            chain[i].verify(chain[i + 1].getPublicKey());
                        }
                    } catch (Exception verifyError) {
                        CertificateException ce = new CertificateException("ISRG chain signature validation failed");
                        ce.initCause(verifyError);
                        throw ce;
                    }
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return system.getAcceptedIssuers();
            }
        };

        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[]{bridge}, null);
        HttpsURLConnection.setDefaultSSLSocketFactory(ssl.getSocketFactory());
        // Keep Android's default hostname verifier. Do not replace it.
    }

    private static String sha256(X509Certificate cert) throws CertificateException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder b = new StringBuilder(digest.length * 2);
            for (byte v : digest) b.append(String.format(java.util.Locale.US, "%02X", v & 0xff));
            return b.toString();
        } catch (Exception e) {
            CertificateException ce = new CertificateException("Unable to fingerprint certificate");
            ce.initCause(e);
            throw ce;
        }
    }
}
