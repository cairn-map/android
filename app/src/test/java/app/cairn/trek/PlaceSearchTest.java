package app.cairn.trek;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.location.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = PlaceSearchTest.SearchGeocoder.class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
public class PlaceSearchTest {
  @Implements(Geocoder.class)
  public static class SearchGeocoder {
    static boolean present;
    static List<Address> answer;
    static IOException error;
    static String query;
    static CountDownLatch entered, release;

    @Implementation
    protected static boolean isPresent() {
      return present;
    }

    @Implementation
    protected List<Address> getFromLocationName(String name, int maxResults) throws IOException {
      query = name;
      if (entered != null) {
        entered.countDown();
        try {
          release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
          // Simulate a backend that delivers a late response after cancellation.
        }
      }
      if (error != null) throw error;
      return answer;
    }
  }

  private PlaceSearch search;
  private int found, failed;
  private List<Address> results;
  private String reason;
  private final PlaceSearch.Callback callback =
      new PlaceSearch.Callback() {
        public void results(List<Address> places) {
          found++;
          results = places;
        }

        public void unavailable(String message) {
          failed++;
          reason = message;
        }
      };

  @Before
  public void setup() {
    SearchGeocoder.present = true;
    SearchGeocoder.answer = Collections.emptyList();
    SearchGeocoder.error = null;
    SearchGeocoder.query = null;
    SearchGeocoder.entered = SearchGeocoder.release = null;
    search =
        new PlaceSearch(
            RuntimeEnvironment.getApplication(), (query, center) -> Collections.emptyList());
  }

  @After
  public void close() {
    if (SearchGeocoder.release != null) SearchGeocoder.release.countDown();
    search.close();
  }

  private void drain() throws Exception {
    drain(search);
  }

  private void drain(PlaceSearch target) throws Exception {
    Field field = PlaceSearch.class.getDeclaredField("worker");
    field.setAccessible(true);
    ((ExecutorService) field.get(target)).submit(() -> {}).get(5, TimeUnit.SECONDS);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  private Address place(String name, double lat, double lon) {
    Address result = new Address(Locale.FRANCE);
    result.setFeatureName(name);
    result.setLatitude(lat);
    result.setLongitude(lon);
    return result;
  }

  @Test
  public void suggestionsDebounceTypingAndOnlyLookUpTheLatestText() throws Exception {
    search.suggest("Pa", callback);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
    search.suggest("Paris", callback);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(349));
    assertNull(SearchGeocoder.query);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
    drain();
    assertEquals("Paris", SearchGeocoder.query);
    assertEquals(1, found);
  }

  @Test
  public void clearingOrClosingSearchCancelsPendingSuggestions() throws Exception {
    search.suggest("Paris", callback);
    search.suggest("P", callback);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    drain();
    assertNull(SearchGeocoder.query);
    search.suggest("Paris", callback);
    search.cancel();
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    drain();
    assertNull(SearchGeocoder.query);
  }

