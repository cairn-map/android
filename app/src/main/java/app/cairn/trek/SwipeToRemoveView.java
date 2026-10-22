package app.cairn.trek;

import android.content.Context;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** A horizontal swipe reveals Remove while vertical gestures keep scrolling the library. */
final class SwipeToRemoveView extends FrameLayout {
  private final View foreground, remove;
  private final int actionWidth, slop;
  private float downX, downY, initialOffset;
  private boolean dragging;

  SwipeToRemoveView(Context context, View foreground, View remove, Runnable onRemove) {
    super(context);
    this.foreground = foreground;
    this.remove = remove;
    actionWidth = Math.round(96 * getResources().getDisplayMetrics().density);
    slop = ViewConfiguration.get(context).getScaledTouchSlop();
    addView(remove, new FrameLayout.LayoutParams(actionWidth, -1, Gravity.RIGHT));
    addView(foreground, new FrameLayout.LayoutParams(-1, -2));
    remove.setVisibility(INVISIBLE);
    foreground.setOnLongClickListener(
        v -> {
          onRemove.run();
          return true;
        });
    foreground.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.addAction(
                new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_DISMISS, "Remove hike"));
          }

          @Override
          public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (action == AccessibilityNodeInfo.ACTION_DISMISS) {
              onRemove.run();
              return true;
            }
            return super.performAccessibilityAction(host, action, args);
          }
        });
  }

  @Override
  public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      downX = event.getX();
      downY = event.getY();
      initialOffset = foreground.getTranslationX();
      dragging = false;
    } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
      float dx = event.getX() - downX, dy = event.getY() - downY;
      if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) && (dx < 0 || initialOffset < 0)) {
        dragging = true;
        getParent().requestDisallowInterceptTouchEvent(true);
        offset(Math.max(-actionWidth, Math.min(0, initialOffset + dx)));
        return true;
      }
    }
    return false;
  }

  private void offset(float value) {
    foreground.setTranslationX(value);
    remove.setVisibility(value < 0 ? VISIBLE : INVISIBLE);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (!dragging) return super.onTouchEvent(event);
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_MOVE:
        offset(Math.max(-actionWidth, Math.min(0, initialOffset + event.getX() - downX)));
        break;
      case MotionEvent.ACTION_UP:
        offset(foreground.getTranslationX() < -actionWidth / 2f ? -actionWidth : 0);
        dragging = false;
        break;
      case MotionEvent.ACTION_CANCEL:
        offset(initialOffset);
        dragging = false;
        break;
    }
    return true;
  }
}
