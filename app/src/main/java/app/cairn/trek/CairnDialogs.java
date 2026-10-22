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

/** Shared rounded dialogs and bottom drawers, using the platform's accessible controls. */
final class CairnDialogs {
  static Builder dialog(Context context) {
    return new Builder(context, false);
  }

  static Builder drawer(Context context) {
    return new Builder(context, true);
  }

  static final class Builder extends AlertDialog.Builder {
    private boolean drawer;
    private CharSequence title;

    Builder(Context context, boolean drawer) {
      super(context, R.style.CairnDialogTheme);
      this.drawer = drawer;
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
          grip.setOnTouchListener(
              new View.OnTouchListener() {
                float down;

                @Override
                public boolean onTouch(View view, MotionEvent event) {
                  View decor = holder[0].getWindow().getDecorView();
                  if (event.getActionMasked() == MotionEvent.ACTION_DOWN) down = event.getRawY();
                  else if (event.getActionMasked() == MotionEvent.ACTION_MOVE)
                    decor.setTranslationY(Math.max(0, event.getRawY() - down));
                  else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (decor.getTranslationY() > dp(72)) holder[0].cancel();
                    else {
                      decor.animate().translationY(0).setDuration(160).start();
                      view.performClick();
                    }
                  } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                    decor.animate().translationY(0).setDuration(160).start();
                  return true;
                }
              });
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
      if (drawer) surface.setCornerRadii(new float[] {dp(28), dp(28), dp(28), dp(28), 0, 0, 0, 0});
      window.setBackgroundDrawable(surface);
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      window.setDimAmount(0.45f);
      window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      if (drawer) {
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        window.setLayout(
            Math.min(context.getResources().getDisplayMetrics().widthPixels, dp(600)), -2);
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

  private CairnDialogs() {}
}