  @Test
  public void typingReplacesAnOlderResponseAndClearingRemovesSuggestions() throws Exception {
    SearchGeocoder.answer = Collections.singletonList(place("Paris, France", 48.8566, 2.3522));
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      Field searchField = MainActivity.class.getDeclaredField("placeSearch");
      searchField.setAccessible(true);
      ((PlaceSearch) searchField.get(activity)).close();
      searchField.set(
          activity, new PlaceSearch(activity, (queryText, center) -> Collections.emptyList()));
      find(activity.getWindow().getDecorView(), "Search places").performClick();
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      EditText query = (EditText) find(dialog.getWindow().getDecorView(), "Place name or address");
      query.setText("Paris");
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
      drain((PlaceSearch) field(activity, "placeSearch"));
      assertNotNull(find(dialog.getWindow().getDecorView(), "Paris, France"));
      query.setText("Lyon");
      assertNull(find(dialog.getWindow().getDecorView(), "Paris, France"));
      query.setText("");
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
      drain((PlaceSearch) field(activity, "placeSearch"));
      assertEquals("Paris", SearchGeocoder.query);
      assertNull(find(dialog.getWindow().getDecorView(), "Paris, France"));
      dialog.dismiss();
    }
  }

  @Test
  public void editingDiscardsAnAlreadyQueuedResponseFromThePreviousQuery() throws Exception {
    SearchGeocoder.answer = Collections.singletonList(place("Paris", 48, 2));
    search.search("Paris", callback);
    // Wait for the worker without dispatching its queued main-thread callback.
    Field field = PlaceSearch.class.getDeclaredField("worker");
    field.setAccessible(true);
    ((ExecutorService) field.get(search)).submit(() -> {}).get(5, TimeUnit.SECONDS);
    search.suggest("Lyon", callback);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertEquals(0, found);
    search.cancel();
  }

  @Test
  public void returnsNamedMatchesAndRejectsUnusableCoordinates() throws Exception {
    Address chamonix = place("Chamonix", 45.9237, 6.8694);
    SearchGeocoder.answer =
        Arrays.asList(
            chamonix,
            new Address(Locale.FRANCE),
            place("Invalid", Double.NaN, 0),
            place("Invalid", 100, 0));
    search.search("Chamonix", callback);
    drain();
    assertEquals("Chamonix", SearchGeocoder.query);
    assertEquals(1, found);
    assertEquals(Collections.singletonList(chamonix), results);
    assertEquals("Chamonix", PlaceSearch.label(results.get(0)));
  }

  @Test
  public void emptyResultsAndNetworkFailureAreReportedAndCanBeRetried() throws Exception {
    search.search("Unknown place", callback);
    drain();
    assertTrue(results.isEmpty());
    SearchGeocoder.error = new IOException("No network");
    search.search("Chamonix", callback);
    drain();
    assertEquals(1, failed);
    assertTrue(reason.contains("internet connection"));
    SearchGeocoder.error = null;
    search.search("Chamonix", callback);
    drain();
    assertEquals(2, found);
  }

  @Test
  public void partialMountainNamesWorkWithoutADeviceGeocoder() throws Exception {
    SearchGeocoder.present = false;
    search.close();
    Point focus = new Point(45.1, 6.5);
    search =
        new PlaceSearch(
            RuntimeEnvironment.getApplication(),
            (query, center) -> {
              assertEquals("Thabor", query);
              assertSame(focus, center);
              return PhotonClient.parse(
                  """
{"features":[{"properties":{"name":"Mont Thabor","osm_value":"peak","country":"France"},
"geometry":{"type":"Point","coordinates":[6.563,45.115]}}]}
""");
            });
    search.near(focus);
    search.search("Thabor", callback);
    drain();
    assertEquals(1, found);
    assertEquals(0, failed);
    assertTrue(PlaceSearch.label(results.get(0)).startsWith("Mont Thabor"));
    assertEquals(45.115, results.get(0).getLatitude(), 1e-6);
    assertNull(SearchGeocoder.query);
  }

  @Test
  public void primaryNetworkFailureFallsBackToTheDeviceGeocoder() throws Exception {
    search.close();
    search =
        new PlaceSearch(
            RuntimeEnvironment.getApplication(),
            (query, center) -> {
              throw new IOException("Offline");
            });
    SearchGeocoder.answer = Collections.singletonList(place("Mont Thabor", 45.115, 6.563));
    search.search("Thabor", callback);
    drain();
    assertEquals(1, found);
    assertEquals(0, failed);
    assertEquals("Mont Thabor", PlaceSearch.label(results.get(0)));
  }

  @Test
  public void cancellingIgnoresAnAlreadyQueuedResponse() throws Exception {
    search.search("Chamonix", callback);
    search.cancel();
    drain();
    assertEquals(0, found);
    assertEquals(0, failed);
  }

  @Test
  public void timeoutIgnoresLateResults() throws Exception {
    SearchGeocoder.entered = new CountDownLatch(1);
    SearchGeocoder.release = new CountDownLatch(1);
    search.search("Chamonix", callback);
    assertTrue(SearchGeocoder.entered.await(5, TimeUnit.SECONDS));
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
    SearchGeocoder.release.countDown();
    drain();
    assertEquals(1, failed);
    assertTrue(reason.contains("timed out"));
    assertEquals(0, found);
  }

  @Test
  public void resultLabelsDisambiguatePlacesAndFallBackToCoordinates() {
    Address address = place("Chamonix", 45.9237, 6.8694);
    address.setLocality("Chamonix");
    address.setCountryName("France");
    assertEquals("Chamonix, France", PlaceSearch.label(address));
    address.setAddressLine(0, "Chamonix-Mont-Blanc, France");
    assertEquals("Chamonix-Mont-Blanc, France", PlaceSearch.label(address));
    assertFalse(PlaceSearch.label(place(null, 45, 6)).isEmpty());
  }

  private View find(View view, String text) {
    if (text.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())
        || view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View result = find(group.getChildAt(i), text);
        if (result != null) return result;
      }
    }
    return null;
  }

  private Object field(MainActivity activity, String name) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(activity);
  }

  @Test
  public void searchButtonShowsMatchesAndSelectionMovesTheMapWithoutEnablingTiles()
      throws Exception {
    SearchGeocoder.answer =
        Arrays.asList(
            place("Paris, France", 48.8566, 2.3522), place("Paris, Texas", 33.6609, -95.5555));
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      Field searchField = MainActivity.class.getDeclaredField("placeSearch");
      searchField.setAccessible(true);
      ((PlaceSearch) searchField.get(activity)).close();
      searchField.set(
          activity, new PlaceSearch(activity, (queryText, center) -> Collections.emptyList()));
      View searchButton = find(activity.getWindow().getDecorView(), "Search places");
      assertNotNull(searchButton);
      searchButton.performClick();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      EditText query = (EditText) find(dialog.getWindow().getDecorView(), "Place name or address");
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      assertNotNull(query.getError());
      assertNull(SearchGeocoder.query);
      query.setText("Paris");
      query.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
      drain((PlaceSearch) field(activity, "placeSearch"));
      assertNotNull(find(dialog.getWindow().getDecorView(), "Paris, Texas"));
      find(dialog.getWindow().getDecorView(), "Paris, France").performClick();
      TrekMapView map = (TrekMapView) field(activity, "map");
      assertEquals(48.8566, map.center().lat, 1e-6);
      assertEquals(2.3522, map.center().lon, 1e-6);
      assertFalse(((IgnMap) map.tileSource()).online());
      assertFalse(dialog.isShowing());
    }
  }

  @Test
  public void leavingTheActivityDismissesSearchAndDiscardsLateResults() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      Field searchField = MainActivity.class.getDeclaredField("placeSearch");
      searchField.setAccessible(true);
      ((PlaceSearch) searchField.get(activity)).close();
      searchField.set(
          activity, new PlaceSearch(activity, (queryText, center) -> Collections.emptyList()));
      find(activity.getWindow().getDecorView(), "Search places").performClick();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      ((EditText) find(dialog.getWindow().getDecorView(), "Place name or address"))
          .setText("Paris");
      SearchGeocoder.entered = new CountDownLatch(1);
      SearchGeocoder.release = new CountDownLatch(1);
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      assertTrue(SearchGeocoder.entered.await(5, TimeUnit.SECONDS));
      double[] before = ((TrekMapView) field(activity, "map")).camera();
      controller.pause();
      SearchGeocoder.release.countDown();
      drain((PlaceSearch) field(activity, "placeSearch"));
      assertFalse(dialog.isShowing());
      assertArrayEquals(before, ((TrekMapView) field(activity, "map")).camera(), 1e-10);
    }
  }

  @Test
  public void recordingBlocksOnlineSearchWithoutSubmittingAQuery() {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      TrackingService.running = true;
      find(controller.get().getWindow().getDecorView(), "Search places").performClick();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      assertNotNull(
          find(
              dialog.getWindow().getDecorView(),
              "Finish recording before searching online. Offline maps and saved routes remain"
                  + " available."));
      assertNull(SearchGeocoder.query);
      dialog.dismiss();
    } finally {
      TrackingService.running = false;
    }
  }
}
