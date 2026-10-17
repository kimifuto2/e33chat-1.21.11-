package com.niuqu.chatbubble.compat;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Jade（玉）软兼容桥：读取 Jade 描述框当前是否显示、以及它的屏幕范围，
 * 供灵动岛横幅（MentionNotificationBanner）避让。
 *
 * <p>只经反射访问 Jade 自己的公开成员——Jade 的类名/成员名不在 remap 范围内
 * （被 remap 的只是它内部引用的 MC 类），因此开发环境与正式 jar 行为一致，
 * 既不需要把 Jade 加进编译依赖，也不需要 mixin：
 * <ul>
 *   <li>{@code snownee.jade.overlay.OverlayRenderer.shown} —— 描述框当前是否显示</li>
 *   <li>{@code snownee.jade.overlay.OverlayRenderer.animation} —— TooltipAnimation</li>
 *   <li>{@code snownee.jade.api.ui.TooltipAnimation.expectedRect} —— 描述框矩形</li>
 *   <li>{@code snownee.jade.api.ui.Rect2f.getX/getY/getWidth/getHeight}</li>
 * </ul>
 * 取 {@code expectedRect}（动画的目标矩形）而非动画中的 {@code rect}，
 * 避免描述框自身淡入淡出时横幅跟着抖动。
 *
 * <p>未安装 Jade、未显示描述框或读取失败时一律返回 null / 不生效，不影响其它功能。
 */
public final class JadeCompat {

    private JadeCompat() {}

    private static final String MOD_ID = "jade";
    private static final String OVERLAY_CLASS = "snownee.jade.overlay.OverlayRenderer";
    private static final String ANIMATION_CLASS = "snownee.jade.api.ui.TooltipAnimation";

    /** 描述框矩形（缩放后 GUI 坐标，与 DrawContext / getScaledWidth 同一空间）。 */
    public record Rect(float x, float y, float width, float height) {
        public float right() { return x + width; }
        public float bottom() { return y + height; }
    }

    private static boolean initDone;
    private static Field shownField;
    private static Field animationField;
    private static Field expectedRectField;
    private static Method getX;
    private static Method getY;
    private static Method getWidth;
    private static Method getHeight;

    private static void init() {
        if (initDone) return;
        initDone = true;
        try {
            if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return;
            Class<?> overlay = Class.forName(OVERLAY_CLASS);
            shownField = overlay.getField("shown");
            animationField = overlay.getField("animation");
            Class<?> animationClass = Class.forName(ANIMATION_CLASS);
            expectedRectField = animationClass.getField("expectedRect");
            Class<?> rectClass = expectedRectField.getType();
            getX = rectClass.getMethod("getX");
            getY = rectClass.getMethod("getY");
            getWidth = rectClass.getMethod("getWidth");
            getHeight = rectClass.getMethod("getHeight");
        } catch (Throwable t) {
            // Jade 版本结构不符：整体降级为「不避让」，不要让兼容层影响本体功能
            shownField = null;
            animationField = null;
            expectedRectField = null;
            getX = null;
            getY = null;
            getWidth = null;
            getHeight = null;
        }
    }

    /**
     * 当前 Jade 描述框的矩形；未安装 / 未显示 / 读取失败返回 {@code null}。
     */
    public static Rect tooltipRect() {
        init();
        if (shownField == null || animationField == null || expectedRectField == null
            || getX == null || getY == null || getWidth == null || getHeight == null) {
            return null;
        }
        try {
            if (!shownField.getBoolean(null)) return null;
            Object animation = animationField.get(null);
            if (animation == null) return null;
            Object rect = expectedRectField.get(animation);
            if (rect == null) return null;
            float x = (Float) getX.invoke(rect);
            float y = (Float) getY.invoke(rect);
            float w = (Float) getWidth.invoke(rect);
            float h = (Float) getHeight.invoke(rect);
            if (w <= 0f || h <= 0f) return null;
            return new Rect(x, y, w, h);
        } catch (Throwable t) {
            return null;
        }
    }
}
