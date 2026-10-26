package app.cairn.trek;

import android.view.*;
import android.widget.FrameLayout;

/** Keeps the original button's touch, ripple and accessibility behavior while showing progress. */
final class ProgressButton extends FrameLayout {
  final LoadingBar bar;

  ProgressButton(View button) {
    super(button.getContext());
    if (button.getLayoutParams() != null) setLayoutParams(button.getLayoutParams());
    addView(button, new LayoutParams(-1, -1));
    bar = new LoadingBar(getContext());
    bar.setContentDescription(button.getContentDescription());
    int inset = Math.round(8 * getResources().getDisplayMetrics().density);
    LayoutParams params = new LayoutParams(-1, Math.max(1, inset / 2), Gravity.BOTTOM);
    params.setMargins(inset, 0, inset, inset);
    addView(bar, params);
    bar.setVisibility(GONE);
  }

  void progress(boolean busy, double fraction) {
    bar.setVisibility(busy ? VISIBLE : GONE);
    if (busy) bar.showProgress(fraction);
  }
}
