package app.cairn.trek;

import static app.cairn.trek.AppPalette.*;

import android.app.AlertDialog;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Shared dialogs and content-sized drawers, using the platform's accessible controls. */
final class CairnDialogs {
  static Builder dialog(Context context) {
    return new Builder(context, false);
  }

  static Builder drawer(Context context) {
    return new Builder(context, true);
  }

  static final class Builder extends AlertDialog.Builder {
    private boolean drawer, fullHeight, fullWidth;
    private CharSequence title;

    Builder(Context context, boolean drawer) {
      super(context, R.style.CairnDialogTheme);
      this.drawer = drawer;
    }

    /** Reserve the available height for lists whose length can change. */
    Builder fullHeight() {
      fullHeight = true;
      return this;
    }

    /** Use the available width for complex controls or menus that open sub-drawers. */
    Builder fullWidth() {
      fullWidth = true;
      return this;
    }

    @Override
    public Builder setTitle(CharSequence title) {
      this.title = title;
      super.setTitle(title);
      return this;
    }

    @Override
    public Builder setItems(CharSequence[] items, DialogInterface.OnClickListener listener) {
      drawer = true;
      super.setItems(items, listener);
      return this;
    }

    @Override
    public AlertDialog create() {
      Context context = getContext();
      AlertDialog[] holder = new AlertDialog[1];
      if (title != null || drawer) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        if (drawer) {
          FrameLayout grip = new FrameLayout(context);
          View handle = new View(context);
          handle.setBackground(shape(OUTLINE, 2));
          FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER);
          grip.addView(handle, hp);
          header.addView(grip, new LinearLayout.LayoutParams(-1, dp(28)));
          grip.setContentDescription("Swipe down to close drawer");
        }
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(24), drawer ? 0 : dp(24), dp(12), dp(12));
        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTextSize(24);
        heading.setTextColor(TEXT);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        titleRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (drawer) {
          ImageButton close = new ImageButton(context);
          close.setScaleType(ImageView.ScaleType.FIT_CENTER);
          close.setImageDrawable(new MapIcon(MapIcon.CLOSE));
          close.setPadding(dp(12), dp(12), dp(12), dp(12));
          close.setContentDescription("Close drawer");
          close.setBackground(
              new RippleDrawable(
                  ColorStateList.valueOf(RIPPLE),
                  shape(Color.TRANSPARENT, 24),
                  shape(Color.WHITE, 24)));
          close.setOnClickListener(v -> holder[0].cancel());
          titleRow.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        header.addView(titleRow);
        super.setCustomTitle(header);
      }
      AlertDialog dialog = super.create();
      holder[0] = dialog;
      Window window = dialog.getWindow();
      GradientDrawable surface = shape(SURFACE, 28);
      if (drawer && fullHeight) surface.setCornerRadius(0);
      window.setBackgroundDrawable(drawer ? new ColorDrawable(Color.TRANSPARENT) : surface);
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      window.setDimAmount(0.45f);
      window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      if (drawer) {
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int width =
            fullWidth
                ? -1
                : Math.min(
                    dp(560), context.getResources().getDisplayMetrics().widthPixels - dp(32));
        int height = fullHeight ? -1 : -2;
        window.setLayout(width, height);
        // AlertController installs and sizes its panels during show(), so apply the shared
        // size after attachment as well. Callers can keep their own on-show callbacks.
        window
            .getDecorView()
            .addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                  @Override
                  public void onViewAttachedToWindow(View decor) {
                    window.setLayout(width, height);
                    FrameLayout content = window.findViewById(android.R.id.content);
                    SwipeDrawer swipe = new SwipeDrawer(context, dialog);
                    swipe.setBackground(surface);
                    swipe.setClipToOutline(true);
                    while (content.getChildCount() > 0) {
                      View child = content.getChildAt(0);
                      ViewGroup.LayoutParams params = child.getLayoutParams();
                      content.removeView(child);
                      swipe.addView(child, params);
                    }
                    content.addView(swipe, new FrameLayout.LayoutParams(-1, height));
                    decor.removeOnAttachStateChangeListener(this);
                  }

                  @Override
                  public void onViewDetachedFromWindow(View decor) {}
                });
        window.setWindowAnimations(R.style.CairnDrawerAnimation);
      }
      return dialog;
    }

    private int dp(int value) {
      return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shape(int color, int radius) {
      GradientDrawable shape = new GradientDrawable();
      shape.setColor(color);
      shape.setCornerRadius(dp(radius));
      return shape;
    }
  }

  /** Intercept downward drags only when the touched content is already at its top. */
  private static final class SwipeDrawer extends FrameLayout {
    private final AlertDialog dialog;
    private final int slop;
    private float downX, downY;
    private boolean eligible, dragging;
    private VelocityTracker velocity;

    SwipeDrawer(Context context, AlertDialog dialog) {
      super(context);
      this.dialog = dialog;
      setBackgroundColor(SURFACE);
      slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
        animate().cancel();
        downX = event.getRawX();
        downY = event.getRawY();
        dragging = false;
        eligible = !canScrollUpAt(this, event.getX(), event.getY());
        if (velocity != null) velocity.recycle();
        velocity = VelocityTracker.obtain();
      }
      if (velocity != null) {
        MotionEvent raw = MotionEvent.obtain(event);
        raw.offsetLocation(event.getRawX() - event.getX(), event.getRawY() - event.getY());
        velocity.addMovement(raw);
        raw.recycle();
      }
      if (event.getPointerCount() > 1) eligible = false;
      boolean handled = super.dispatchTouchEvent(event);
      if (event.getActionMasked() == MotionEvent.ACTION_UP
          || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
        if (velocity != null) velocity.recycle();
        velocity = null;
        dragging = false;
      }
      return handled;
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallow) {
      // ScrollView may claim a sequence on DOWN while stopping an old fling. At its
      // top, keep observing the direction so a downward swipe can still close it.
      super.requestDisallowInterceptTouchEvent(disallow && !eligible);
    }

    private void beginDrag(MotionEvent event) {
      float dy = event.getRawY() - downY;
      if (event.getActionMasked() == MotionEvent.ACTION_MOVE
          && eligible
          && dy > slop
          && dy > Math.abs(event.getRawX() - downX)
          && !canScrollUpAt(this, event.getX(), event.getY())) {
        dragging = true;
        getParent().requestDisallowInterceptTouchEvent(true);
      }
      // The intercepting MOVE only cancels the child; onTouchEvent starts with the next
      // event. Apply this first displacement here so even a quick swipe follows the finger.
      if (dragging && event.getActionMasked() == MotionEvent.ACTION_MOVE)
        setTranslationY(Math.max(0, dy));
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
      beginDrag(event);
      return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      // Empty areas have no child touch target, so their MOVE events arrive here directly.
      beginDrag(event);
      if (!dragging) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        return true;
      }
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_UP:
          velocity.computeCurrentVelocity(1000);
          float density = getResources().getDisplayMetrics().density;
          if (eligible
              && (getTranslationY() > 72 * density
                  || getTranslationY() > slop && velocity.getYVelocity() > 1000 * density)) {
            dialog.cancel();
            break;
          }
        // Fall through to restore the drawer after a short drag.
        case MotionEvent.ACTION_CANCEL:
          animate().translationY(0).setDuration(160).start();
          break;
      }
      return true;
    }

    @Override
    public boolean performClick() {
      return super.performClick();
    }

    @Override
    protected void onDetachedFromWindow() {
      animate().cancel();
      setTranslationY(0);
      if (velocity != null) velocity.recycle();
      velocity = null;
      super.onDetachedFromWindow();
    }

    private static boolean canScrollUpAt(View view, float x, float y) {
      if (view.canScrollVertically(-1)) return true;
      if (view instanceof ViewGroup) {
        ViewGroup group = (ViewGroup) view;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
          View child = group.getChildAt(i);
          float cx = x + view.getScrollX() - child.getLeft();
          float cy = y + view.getScrollY() - child.getTop();
          if (child.getVisibility() == VISIBLE
              && cx >= 0
              && cy >= 0
              && cx < child.getWidth()
              && cy < child.getHeight()
              && canScrollUpAt(child, cx, cy)) return true;
        }
      }
      return false;
    }
  }

  private CairnDialogs() {}
}
