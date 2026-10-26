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
    static int calls;
    static CountDownLatch entered, release;

    @Implementation
    protected static boolean isPresent() {
      return present;
    }

    @Implementation
    protected List<Address> getFromLocationName(String name, int maxResults) throws IOException {
      calls++;
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
    SearchGeocoder.calls = 0;
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

  private Address destination(String name, String category, String kind, double lat, double lon) {
    Address result = place(name, lat, lon);
    android.os.Bundle metadata = new android.os.Bundle();
    metadata.putString("category", category);
    metadata.putString("kind", kind);
    result.setExtras(metadata);
    return result;
  }

  @Test
  public void thaborPromotesMountainAndRefugesAboveNearbyExactUrbanNamesakes() throws Exception {
    List<Address> places =
        PhotonClient.parse(
            """
            {"features":[
              {"properties":{"name":"Thabor","osm_key":"place","osm_value":"city"},
               "geometry":{"type":"Point","coordinates":[2.35,48.85]}},
              {"properties":{"name":"Thabor","osm_key":"shop","osm_value":"bakery"},
               "geometry":{"type":"Point","coordinates":[2.36,48.85]}},
              {"properties":{"name":"Mont Thabor","osm_key":"natural","osm_value":"peak"},
               "geometry":{"type":"Point","coordinates":[6.563,45.115]}},
              {"properties":{"name":"Refuge du Thabor","osm_key":"tourism","osm_value":"alpine_hut"},
               "geometry":{"type":"Point","coordinates":[6.59,45.13]}},
              {"properties":{"name":"Cabane du Thabor","osm_key":"tourism","osm_value":"wilderness_hut"},
               "geometry":{"type":"Point","coordinates":[6.6,45.14]}}
            ]}
            """);
    search.close();
    search = new PlaceSearch(RuntimeEnvironment.getApplication(), (query, center) -> places);
    search.near(new Point(48.85, 2.35));
    search.search("Thabor", callback);
    drain();
    assertEquals(5, results.size());
    assertSame(places.get(2), results.get(0));
    assertEquals(new HashSet<>(places.subList(2, 5)), new HashSet<>(results.subList(0, 3)));
    assertEquals(places.subList(0, 2), results.subList(3, 5));
    assertNull(SearchGeocoder.query);
  }

  @Test
  public void hikingFeaturesReceivePreferenceWithoutFilteringOtherPlaces() {
    Address town = destination("Thabor", "place", "town", 48.85, 2.35);
    String[] kinds = {
      "peak", "saddle", "ridge", "valley", "glacier", "waterfall", "water", "wood",
      "alpine_hut", "wilderness_hut", "shelter", "trailhead", "path", "footway", "hiking",
      "viewpoint", "camp_site", "picnic_site", "national_park", "protected_area", "nature_reserve"
    };
    for (String kind : kinds) {
      Address hike = destination("Destination Thabor", "", kind, 45.1, 6.5);
      assertEquals(
          kind,
          Arrays.asList(hike, town),
          PlaceSearch.rank("Thabor", new Point(48.85, 2.35), Arrays.asList(town, hike)));
    }
    Address natural = destination("Thabor landscape", "natural", "heath", 45.1, 6.5);
    assertSame(natural, PlaceSearch.rank("Thabor", null, Arrays.asList(town, natural)).get(0));
  }

  @Test
  public void naturePreferenceRespectsCountryAndAddressQualifiersAndNameRelevance() {
    Address mountain = destination("Mont Thabor", "natural", "peak", 45.115, 6.563);
    mountain.setCountryName("France");
    Address abroad = destination("Thabor", "place", "town", 46.8, -71.2);
    abroad.setCountryName("Canada");
    assertSame(
        abroad,
        PlaceSearch.rank("Thabor Canada", null, Arrays.asList(mountain, abroad)).get(0));
    Address street = place("12 Rue du Thabor", 48.85, 2.35);
    assertSame(
        street,
        PlaceSearch.rank("12 rue du thabor", null, Arrays.asList(mountain, street)).get(0));
    Address otherMountain = destination("Mont Blanc", "natural", "peak", 45.8, 6.9);
    otherMountain.setLocality("Thabor");
    assertSame(
        abroad,
        PlaceSearch.rank("Thabor", null, Arrays.asList(otherMountain, abroad)).get(0));
  }

  @Test
  public void allProviderMatchesRemainAvailableBelowPreferredNatureResults() {
    List<Address> places = new ArrayList<>();
    for (int i = 0; i < 39; i++) places.add(place("Thabor business " + i, 48 + i * .01, 2));
    Address mountain = destination("Mont Thabor", "natural", "peak", 45.115, 6.563);
    places.add(mountain);
    List<Address> ranked = PlaceSearch.rank("Thabor", null, places);
    assertSame(mountain, ranked.get(0));
    assertEquals(40, ranked.size());
    assertEquals(new HashSet<>(places), new HashSet<>(ranked));
  }

  @Test
  public void equallyRelevantHikingDestinationsKeepTheSoftDistanceBias() {
    Address far = destination("Refuge du Thabor", "tourism", "alpine_hut", 45.13, 6.59);
    Address near = destination("Cabane du Thabor", "tourism", "wilderness_hut", 45.14, 6.6);
    assertEquals(
        Arrays.asList(near, far),
        PlaceSearch.rank("thabor", new Point(45.14, 6.6), Arrays.asList(far, near)));
  }

  @Test
  public void exactAccentedNamesBeatNearbyPartialMatchesAndQualifiersDisambiguate() {
    Address exact = place("Saint-Étienne", 45.44, 4.39);
    exact.setAddressLine(0, "Saint-Étienne, Loire, France");
    Address partial = place("Saint Étienne café", 48.85, 2.35);
    Address abroad = place("Saint Étienne", 46.8, -71.2);
    abroad.setAddressLine(0, "Saint Étienne, Québec, Canada");
    List<Address> ranked =
        PlaceSearch.rank("saint etienne", new Point(48.85, 2.35), Arrays.asList(partial, exact));
    assertSame(exact, ranked.get(0));
    ranked =
        PlaceSearch.rank(
            "saint etienne canada", new Point(45.44, 4.39), Arrays.asList(exact, abroad));
    assertSame(abroad, ranked.get(0));
  }

  @Test
  public void townBeatsANearbyBusinessAndDuplicatesAcrossLabelsAreMerged() {
    Address city = place("Paris", 48.85, 2.35);
    android.os.Bundle metadata = new android.os.Bundle();
    metadata.putString("kind", "city");
    city.setExtras(metadata);
    Address duplicate = place("Paris", 48.8501, 2.3501);
    duplicate.setAddressLine(0, "Paris, France");
    Address shop = place("Paris bakery", 48.851, 2.351);
    assertEquals(
        Arrays.asList(city, shop),
        PlaceSearch.rank("Paris", new Point(48.851, 2.351), Arrays.asList(shop, duplicate, city)));
  }

  @Test
  public void coordinatesAreImmediateWithoutCallingEitherOnlineBackend() {
    search.search("45.9237, 6.8694", callback);
    assertEquals(1, results.size());
    assertEquals(45.9237, results.get(0).getLatitude(), 1e-6);
    assertNull(SearchGeocoder.query);
    assertNull(PlaceSearch.coordinates("95, 6"));
    assertNull(PlaceSearch.coordinates("NaN, 6"));
  }

  @Test
  public void suggestionsDebounceTypingAndOnlyLookUpTheLatestText() throws Exception {
    search.suggest("Pa", callback);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
    search.suggest("Paris", callback);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(199));
    assertNull(SearchGeocoder.query);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
    drain();
    assertEquals("Paris", SearchGeocoder.query);
    assertEquals(1, found);
  }

  @Test
  public void submittingTheSameInFlightSuggestionDoesNotRestartTheLookup() throws Exception {
    SearchGeocoder.entered = new CountDownLatch(1);
    SearchGeocoder.release = new CountDownLatch(1);
    SearchGeocoder.answer = List.of(place("Paris", 48.85, 2.35));
    search.suggest("Paris", callback);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
    assertTrue(SearchGeocoder.entered.await(5, TimeUnit.SECONDS));
    search.search(" Paris ", callback);
    search.suggest("Paris", callback);
    SearchGeocoder.release.countDown();
    drain();
    assertEquals(1, found);
    assertEquals(0, failed);
    assertEquals("Paris", results.get(0).getFeatureName());
    assertEquals(1, SearchGeocoder.calls);
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
    assertEquals("Search unavailable", reason);
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
      query.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
      assertNull(query.getError());
      assertNull(SearchGeocoder.query);
      query.setText("Paris");
      query.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
      drain((PlaceSearch) field(activity, "placeSearch"));
      assertNotNull(find(dialog.getWindow().getDecorView(), "Paris, Texas"));
      find(dialog.getWindow().getDecorView(), "Paris, France").performClick();
      TrekMapView map = (TrekMapView) field(activity, "map");
      assertEquals(48.8566, map.center().lat, 1e-6);
      assertEquals(2.3522, map.center().lon, 1e-6);
      assertNotNull(map.searchMarker);
      assertEquals(48.8566, map.searchMarker.lat, 1e-6);
      assertEquals(2.3522, map.searchMarker.lon, 1e-6);
      assertFalse(((IgnMap) map.tileSource()).online());
      assertFalse(dialog.isShowing());
      controller.recreate();
      TrekMapView restored = (TrekMapView) field(controller.get(), "map");
      assertNotNull(restored.searchMarker);
      assertEquals(48.8566, restored.searchMarker.lat, 1e-6);
      assertEquals(2.3522, restored.searchMarker.lon, 1e-6);
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
      EditText query = (EditText) find(dialog.getWindow().getDecorView(), "Place name or address");
      query.setText("Paris");
      SearchGeocoder.entered = new CountDownLatch(1);
      SearchGeocoder.release = new CountDownLatch(1);
      query.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
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
  public void recordingAllowsSearchAndResultSelectionWithoutEnablingOnlineMaps() throws Exception {
    SearchGeocoder.answer = Collections.singletonList(place("Paris, France", 48.8566, 2.3522));
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      TrackingService.running = true;
      MainActivity activity = controller.get();
      Field searchField = MainActivity.class.getDeclaredField("placeSearch");
      searchField.setAccessible(true);
      ((PlaceSearch) searchField.get(activity)).close();
      searchField.set(
          activity, new PlaceSearch(activity, (queryText, center) -> Collections.emptyList()));
      Store store = (Store) field(activity, "store");
      store.prefs.edit().putInt("intervalSeconds", 137).commit();
      find(activity.getWindow().getDecorView(), "Search places").performClick();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      assertTrue(dialog.isShowing());
      EditText query = (EditText) find(dialog.getWindow().getDecorView(), "Place name or address");
      query.setText("Paris");
      query.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
      drain((PlaceSearch) field(activity, "placeSearch"));
      assertEquals("Paris", SearchGeocoder.query);
      find(dialog.getWindow().getDecorView(), "Paris, France").performClick();
      TrekMapView map = (TrekMapView) field(activity, "map");
      assertEquals(48.8566, map.center().lat, 1e-6);
      assertEquals(2.3522, map.center().lon, 1e-6);
      assertNotNull(map.searchMarker);
      assertEquals(48.8566, map.searchMarker.lat, 1e-6);
      assertEquals(2.3522, map.searchMarker.lon, 1e-6);
      assertFalse(((IgnMap) field(activity, "ignMap")).online());
      assertEquals(137000L, store.intervalMillis());
      assertTrue(TrackingService.running);
      assertFalse(dialog.isShowing());
    } finally {
      TrackingService.running = false;
    }
  }
}
