package app.cairn.trek;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Grants Android's installer read access to exactly one verified APK, never other app files. */
public final class UpdateFileProvider extends ContentProvider {
  static Uri uri(Context context) {
    return Uri.parse("content://" + context.getPackageName() + ".updates/update.apk");
  }

  private File file(Uri value) throws FileNotFoundException {
    if (!uri(getContext()).equals(value) || !UpdateFiles.ready(getContext()))
      throw new FileNotFoundException("No downloaded update at this URI");
    return UpdateFiles.apk(getContext());
  }

  @Override public boolean onCreate() { return true; }

  @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }

  @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("Update APK is read-only");
    return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    try {
      File apk = file(uri);
      String[] columns = projection == null
          ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
      MatrixCursor cursor = new MatrixCursor(columns, 1);
      Object[] row = new Object[columns.length];
      for (int i = 0; i < columns.length; i++) {
        if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "Cairn-update.apk";
        else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = apk.length();
      }
      cursor.addRow(row);
      return cursor;
    } catch (FileNotFoundException error) { throw new IllegalArgumentException(error); }
  }

  @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
  @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
  @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
    throw new UnsupportedOperationException();
  }
}
