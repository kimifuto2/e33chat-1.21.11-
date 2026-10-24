package com.niuqu.chatbubble.render;

import java.util.List;

public final class ChatTextSelection {
   private static final int SELECTION_BG = -533893162;
   private static final int SELECTION_FG = -1;
   private int anchorMsg = -1;
   private int anchorLine = -1;
   private int anchorKind = -1;
   private int anchorChar = -1;
   private int focusMsg = -1;
   private int focusLine = -1;
   private int focusKind = -1;
   private int focusChar = -1;
   private boolean dragActive;
   private boolean moved;

   public ChatTextSelection() {
   }

   public static int selectionBg() {
      return -533893162;
   }

   public static int selectionFg() {
      return -1;
   }

   public void begin(int msg, int line, int kind, int ch) {
      this.anchorMsg = this.focusMsg = msg;
      this.anchorLine = this.focusLine = line;
      this.anchorKind = this.focusKind = kind;
      this.anchorChar = this.focusChar = ch;
      this.dragActive = true;
      this.moved = false;
   }

   public void update(int msg, int line, int kind, int ch) {
      if (this.dragActive) {
         this.focusMsg = msg;
         this.focusLine = line;
         this.focusKind = kind;
         this.focusChar = ch;
      }
   }

   public void endDrag() {
      this.dragActive = false;
   }

   public boolean isDragActive() {
      return this.dragActive;
   }

   public boolean didMove() {
      return this.moved;
   }

   public void markMoved() {
      if (this.dragActive) {
         this.moved = true;
      }
   }

   public void clear() {
      this.anchorMsg = this.focusMsg = -1;
      this.anchorLine = this.focusLine = -1;
      this.anchorKind = this.focusKind = -1;
      this.anchorChar = this.focusChar = -1;
      this.dragActive = false;
      this.moved = false;
   }

   public boolean hasSelection() {
      if (this.anchorMsg < 0 || this.focusMsg < 0) {
         return false;
      } else {
         return this.anchorMsg == this.focusMsg && this.anchorLine == this.focusLine && this.anchorKind == this.focusKind
            ? this.anchorChar != this.focusChar
            : true;
      }
   }

   /** 选区起点所在消息的下标（全量列表下标）：Ctrl+C 的兜底复制用它定位整条消息。 */
   public int anchorMessage() {
      return this.anchorMsg;
   }

   private static long key(int msg, int line, int kind) {
      return msg * 100000L + kind * 10000L + line;
   }

   public int[] rangeFor(TextSpan span) {
      if (this.anchorMsg >= 0 && this.focusMsg >= 0) {
         long a = key(this.anchorMsg, this.anchorLine, this.anchorKind);
         long f = key(this.focusMsg, this.focusLine, this.focusKind);
         long s = span.orderKey();
         int len = span.text().codePointCount(0, span.text().length());
         int start;
         int end;
         if (a == f) {
            if (s != a) {
               return null;
            }

            start = Math.min(this.anchorChar, this.focusChar);
            end = Math.max(this.anchorChar, this.focusChar);
         } else if (a < f) {
            if (s < a || s > f) {
               return null;
            }

            start = s == a ? this.anchorChar : 0;
            end = s == f ? this.focusChar : len;
         } else {
            if (s < f || s > a) {
               return null;
            }

            start = s == f ? this.focusChar : 0;
            end = s == a ? this.anchorChar : len;
         }

         start = Math.max(0, Math.min(len, start));
         end = Math.max(0, Math.min(len, end));
         return end <= start ? null : new int[]{start, end};
      } else {
         return null;
      }
   }

   public String copyText(List<TextSpan> spans) {
      StringBuilder sb = new StringBuilder();
      boolean first = true;

      for (TextSpan span : spans) {
         int[] r = this.rangeFor(span);
         if (r != null) {
            if (!first) {
               sb.append('\n');
            }

            int cs = span.text().offsetByCodePoints(0, r[0]);
            int ce = span.text().offsetByCodePoints(0, r[1]);
            sb.append(span.text(), cs, ce);
            first = false;
         }
      }

      return sb.toString();
   }
}
