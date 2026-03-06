package its.madruga.warevamp.module.hooks.core;


import static its.madruga.warevamp.module.references.References.homeActivityClass;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Instrumentation;
import android.os.Bundle;

import androidx.annotation.NonNull;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import its.madruga.warevamp.broadcast.receivers.WhatsAppReceiver;
import its.madruga.warevamp.broadcast.senders.WhatsAppSender;
import its.madruga.warevamp.module.core.WppCallback;
import its.madruga.warevamp.module.hooks.customization.HideArchivedChatsHook;
import its.madruga.warevamp.module.hooks.functions.CallPrivacyHook;
import its.madruga.warevamp.module.hooks.media.DownloadStatusHook;
import its.madruga.warevamp.module.hooks.media.DownloadViewOnceHook;
import its.madruga.warevamp.module.hooks.functions.CustomPrivacyHook;
import its.madruga.warevamp.module.hooks.others.PinnedLimit;
import its.madruga.warevamp.module.hooks.customization.SeparateGroupsHook;
import its.madruga.warevamp.module.hooks.others.MenuHook;
import its.madruga.warevamp.module.hooks.privacy.DndModeHook;
import its.madruga.warevamp.module.hooks.privacy.FreezeLastSeenHook;
import its.madruga.warevamp.module.hooks.privacy.HideReadHook;
import its.madruga.warevamp.module.hooks.privacy.HideReceiptHook;
import its.madruga.warevamp.module.hooks.privacy.HideTypingRecordingHook;
import its.madruga.warevamp.module.references.References;
import its.madruga.warevamp.module.hooks.media.MediaQualityHook;
import its.madruga.warevamp.module.hooks.others.OthersHook;
import its.madruga.warevamp.module.hooks.functions.AntiRevokeHook;
import its.madruga.warevamp.module.hooks.functions.AntiViewOnceHook;
import its.madruga.warevamp.module.references.ReferencesCache;

/**
 * Central orchestrator for all WaRevamp hooks inside the WhatsApp process.
 *
 * <h3>Initialisation sequence</h3>
 * <ol>
 *   <li>{@link #initialize} is called from {@link its.madruga.warevamp.module.ModuleStart}
 *       when WhatsApp's package is loaded.</li>
 *   <li>DexKit is started via {@link References#initDexKit} so WhatsApp's obfuscated bytecode
 *       can be searched at runtime.</li>
 *   <li>A hook on {@code Instrumentation.callApplicationOnCreate} captures the
 *       {@link Application} instance as soon as WhatsApp creates it, then:
 *       <ul>
 *         <li>Registers a {@link WppCallback} to monitor Activity lifecycle events.</li>
 *         <li>Initialises {@link ReferencesCache} to persist resolved method/field paths.</li>
 *         <li>Calls {@link References#start()} to resolve all required WhatsApp symbols.</li>
 *         <li>Calls {@link #plugins} to load every feature hook.</li>
 *         <li>Starts the broadcast receivers/senders for cross-process IPC.</li>
 *       </ul>
 *   </li>
 *   <li>A hook on WhatsApp's home {@code Activity.onCreate} stores the {@link Activity}
 *       reference and shows an error dialog if any hooks failed to load.</li>
 * </ol>
 *
 * <h3>Plugin loading</h3>
 * {@link #plugins} iterates over a fixed array of hook classes, constructs each one via
 * reflection ({@code constructor(ClassLoader, XSharedPreferences)}), and invokes
 * {@link HooksBase#doHook()}. If a hook throws an {@link java.lang.reflect.InvocationTargetException}
 * its simple class name is added to {@link #list} and reported to the user.
 */
public class HooksLoader {
    /** The WhatsApp {@link Application} instance captured after app creation. */
    public static Application mApp;
    /** The WhatsApp home {@link Activity} captured after its first {@code onCreate}. */
    @SuppressLint("StaticFieldLeak")
    public static Activity home;
    /** Hook class names that failed to load; shown in an error dialog on home-screen open. */
    public static ArrayList<String> list = new ArrayList<>();

    /**
     * Starts DexKit and installs the top-level Instrumentation/Activity hooks.
     * Must be called from {@link its.madruga.warevamp.module.ModuleStart#handleLoadPackage}.
     *
     * @param pref      the module's shared preferences
     * @param loader    WhatsApp's class loader
     * @param sourceDir path to WhatsApp's base APK (used by DexKit for bytecode scanning)
     * @throws Exception if DexKit fails to initialise or a required class cannot be found
     */
    public static void initialize(XSharedPreferences pref, ClassLoader loader, String sourceDir) throws Exception {

        XposedBridge.log("Starting WhatsApp Broadcasts");
        if (!References.initDexKit(sourceDir)) {
            XposedBridge.log("Unable to start DexKit");
            return;
        } else {
            XposedBridge.log("DexKit Init");
        }

        XposedBridge.log("Starting WhatsApp Broadcasts");
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate", Application.class, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam param) throws Exception {
                mApp = (Application) param.args[0];
                mApp.registerActivityLifecycleCallbacks(new WppCallback());
                ReferencesCache.init(mApp, loader);
                References.start();
                plugins(loader, pref);
                // Initializing WhatsApp Broadcasts
                XposedBridge.log("Starting WhatsApp Broadcasts");
                WhatsAppSender.start();
                WhatsAppReceiver.start();
            }
        });

        XposedHelpers.findAndHookMethod(homeActivityClass(loader), "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                home = (Activity) param.thisObject;

                if (!list.isEmpty()) {
                    new AlertDialog.Builder(home)
                            .setTitle("Error detected")
                            .setMessage("The following functions are in error:\n\n" + String.join("\n", list.toArray(new String[0])))
                            .show();
                }
            }
        });
    }

    /**
     * Instantiates and activates every feature hook registered in the {@code classes} array.
     *
     * <p>Each hook class must expose a public constructor with signature
     * {@code (ClassLoader, XSharedPreferences)} and a no-arg {@code doHook()} method
     * (inherited from {@link HooksBase}).  Hooks that throw during {@code doHook()} are
     * silently skipped but their names are recorded in {@link #list} for later reporting.
     *
     * @param loader class loader passed to each hook
     * @param pref   shared preferences passed to each hook
     */
    private static void plugins(@NonNull ClassLoader loader, @NonNull XSharedPreferences pref) {
        ArrayList<String> loadedClasses = new ArrayList<>();
        var classes = new Class<?>[]{
                AntiRevokeHook.class,
                AntiViewOnceHook.class,
                MediaQualityHook.class,
                OthersHook.class,
                MenuHook.class,
                DndModeHook.class,
                HideReceiptHook.class,
                SeparateGroupsHook.class,
                PinnedLimit.class,
                HideReadHook.class,
                HideArchivedChatsHook.class,
                DownloadStatusHook.class,
                DownloadViewOnceHook.class,
                HideTypingRecordingHook.class,
                FreezeLastSeenHook.class,
                CustomPrivacyHook.class,
                CallPrivacyHook.class
        };

        for (var c : classes) {
            try {
                var constructor = c.getConstructor(ClassLoader.class, XSharedPreferences.class);
                var plugin = constructor.newInstance(loader, pref);
                var method = c.getMethod("doHook");
                method.invoke(plugin);
                loadedClasses.add("-> "  + c.getName());
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException |
                     InstantiationException e) {
                XposedBridge.log(e);
                if (e instanceof InvocationTargetException) {
                    list.add(c.getSimpleName());
                }
            }
        }

        XposedBridge.log("Loaded Class:\n\n" + String.join("\n", loadedClasses.toArray(new String[0])));
    }
}
