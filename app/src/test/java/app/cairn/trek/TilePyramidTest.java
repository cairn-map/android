package app.cairn.trek;

import static org.junit.Assert.*;

import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.*;
import java.io.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TilePyramidTest {
  private Context context;
  private PlanIgnStore store;

  @Before public void open() {
    context = RuntimeEnvironment.getApplication();
    context.deleteDatabase("plan-ign.mbtiles");
    TrackingService.running = false;
    store = new PlanIgnStore(context);
  }

  @After public void close() { store.close(); }

  private byte[] png(int color) {
    Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(color);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
    bitmap.recycle();
    return bytes.toByteArray();
  }

  private void save(int z, int x, int y, int color) {
    store.save(new TilePlanner.Tile(z, x, y), new IgnClient.Result(png(color), null, null));
  }

  @Test public void firstOfflineReadUsesSavedChildrenAndKeepsOverviewInGaps() {
    save(10, 512, 512, Color.RED);
    save(11, 1024, 1024, Color.BLUE);
    save(12, 2048, 2048, Color.GREEN);
    try (IgnMap map = new IgnMap(context)) {
      Bitmap tile = map.tile(10, 512, 512);
      assertNotNull(tile);
      assertEquals(Color.GREEN, tile.getPixel(32, 32));
      assertEquals(Color.BLUE, tile.getPixel(200, 200));
      assertEquals(Color.RED, tile.getPixel(400, 400));
      assertFalse(map.online());
    }
  }

  @Test public void savedDetailIsVisibleEvenWithoutAnOverviewTile() {
    save(12, 2049, 2048, Color.BLUE);
    try (IgnMap map = new IgnMap(context)) {
      Bitmap tile = map.tile(10, 512, 512);
      assertNotNull(tile);
      assertEquals(Color.BLUE, tile.getPixel(160, 32));
      assertEquals(Color.TRANSPARENT, tile.getPixel(400, 400));
    }
  }

  @Test public void importedTmsAndXyzMapsUseTheSameGeographicalDetail() {
    for (boolean xyz : new boolean[] {true, false}) {
      File file = new File(context.getCacheDir(), "pyramid-" + xyz + ".mbtiles");
      file.delete();
      try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
        db.execSQL("CREATE TABLE metadata(name TEXT,value TEXT)");
        db.execSQL("INSERT INTO metadata VALUES('scheme',?)", new Object[] {xyz ? "xyz" : "tms"});
        db.execSQL("CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB)");
        db.execSQL("INSERT INTO tiles VALUES(10,512,?,?)", new Object[] {xyz ? 512 : 511, png(Color.RED)});
        db.execSQL("INSERT INTO tiles VALUES(11,1024,?,?)", new Object[] {xyz ? 1024 : 1023, png(Color.BLUE)});
      }
      try (OfflineMap map = new OfflineMap(file)) {
        Bitmap tile = map.tile(10, 512, 512);
        assertEquals(Color.BLUE, tile.getPixel(100, 100));
        assertEquals(Color.RED, tile.getPixel(400, 400));
      }
    }
  }

  @Test public void obsoleteViewportStopsReadingSavedDetail() {
    save(11, 1024, 1024, Color.BLUE);
    Bitmap base = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
    base.eraseColor(Color.RED);
    assertSame(base, store.detail(new TilePlanner.Tile(10, 512, 512), base, () -> true));
  }
}
