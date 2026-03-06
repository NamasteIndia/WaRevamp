package its.madruga.warevamp.module;

import android.content.res.XModuleResources;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;

import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import its.madruga.warevamp.BuildConfig;
import its.madruga.warevamp.module.hooks.core.HooksLoader;
import its.madruga.warevamp.module.hooks.media.DisableFlagSecureHook;
import its.madruga.warevamp.module.references.ModuleResources;
import its.madruga.warevamp.app.core.Utils;
import its.madruga.warevamp.app.core.XposedChecker;

/**
 * Xposed entry point for WaRevamp.
 *
 * <p>Implements three Xposed interfaces:
 * <ul>
 *   <li>{@link IXposedHookZygoteInit} – runs once in the Zygote process before any app is forked;
 *       stores the module APK path so resources can be loaded later.</li>
 *   <li>{@link IXposedHookLoadPackage} – called for every app launch; detects WhatsApp / WhatsApp
 *       Business and delegates to {@link HooksLoader}, and also marks the module as active inside
 *       the module's own process.</li>
 *   <li>{@link IXposedHookInitPackageResources} – injects module-defined strings, arrays,
 *       drawables, and layouts into WhatsApp's resource table so hooks can reference them as
 *       if they were native WhatsApp resources.</li>
 * </ul>
 */
public class ModuleStart implements IXposedHookLoadPackage, IXposedHookInitPackageResources, IXposedHookZygoteInit {
    private static XSharedPreferences pref;
    private static String PATH;

    /**
     * Returns a lazily-initialised, world-readable {@link XSharedPreferences} instance backed by
     * the module's default SharedPreferences file. All hooks read user settings through this
     * object.
     */
    @NonNull
    public static XSharedPreferences getPref() {
        if (pref == null) {
            pref = new XSharedPreferences(BuildConfig.APPLICATION_ID);
            pref.makeWorldReadable();
            pref.reload();
        }
        return pref;
    }

    /**
     * Called by Xposed when each app's package is loaded.
     *
     * <ul>
     *   <li>If the loaded package is the module itself, activates the Xposed-active flag used by
     *       the settings UI to show a "module is running" indicator.</li>
     *   <li>If the loaded package is WhatsApp or WhatsApp Business, starts the full hook
     *       initialisation pipeline via {@link HooksLoader#initialize}.</li>
     *   <li>Applies {@code DisableFlagSecureHook} unconditionally so screenshots work in all
     *       relevant packages.</li>
     * </ul>
     */
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String packageName = lpparam.packageName;
        ClassLoader classLoader = lpparam.classLoader;
        String sourcePath = lpparam.appInfo.sourceDir;
        if (packageName.equals(BuildConfig.APPLICATION_ID)) {
            XposedChecker.setActiveModule(lpparam.classLoader);
        }

        if (packageName.equals(Utils.WHATSAPP_PACKAGE) || packageName.equals(Utils.WHATSAPP_WEB_PACKAGE)) {
            HooksLoader.initialize(getPref(), classLoader, sourcePath);
        }

        DisableFlagSecureHook.doHook(lpparam, getPref());

    }

    /**
     * Called by Xposed when WhatsApp's resource table is being initialised.
     *
     * <p>Iterates over every public static field in the {@link ModuleResources} inner classes
     * (string, array, drawable, layout) and injects the corresponding module resource into
     * WhatsApp's resource table. The returned resource ID is stored back into the static field so
     * hooks can reference it directly without further look-ups.
     */
    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam) throws Throwable {
        String packageName = resparam.packageName;

        if (packageName.equals(BuildConfig.APPLICATION_ID))
            getPref();

        if (!packageName.equals(Utils.WHATSAPP_PACKAGE) && !packageName.equals(Utils.WHATSAPP_WEB_PACKAGE)) return;

        XModuleResources res = XModuleResources.createInstance(PATH, resparam.res);

        for (Field field : ModuleResources.string.class.getFields()) {
            int resId = res.getIdentifier(field.getName(), "string", BuildConfig.APPLICATION_ID);
            int addedResId = resparam.res.addResource(res, resId);
           field.set(null, addedResId);
        }

        for (Field field : ModuleResources.array.class.getFields()) {
            int resId = res.getIdentifier(field.getName(), "array", BuildConfig.APPLICATION_ID);
            int addedResId = resparam.res.addResource(res, resId);
            field.set(null, addedResId);
        }

        for (Field field : ModuleResources.drawable.class.getFields()) {
            int resId = res.getIdentifier(field.getName(), "drawable", BuildConfig.APPLICATION_ID);
            int addedResId = resparam.res.addResource(res, resId);
            field.set(null, addedResId);
        }

        for (Field field : ModuleResources.layout.class.getFields()) {
            int resId = res.getIdentifier(field.getName(), "layout", BuildConfig.APPLICATION_ID);
            int addedResId = resparam.res.addResource(res, resId);
            field.set(null, addedResId);
        }
    }

    /**
     * Called once in Zygote before any app is started. Saves the module APK path so that
     * {@link #handleInitPackageResources} can create an {@link XModuleResources} instance later.
     */
    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        PATH = startupParam.modulePath;
        XposedBridge.log("WaRevamp InitZygote");
    }
}
