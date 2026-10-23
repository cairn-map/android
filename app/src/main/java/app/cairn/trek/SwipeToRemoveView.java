package app.cairn.trek;

import android.content.Context;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** Swipe either way to delete; short or cancelled swipes return to the resting position. */
final class SwipeToRemoveView extends FrameLayout {
  private final View foreground, remove;
  private final int slop;
  private final Runnable onRemove;
  private float downX, downY;
  private boolean dragging, vertical, dismissed;

  SwipeToRemoveView(Context context, View foreground, View remove, Runnable onRemove) {
    super(context);
    this.foreground = foreground;
    this.remove = remove;
    this.onRemove = onRemove;
    int actionWidth = Math.round(96 * getResources().getDisplayMetrics().density);
    slop = ViewConfiguration.get(context).getScaledTouchSlop();
    addView(remove, new FrameLayout.LayoutParams(actionWidth, -1, Gravity.RIGHT));
    addView(foreground, new FrameLayout.LayoutParams(-1, -2));
    remove.setVisibility(INVISIBLE);
    foreground.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.addAction(
                new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_DISMISS, "Delete item"));
          }

          @Override
          public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (action == AccessibilityNodeInfo.ACTION_DISMISS) {
              dismiss();
              return true;
            }
            return super.performAccessibilityAction(host, action, args);
          }
        });
  }

  @Override
  public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
    if (dismissed) return true;
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      downX = event.getX();
      downY = event.getY();
      dragging = false;
      vertical = false;
    } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
      float dx = event.getX() - downX, dy = event.getY() - downY;
      if (Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) vertical = true;
      if (!vertical && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
        dragging = true;
        getParent().requestDisallowInterceptTouchEvent(true);
        dragTo(event.getX());
        return true;
      }
    }
    return false;
  }

  private void offset(float value) {
    foreground.setTranslationX(value);
    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) remove.getLayoutParams();
    int gravity = value > 0 ? Gravity.LEFT : Gravity.RIGHT;
    if (params.gravity != gravity) {
      params.gravity = gravity;
      remove.setLayoutParams(params);
    }
    remove.setVisibility(value != 0 ? VISIBLE : INVISIBLE);
  }

  private void dragTo(float x) {
    offset(Math.max(-getWidth(), Math.min(getWidth(), x - downX)));
  }

  private void dismiss() {
    if (dismissed) return;
    dismissed = true;
    offset(foreground.getTranslationX() > 0 ? getWidth() : -getWidth());
    onRemove.run();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (dismissed) return true;
    if (!dragging) return super.onTouchEvent(event);
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_MOVE:
        dragTo(event.getX());
        break;
      case MotionEvent.ACTION_UP:
        dragTo(event.getX());
        if (getWidth() > 0 && Math.abs(foreground.getTranslationX()) >= getWidth() * 0.4f)
          dismiss();
        else offset(0);
        dragging = false;
        break;
      case MotionEvent.ACTION_CANCEL:
      case MotionEvent.ACTION_POINTER_DOWN:
        offset(0);
        dragging = false;
        vertical = true;
        break;
    }
    return true;
  }
}
