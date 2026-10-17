package com.niuqu.chatbubble.render;

public record TextSpan(int messageIndex, int lineIndex, int kind, int x, int y, int w, int h, String text, float scale, Object visualLine) {
   public static final int KIND_NAME = 0;
   public static final int KIND_CONTENT = 1;
   public static final int KIND_QUOTE = 2;

   public TextSpan(int messageIndex, int lineIndex, int kind, int x, int y, int w, int h, String text, float scale) {
      this(messageIndex, lineIndex, kind, x, y, w, h, text, scale, null);
   }

   public TextSpan withPosition(int nx, int ny, int nw, int nh) {
      return new TextSpan(this.messageIndex, this.lineIndex, this.kind, nx, ny, nw, nh, this.text, this.scale, this.visualLine);
   }

   public long orderKey() {
      return this.messageIndex * 100000L + this.kind * 10000L + this.lineIndex;
   }
}
