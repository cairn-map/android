package app.cairn.trek;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small platform-only icons; no bitmap assets or icon-library dependency. */
final class MapIcon extends Drawable {
  static final int LOCATE = 0, ONLINE = 1, OFFLINE = 2, MAPS = 3, SEARCH = 4, PATH = 5;
  private final int kind;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  MapIcon(int kind) {
    this.kind = kind;
  }

  @Override
  public void draw(Canvas canvas) {
    int save = canvas.save();
    Rect b = getBounds();
    canvas.translate(b.left, b.top);
    canvas.scale(b.width() / 24f, b.height() / 24f);
    paint.setColor(AppPalette.ACCENT);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.8f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    if (kind == PATH) {
      canvas.drawCircle(5, 19, 2.5f, paint);
      canvas.drawCircle(19, 5, 2.5f, paint);
      Path path = new Path();
      path.moveTo(7.5f, 19);
      path.lineTo(15, 19);
      path.cubicTo(22, 19, 22, 12, 15, 12);
      path.lineTo(9, 12);
      path.cubicTo(2, 12, 2, 5, 9, 5);
      path.lineTo(16.5f, 5);
      canvas.drawPath(path, paint);
    } else if (kind == SEARCH) {
      canvas.drawCircle(10, 10, 6.5f, paint);
      canvas.drawLine(15, 15, 21, 21, paint);
    } else if (kind == MAPS) {
      Path layer = new Path();
      layer.moveTo(3, 8);
      layer.lineTo(12, 3);
      layer.lineTo(21, 8);
      layer.lineTo(12, 13);
      layer.close();
      canvas.drawPath(layer, paint);
      canvas.drawLine(3, 12, 12, 17, paint);
      canvas.drawLine(12, 17, 21, 12, paint);
      canvas.drawLine(3, 16, 12, 21, paint);
      canvas.drawLine(12, 21, 21, 16, paint);
    } else if (kind == LOCATE) {
      canvas.drawCircle(12, 12, 6.5f, paint);
      canvas.drawLine(12, 2, 12, 6, paint);
      canvas.drawLine(12, 18, 12, 22, paint);
      canvas.drawLine(2, 12, 6, 12, paint);
      canvas.drawLine(18, 12, 22, 12, paint);
      paint.setStyle(Paint.Style.FILL);
      canvas.drawCircle(12, 12, 2.5f, paint);
    } else {
      Path cloud = new Path();
      cloud.moveTo(6, 18);
      cloud.cubicTo(0, 18, 1, 9, 7, 10);
      cloud.cubicTo(8, 2, 18, 3, 19, 10);
      cloud.cubicTo(25, 10, 24, 18, 19, 18);
      cloud.lineTo(6, 18);
      canvas.drawPath(cloud, paint);
      if (kind == OFFLINE) {
        paint.setColor(AppPalette.SURFACE);
        paint.setStrokeWidth(4);
        canvas.drawLine(3, 3, 21, 21, paint);
        paint.setColor(AppPalette.ACCENT);
        paint.setStrokeWidth(1.8f);
        canvas.drawLine(3, 3, 21, 21, paint);
      } else {
        canvas.drawLine(12, 16, 12, 10, paint);
        canvas.drawLine(9.5f, 12.5f, 12, 10, paint);
        canvas.drawLine(14.5f, 12.5f, 12, 10, paint);
      }
    }
    canvas.restoreToCount(save);
  }

  @Override
  public void setAlpha(int alpha) {
    paint.setAlpha(alpha);
  }

  @Override
  public void setColorFilter(ColorFilter filter) {
    paint.setColorFilter(filter);
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
