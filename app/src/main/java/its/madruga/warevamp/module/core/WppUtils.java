package its.madruga.warevamp.module.core;

import android.database.Cursor;
import android.util.Log;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import its.madruga.warevamp.module.core.databases.WaDatabase;

import static its.madruga.warevamp.module.hooks.core.HooksLoader.mApp;

/**
 * General-purpose utility methods shared across all WaRevamp hooks.
 *
 * <p>Provides helpers for:
 * <ul>
 *   <li>Stripping WhatsApp JID suffixes ({@link #stripJID}).</li>
 *   <li>Extracting the raw string from an opaque JID object ({@link #getRawString}).</li>
 *   <li>Parsing WhatsApp's array-like string representation ({@link #StringToStringArray}).</li>
 *   <li>Resolving resource IDs inside the WhatsApp process ({@link #getResourceId}).</li>
 *   <li>Querying contact display names from {@code wa.db} ({@link #getContactName}).</li>
 * </ul>
 */
public class WppUtils {

    /**
     * Strips the domain suffix from a WhatsApp JID string.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "12345@s.whatsapp.net"} → {@code "12345"}</li>
     *   <li>{@code "12345-67890@g.us"} → {@code "12345-67890"}</li>
     *   <li>{@code "status@broadcast"} → {@code "status"}</li>
     *   <li>{@code "12345"} (already stripped) → {@code "12345"}</li>
     * </ul>
     *
     * @param str the raw JID string, possibly containing a domain suffix
     * @return the JID without its domain, or the original string if no suffix is present
     */
    public static String stripJID(String str) {
        try {
            return (str.contains("@g.us") || str.contains("@s.whatsapp.net") || str.contains("@broadcast")) ? str.substring(0, str.indexOf("@")) : str;
        } catch (Exception e) {
            XposedBridge.log(e.getMessage());
            return str;
        }
    }

    /**
     * Calls {@code getRawString()} reflectively on a WhatsApp JID object to obtain its string
     * representation.
     *
     * @param objJid a WhatsApp JID object (type is obfuscated); may be {@code null}
     * @return the raw JID string, or an empty string if {@code objJid} is {@code null}
     */
    public static String getRawString(Object objJid) {
        if (objJid == null) return "";
        else return (String) XposedHelpers.callMethod(objJid, "getRawString");
    }

    /**
     * Parses a string that was serialised by {@link java.util.Arrays#toString} back into a
     * {@code String[]}. Leading/trailing brackets and internal whitespace are stripped before
     * splitting on commas.
     *
     * @param str a string of the form {@code "[elem1, elem2, …]"}
     * @return an array of element strings, or {@code null} if parsing fails
     */
    public static String[] StringToStringArray(String str) {
        try {
            return str.substring(1, str.length() - 1).replaceAll("\\s", "").split(",");
        } catch (Exception unused) {
            return null;
        }
    }

    /**
     * Resolves a resource name to its integer resource ID within WhatsApp's process. Logs an
     * error and returns {@code 0} if the resource is not found.
     *
     * @param name the resource name (e.g. {@code "ic_block_small"})
     * @param type the resource type (e.g. {@code "drawable"}, {@code "string"})
     * @return the integer resource ID, or {@code 0} if the resource cannot be found
     */
    public static int getResourceId(String name, String type) {
        int id = mApp.getResources().getIdentifier(name, type, mApp.getPackageName());
        if (id == 0) {
           Log.e("WaRevamp", "Resource not found: " + name);
        }
        return id;
    }

    /**
     * Queries the {@code wa_contacts} table in {@code wa.db} for a contact's display name.
     *
     * @param id   the JID (without domain suffix) to look up
     * @param save if {@code true}, only returns names for contacts that have been saved
     *             ({@code raw_contact_id > 0}); if {@code false}, returns any known name
     * @return the contact's display name, or an empty string if not found
     */
    public static String getContactName(String id, boolean save) {
        WaDatabase db = WaDatabase.getInstance();
        String name = null;
        String queryString;
        if (save) {
            queryString = "jid = ? AND raw_contact_id > 0";
        } else {
            queryString = "jid = ?";
        }

        Cursor cursor = db.getDatabase().query("wa_contacts", new String[]{"display_name"}, queryString, new String[]{id}, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            name = cursor.getString(0);
            cursor.close();
        }
        return name == null ? "" : name;
    }

}
