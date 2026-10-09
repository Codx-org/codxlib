package codx.codxlib.api.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side helper for showing the vanilla "system toast" (the slide-in card in the
 * top-right corner) without each mod re-deriving the {@link SystemToast} / toast-manager
 * plumbing (which has been renamed and re-homed several times across MC versions).
 *
 * <p>This is the client half of CodxLib's notification toolkit; the server/shared
 * chat half lives in {@link codx.codxlib.api.CodxNotify}.
 *
 * <p><b>Client-only.</b> These methods touch {@link Minecraft} and must only be called
 * on the client (e.g. from a {@code Screen}, keybind handler, or client command). They
 * no-op safely if no client instance is running.
 */
public final class CodxToast {

    /**
     * Stable tokens for keyed toasts, so repeated calls update one card instead of stacking.
     * Before 1.20.3 the token type is the fixed {@code SystemToastIds} enum rather than an
     * instantiable id, so there every keyed toast shares one token and this map goes unused.
     */
    //? if >=1.20.3 {
    private static final Map<String, SystemToast.SystemToastId> TOKENS = new ConcurrentHashMap<>();
    //?} else {
    /*private static final Map<String, SystemToast.SystemToastIds> TOKENS = new ConcurrentHashMap<>();*/
    //?}

    private CodxToast() {
    }

    /** Shows a transient toast with the given title and (optional) description line. */
    public static void show(Component title, Component description) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || title == null) {
            return;
        }
        addToast(mc, null, title, description == null ? Component.empty() : description);
    }

    /** Convenience string overload of {@link #show(Component, Component)}. */
    public static void show(String title, String description) {
        show(title == null ? null : Component.literal(title),
            description == null ? null : Component.literal(description));
    }

    /**
     * Shows or updates a <em>keyed</em> toast: repeated calls with the same {@code key}
     * replace the existing card (resetting its timer) rather than stacking a new one —
     * useful for progress/status that changes over time.
     */
    public static void show(String key, Component title, Component description) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || key == null || title == null) {
            return;
        }
        addToast(mc, key, title, description == null ? Component.empty() : description);
    }

    // The toast plumbing moved three times: the accessor (Minecraft.getToasts ->
    // getToastManager -> Gui.toastManager), the manager type (ToastComponent ->
    // ToastManager) and the token type (the fixed SystemToastIds enum -> instantiable
    // SystemToastId). `key == null` means "always a new card". On the pre-1.20.3 branch
    // PERIODIC_NOTIFICATION is the closest vanilla token to "a mod said something", and every
    // codx toast shares it, so an unkeyed toast also replaces the previous card there.
    //? if >=26.2 {
    /*private static void addToast(Minecraft mc, String key, Component title, Component description) {
        if (key == null) {
            SystemToast.add(mc.gui.toastManager(), new SystemToast.SystemToastId(), title, description);
        } else {
            SystemToast.addOrUpdate(mc.gui.toastManager(),
                TOKENS.computeIfAbsent(key, k -> new SystemToast.SystemToastId()), title, description);
        }
    }
    *///?} elif >=1.21.2 {
    private static void addToast(Minecraft mc, String key, Component title, Component description) {
        if (key == null) {
            SystemToast.add(mc.getToastManager(), new SystemToast.SystemToastId(), title, description);
        } else {
            SystemToast.addOrUpdate(mc.getToastManager(),
                TOKENS.computeIfAbsent(key, k -> new SystemToast.SystemToastId()), title, description);
        }
    }
    //?} elif >=1.20.3 {
    /*private static void addToast(Minecraft mc, String key, Component title, Component description) {
        if (key == null) {
            SystemToast.add(mc.getToasts(), new SystemToast.SystemToastId(), title, description);
        } else {
            SystemToast.addOrUpdate(mc.getToasts(),
                TOKENS.computeIfAbsent(key, k -> new SystemToast.SystemToastId()), title, description);
        }
    }
    *///?} else {
    /*private static void addToast(Minecraft mc, String key, Component title, Component description) {
        SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
            title, description);
    }
    *///?}
}
