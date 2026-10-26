package app.cairn.trek;

import android.view.*;
import android.widget.FrameLayout;

/** Keeps the original button's touch, ripple and accessibility behavior while showing progress. */
final class ProgressButton extends FrameLayout {
  final PerimeterProgress bar;

  ProgressButton(View button) {
    this(button, 16);
  }

  ProgressButton(View button, int cornerDp) {
    super(button.getContext());
    if (button.getLayoutParams() != null) setLayoutParams(button.getLayoutParams());
    addView(button, new LayoutParams(-1, -1));
    bar = new PerimeterProgress(getContext(), cornerDp);
    bar.setContentDescription(button.getContentDescription());
    addView(bar, new LayoutParams(-1, -1));
    bar.setVisibility(GONE);
  }

  void progress(boolean busy, double fraction) {
    bar.setVisibility(busy ? VISIBLE : GONE);
    if (busy) bar.showProgress(fraction);
  }
}
