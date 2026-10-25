package app.cairn.trek;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small platform-only icons; no bitmap assets or icon-library dependency. */
final class MapIcon extends Drawable {
  static final int LOCATE = 0,
      ONLINE = 1,
      OFFLINE = 2,
      MAPS = 3,
      SEARCH = 4,
      PATH = 5,
      UPDATE = 6,
      CLOSE = 7,
      INFO = 8,
      SAVE_MAP = 9,
      EXPORT = 10,
      HIDE = 11,
      CENTER = 12,
      EDIT = 13,
      TUNE = 14,
      UNDO = 15,
      CORRIDOR_READY = 16,
      NEW_ROUTE = 17;
  private final int kind;
  private int foreground = AppPalette.ACCENT, background = AppPalette.SURFACE;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  MapIcon(int kind) {
    this.kind = kind;
  }

  void colors(int foreground, int background) {
    this.foreground = foreground;
    this.background = background;
    invalidateSelf();
  }

  @Override
  public int getIntrinsicWidth() {
    return 24;
  }

  @Override
  public int getIntrinsicHeight() {
    return 24;
  }

  @Override
  public void draw(Canvas canvas) {
    int save = canvas.save();
    Rect b = getBounds();
    canvas.translate(b.left, b.top);
    canvas.scale(b.width() / 24f, b.height() / 24f);
    paint.setColor(foreground);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.8f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    if (kind == CORRIDOR_READY) {
      canvas.drawRoundRect(3, 3, 21, 21, 3, 3, paint);
      canvas.drawLine(7, 12, 11, 16, paint);
      canvas.drawLine(11, 16, 18, 8, paint);
    } else if (kind == UNDO) {
      canvas.drawArc(5, 7, 21, 21, 210, 250, false, paint);
      canvas.drawLine(5, 6, 5, 12, paint);
      canvas.drawLine(5, 12, 11, 12, paint);
    } else if (kind == TUNE) {
      for (int i = 0; i < 3; i++) {
        float y = 6 + i * 6, x = i == 1 ? 15 : 9;
        canvas.drawLine(3, y, x - 3, y, paint);
        canvas.drawLine(x + 3, y, 21, y, paint);
        canvas.drawCircle(x, y, 3, paint);
      }
    } else if (kind == CENTER) {
      canvas.drawLines(
          new float[] {
            3, 9, 3, 3, 3, 3, 9, 3, 15, 3, 21, 3, 21, 3, 21, 9, 21, 15, 21, 21, 21, 21, 15, 21, 9,
            21, 3, 21, 3, 21, 3, 15
          },
          paint);
      canvas.drawCircle(12, 12, 3, paint);
    } else if (kind == NEW_ROUTE) {
      canvas.drawCircle(5, 19, 2.5f, paint);
      Path route = new Path();
      route.moveTo(7.5f, 19);
      route.lineTo(15, 19);
      route.cubicTo(20, 19, 20, 12, 15, 12);
      route.lineTo(8, 12);
      route.cubicTo(3, 12, 3, 5, 8, 5);
      route.lineTo(10, 5);
      canvas.drawPath(route, paint);
      canvas.drawLine(17, 2, 17, 10, paint);
      canvas.drawLine(13, 6, 21, 6, paint);
    } else if (kind == EDIT) {
      Path pencil = new Path();
      pencil.moveTo(4, 16);
      pencil.lineTo(16, 4);
      pencil.lineTo(20, 8);
      pencil.lineTo(8, 20);
      pencil.lineTo(3, 21);
      pencil.close();
      canvas.drawPath(pencil, paint);
      canvas.drawLine(13, 7, 17, 11, paint);
    } else if (kind == SAVE_MAP) {
      Path map = new Path();
      map.moveTo(3, 19);
      map.lineTo(3, 5);
      map.lineTo(9, 3);
      map.lineTo(15, 5);
      map.lineTo(21, 3);
      map.lineTo(21, 10);
      map.moveTo(3, 19);
      map.lineTo(9, 17);
      map.lineTo(12, 18);
      map.moveTo(9, 3);
      map.lineTo(9, 17);
      canvas.drawPath(map, paint);
      canvas.drawLine(18, 12, 18, 21, paint);
      canvas.drawLine(14, 17, 18, 21, paint);
      canvas.drawLine(22, 17, 18, 21, paint);
    } else if (kind == EXPORT) {
      Path box = new Path();
      box.moveTo(10, 5);
      box.lineTo(4, 5);
      box.lineTo(4, 21);
      box.lineTo(20, 21);
      box.lineTo(20, 14);
      canvas.drawPath(box, paint);
      canvas.drawLine(11, 13, 21, 3, paint);
      canvas.drawLine(14, 3, 21, 3, paint);
      canvas.drawLine(21, 3, 21, 10, paint);
    } else if (kind == HIDE) {
      Path eye = new Path();
      eye.moveTo(2, 12);
      eye.cubicTo(7, 3, 17, 3, 22, 12);
      eye.cubicTo(17, 21, 7, 21, 2, 12);
      canvas.drawPath(eye, paint);
      canvas.drawCircle(12, 12, 3, paint);
      paint.setColor(background);
      paint.setStrokeWidth(4);
      canvas.drawLine(3, 3, 21, 21, paint);
      paint.setColor(foreground);
      paint.setStrokeWidth(1.8f);
      canvas.drawLine(3, 3, 21, 21, paint);
    } else if (kind == INFO) {
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(background);
      canvas.drawCircle(12, 12, 10, paint);
      paint.setColor(foreground);
      paint.setStyle(Paint.Style.STROKE);
      canvas.drawCircle(12, 12, 9, paint);
      canvas.drawPoint(12, 7, paint);
      canvas.drawLine(12, 11, 12, 17, paint);
    } else if (kind == CLOSE) {
      canvas.drawLine(6, 6, 18, 18, paint);
      canvas.drawLine(18, 6, 6, 18, paint);
    } else if (kind == UPDATE) {
      canvas.drawLine(12, 3, 12, 15, paint);
      canvas.drawLine(7, 10, 12, 15, paint);
      canvas.drawLine(17, 10, 12, 15, paint);
      Path tray = new Path();
      tray.moveTo(4, 16);
      tray.lineTo(4, 21);
      tray.lineTo(20, 21);
      tray.lineTo(20, 16);
      canvas.drawPath(tray, paint);
    } else if (kind == PATH) {
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
        paint.setColor(background);
        paint.setStrokeWidth(4);
        canvas.drawLine(3, 3, 21, 21, paint);
        paint.setColor(foreground);
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
