package com.niuqu.chatbubble.input;

/**
 * 鼠标键号换算：本模组内部统一用「旧编号」{@code 0=左 / 1=右 / 2=中}。
 *
 * <p>MC 26.2 仍是 GLFW 输入层，原版用的就是这套编号，与内部编号完全一致，
 * 因此这里不需要做任何折算（{@link #legacy(int)} 直接透传）。</p>
 *
 * <p>26.2 字节码证据（{@code minecraft-client.jar}，javap 核对）：</p>
 * <ul>
 *   <li>{@code AbstractWidget.isValidClickButton(MouseButtonInfo)} 方法体是 {@code button() == 0}；</li>
 *   <li>{@code ChatScreen.mouseClicked} 处理聊天文字点击同样以 {@code button() == 0} 为门槛；</li>
 *   <li>{@code InputConstants.KEY_ESCAPE = 256}（GLFW 键码），而非 26.3 的 SDL scancode 41。</li>
 * </ul>
 *
 * <p>注意：26.3（SDL 输入层）改成了 {@code 1=左 / 2=中 / 3=右}，那个版本上必须把键号折回旧编号，
 * 否则「{@code button == 0} 判左键」永远不成立 —— 表现为自绘按钮静默不响应鼠标，而键盘正常，
 * 编译期没有任何报错。本分支是 26.2，保持透传即可。</p>
 */
public final class MouseButtons {
    /** 旧编号：左键。 */
    public static final int LEFT = 0;
    /** 旧编号：右键。 */
    public static final int RIGHT = 1;
    /** 旧编号：中键（两个体系里都是 2）。 */
    public static final int MIDDLE = 2;

    /** 本版本里真实左键的编号（26.2 = GLFW 体系：0）。自造 MouseButtonEvent 时必须传它。 */
    public static final int NATIVE_LEFT = 0;

    private MouseButtons() {
    }

    /**
     * 把本版本的鼠标键号折回旧编号（0=左 / 1=右 / 2=中）。
     *
     * <p>26.2 与旧编号同构，故原样返回。</p>
     */
    public static int legacy(int button) {
        return button;
    }
}
