package com.niuqu.chatbubble.input;

/**
 * 鼠标键号换算：26.3（SDL 输入层）用的是 1=左 / 2=中 / 3=右，
 * 而 1.21.11 及更早（GLFW）是 0=左 / 1=右 / 2=中。
 *
 * <p>本模组所有自绘命中逻辑都按旧编号书写（{@code button == 0} 判左键），
 * 所以在 {@code mouseClicked} 入口把键号折回旧编号即可，命中判定本身一行都不用改。</p>
 *
 * <p>26.3 字节码证据（minecraft-merged-deobf-26.3.jar，javap 核对）：</p>
 * <ul>
 *   <li>{@code AbstractWidget.isValidClickButton(MouseButtonInfo)} 方法体就是 {@code button() == 1}；</li>
 *   <li>{@code ContainerEventHandler.mouseClicked} 里 {@code setDragging(true)} 的前置判断是
 *       {@code event.button() == 1}；{@code mouseDragged}/{@code mouseReleased} 的同款门槛也是 1；</li>
 *   <li>{@code ChatScreen.mouseClicked} 处理聊天文字点击同样用 {@code button() == 1}；</li>
 *   <li>{@code MouseHandler.onButton} 用 {@code button==1/2/3} 分别置 isLeftPressed/isMiddlePressed/isRightPressed。</li>
 * </ul>
 *
 * <p>反过来看：在 26.3 里「{@code button == 0} 判左键」永远不成立 —— 表现为所有自绘按钮
 * 静默不响应鼠标，而键盘（keyPressed / charTyped）完全正常，编译期没有任何报错。</p>
 */
public final class MouseButtons {
    /** 旧编号：左键。 */
    public static final int LEFT = 0;
    /** 旧编号：右键。 */
    public static final int RIGHT = 1;
    /** 旧编号：中键（两个体系里都是 2）。 */
    public static final int MIDDLE = 2;

    /** 26.3 里真实左键的编号：自造 MouseButtonEvent 时必须传它，传 0 会被原版控件判为非左键。 */
    public static final int SDL_LEFT = 1;

    private MouseButtons() {
    }

    /**
     * 把 26.3 的 SDL 键号折回旧编号（0=左 / 1=右 / 2=中）；未知键号（含已是旧编号的 0）原样返回。
     */
    public static int legacy(int button) {
        if (button == SDL_LEFT) return LEFT;
        if (button == 3) return RIGHT;
        return button;
    }
}
