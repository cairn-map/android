package app.cairn.trek;

import android.content.Context;
import android.graphics.Canvas;
import android.widget.TextView;

/** Keep metric groups together and distribute spare width between them. */
final class ProfileReadout extends TextView {
  private String[] groups = new String[0];

  ProfileReadout(Context context, int color, boolean bold) {
    super(context);
    setTextSize(12);
    setTextColor(color);
    if (bold) setTypeface(null, android.graphics.Typeface.BOLD);
    setSingleLine(true);
    setLayoutDirection(LAYOUT_DIRECTION_LTR);
    int densityPadding = Math.round(10 * getResources().getDisplayMetrics().density);
    int verticalPadding = Math.round(3 * getResources().getDisplayMetrics().density);
    setPadding(densityPadding, verticalPadding, densityPadding, verticalPadding);
  }

  @Override
  protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
    super.onTextChanged(text, start, lengthBefore, lengthAfter);
    groups = text.toString().split(" · |   ");
  }

  @Override
  protected void onDraw(Canvas canvas) {
    float width = getWidth() - getPaddingLeft() - getPaddingRight();
    float naturalWidth = getPaint().measureText(getText().toString());
    if (groups.length < 2 || naturalWidth > width) {
      // The surrounding scroll view preserves large text on narrow screens.
      super.onDraw(canvas);
      return;
    }
    float contentWidth = 0;
    for (String group : groups) contentWidth += getPaint().measureText(group);
    float gap = (width - contentWidth) / (groups.length - 1);
    float x = getPaddingLeft();
    getPaint().setColor(getCurrentTextColor());
    for (String group : groups) {
      canvas.drawText(group, x, getBaseline(), getPaint());
      x += getPaint().measureText(group) + gap;
    }
  }
}
