package its.madruga.warevamp.module.hooks.core;

import android.util.Log;
import androidx.annotation.NonNull;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * Abstract base class for every WaRevamp hook.
 *
 * <p>Each feature hook (e.g. {@code AntiRevokeHook}, {@code HideReadHook}) extends this class.
 * It provides:
 * <ul>
 *   <li>{@link #loader} – WhatsApp's {@link ClassLoader}, required to look up obfuscated
 *       WhatsApp classes via Xposed / DexKit.</li>
 *   <li>{@link #prefs} – The module's {@link XSharedPreferences}, used to read all user-
 *       configurable settings.</li>
 *   <li>{@link #log(String)} – Writes a tagged line to the Xposed log (visible in LSPosed /
 *       EdXposed log viewers).</li>
 *   <li>{@link #wppLog(String)} – Writes a tagged line to Android's logcat using {@code Log.e}
 *       (useful when the Xposed logger is unavailable).</li>
 *   <li>{@link #doHook()} – Override to install XposedBridge method hooks. Called once during
 *       WhatsApp startup by {@link HooksLoader#plugins}.</li>
 * </ul>
 */
public class HooksBase {
    /** WhatsApp's class loader, needed to resolve obfuscated class names at runtime. */
    public final ClassLoader loader;
    /** User-facing preferences; controls which features are enabled. */
    public final XSharedPreferences prefs;

    public HooksBase(@NonNull ClassLoader loader, @NonNull XSharedPreferences preferences) {
        this.loader = loader;
        this.prefs = preferences;
    }

    /**
     * Writes a log line to the Xposed log prefixed with the concrete hook class name.
     *
     * @param msg the message to log
     */
    public void log(@NonNull String msg) {
        XposedBridge.log("[-] Log | " + this.getClass().getSimpleName() +
                " -> " + msg);
    }

    /**
     * Writes a log line to Android logcat (tag {@code "WaRevamp"}) prefixed with the concrete
     * hook class name. Useful for debugging when the Xposed log is not accessible.
     *
     * @param msg the message to log
     */
    public void wppLog(@NonNull String msg) {
        Log.e("WaRevamp", "[-] Log | " + this.getClass().getSimpleName() +
                " -> " + msg);
    }

    /**
     * Installs the Xposed method hooks for this feature. Override in each concrete hook subclass.
     * Called once from {@link HooksLoader#plugins} during WhatsApp's {@code Application.onCreate}.
     *
     * @throws Exception if a required WhatsApp method or class cannot be resolved
     */
    public void doHook() throws Exception {

    }
}
