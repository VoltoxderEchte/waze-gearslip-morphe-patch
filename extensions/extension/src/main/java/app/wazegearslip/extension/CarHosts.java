package app.wazegearslip.extension;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;

/**
 * Extra Car App Library hosts Waze accepts, such as Gearslip.
 *
 * Car App Library 1.8.0-rc01 / 1.9.0-alpha02 and later honour the TEMPLATE_RENDERER permission only
 * on Android Automotive, so on a phone Waze's HostValidator lets in nothing but the hosts on its
 * own allowlist -- Android Auto. The patch calls [allowed] at the start of the validator's check;
 * a host on the patch's list is accepted there, everything else goes on to Waze's own check.
 */
public final class CarHosts {
    private static final String TAG = "CarApp.Val";

    private CarHosts() {
    }

    /**
     * Whether the host described by [hostInfo] (the library's HostInfo, renamed by R8: one String,
     * the package, and one int, the uid) is on [allowlist].
     *
     * [allowlist] is "digest,package;digest,package": the SHA-256 of the host's signing certificate
     * in lowercase hex, or "*" for any certificate, then the package name -- the format of the
     * library's own hosts_allowlist arrays.
     */
    public static boolean allowed(Object hostInfo, String allowlist) {
        try {
            if (hostInfo == null || allowlist == null) return false;
            String pkg = null;
            int uid = -1;
            for (Class<?> c = hostInfo.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    if (f.getType() == String.class && pkg == null) pkg = (String) f.get(hostInfo);
                    else if (f.getType() == int.class && uid == -1) uid = f.getInt(hostInfo);
                }
            }
            if (pkg == null) return false;

            boolean listed = false;
            boolean anyCertificate = false;
            java.util.List<String> digests = new java.util.ArrayList<>();
            for (String entry : allowlist.split(";")) {
                String[] parts = entry.trim().split(",");
                if (parts.length != 2 || !parts[1].trim().equals(pkg)) continue;
                listed = true;
                String digest = parts[0].trim().toLowerCase(java.util.Locale.ROOT);
                if (digest.equals("*")) anyCertificate = true;
                else digests.add(digest);
            }
            if (!listed) return false;

            Context context = application();
            if (context == null) return false;
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            // The package name came with the binder's uid; a different uid is not that package.
            if (uid != -1 && info.applicationInfo.uid != uid) {
                Log.w(TAG, "Rejected " + pkg + ": uid " + uid + " is not the package's " + info.applicationInfo.uid);
                return false;
            }
            if (anyCertificate) {
                Log.i(TAG, "Accepted - " + pkg + " on the patch's host list (any certificate)");
                return true;
            }
            SigningInfo signing = info.signingInfo;
            if (signing == null) return false;
            Signature[] signatures = signing.hasMultipleSigners()
                    ? signing.getApkContentsSigners() : signing.getSigningCertificateHistory();
            if (signatures == null) return false;
            for (Signature s : signatures) {
                String digest = sha256(s.toByteArray());
                if (digests.contains(digest)) {
                    Log.i(TAG, "Accepted - " + pkg + " on the patch's host list");
                    return true;
                }
            }
            Log.w(TAG, "Rejected " + pkg + ": its certificate is not the one on the patch's host list."
                    + " Certificate digest: " + (signatures.length > 0 ? sha256(signatures[0].toByteArray()) : "none"));
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "Host check failed, falling back to Waze's own", t);
            return false;
        }
    }

    private static Context application() throws Exception {
        Object app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        return app instanceof Application ? (Application) app : null;
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
