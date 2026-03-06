package its.madruga.warevamp.module.core;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import its.madruga.warevamp.module.references.ReferencesUtils;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static its.madruga.warevamp.module.references.References.*;

/**
 * Reflection-based wrapper around WhatsApp's internal {@code FMessage} object.
 *
 * <p>WhatsApp's code is heavily obfuscated, so fields and methods cannot be accessed by name.
 * This class uses the resolved references from {@link References} to expose a clean Java API for
 * the data hooks need most. It is initialised lazily on first use and caches all resolved
 * reflective handles as static fields to avoid repeated look-ups.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * FMessageInfo info = new FMessageInfo(param.args[0]);
 * String text   = info.getMessageStr();
 * File   media  = info.getMediaFile();
 * FMessageInfo.Key key = info.getKey();
 * }</pre>
 */
public class FMessageInfo {
    private final Object messageObject;
    private static Method messageMethod;
    private static Method messageWithMediaMethod;
    private static Field keyMessage;
    private static Field mediaTypeField;
    private static Class<?> mediaMessageClass;
    private static boolean initialized;
    public static Class<?> TYPE;

    /**
     * Creates a new {@code FMessageInfo} wrapping the given raw WhatsApp message object.
     * Initialises all static reflective handles on first call.
     *
     * @param message the raw {@code FMessage} instance obtained from an Xposed hook parameter
     * @throws RuntimeException if {@code message} is {@code null} or reflection initialisation fails
     */
    public FMessageInfo(Object message) {
        if (message == null) throw new RuntimeException("Message is null");
        this.messageObject = message;
        try {
            init(message.getClass().getClassLoader());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void init(ClassLoader loader) throws Exception {
        if (initialized) return;
        initialized = true;
        TYPE = FMessageClass(loader);
        keyMessage = keyMessageField(loader);
        mediaTypeField = mediaTypeField(loader);
        messageMethod = newMessageMethod(loader);
        messageWithMediaMethod = newMessageWithMediaMethod(loader);
        mediaMessageClass = mediaMessageClass(loader);
    }

    /**
     * Returns the message key, which carries the message ID, sender JID, and direction flag.
     * Returns {@code null} if reflection fails.
     */
    public Key getKey() {
        try {
            return new Key(keyMessage.get(messageObject));
        } catch (Exception e) {
            XposedBridge.log(e);
            return null;
        }
    }

    /**
     * Returns the plain-text body of the message. For media messages the caption is returned.
     * Returns {@code null} if neither text nor caption is present.
     */
    public String getMessageStr() {
        try {
            String message = (String) messageMethod.invoke(messageObject);
            if (message != null) return message;
            return (String) messageWithMediaMethod.invoke(messageObject);
        } catch (Exception e) {
            XposedBridge.log(e);
            return null;
        }
    }

    /** Returns {@code true} if this message carries a media attachment. */
    public boolean isMediaFile() {
        try {
            return mediaMessageClass.isInstance(messageObject);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Locates the {@link File} representing the downloaded media attachment by walking the
     * media-message object's field graph looking for a field whose type contains a {@code File}
     * field. Returns {@code null} for text-only messages or if the media file cannot be found.
     */
    public File getMediaFile() {
        try {
            if (!isMediaFile()) {
                return null;
            }
            for (var field : mediaMessageClass.getDeclaredFields()) {
                if (field.getType().isPrimitive()) continue;
                var fileField = ReferencesUtils.getFieldByType(field.getType(), File.class);
                if (fileField != null) {
                    var mediaFile = ReferencesUtils.getObjectField(field, messageObject);
                    return (File) fileField.get(mediaFile);
                }
            }
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return null;
    }

    /**
     * Returns the integer media-type constant stored in the message object, or {@code -1} on error.
     * Common values include image, video, audio, document, and sticker constants used by WhatsApp.
     */
    public int getMediaType() {
        try {
            return mediaTypeField.getInt(messageObject);
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return -1;
    }

    public Object getObject() {
        return messageObject;
    }

    /**
     * Immutable snapshot of a WhatsApp message key extracted via reflection.
     *
     * <p>Field names ({@code A00}, {@code A01}, {@code A02}) are WhatsApp's obfuscated names and
     * may change with WhatsApp updates; the corresponding references are updated in
     * {@link References}.
     */
    public static class Key {

        /** The raw key object from WhatsApp (retained for further reflection if needed). */
        public final Object thisObject;
        /** The unique message ID string assigned by WhatsApp. */
        public final String messageID;
        /** {@code true} if this message was sent by the local user. */
        public final boolean isFromMe;
        /** The JID of the remote chat (contact or group). */
        public final Object remoteJid;

        public Key(Object key) {
            this.thisObject = key;
            this.messageID = (String) XposedHelpers.getObjectField(key, "A01");
            this.isFromMe = XposedHelpers.getBooleanField(key, "A02");
            this.remoteJid = XposedHelpers.getObjectField(key, "A00");
        }

    }
}
