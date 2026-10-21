package app.cairn.trek;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Small versioned release contract; APK contents are independently verified before install. */
final class UpdateManifest {
  static final long MAX_APK_BYTES = 150L * 1024 * 1024;
  final String applicationId, versionName, apkName, sha256;
  final long versionCode, size;
  final int minSdk;

  UpdateManifest(byte[] bytes) throws IOException {
    try {
      JSONObject value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
      if (value.getInt("schema") != 1) throw new IOException("Unsupported update format");
      applicationId = value.getString("applicationId");
      versionName = value.getString("versionName");
      apkName = value.getString("apkName");
      sha256 = value.getString("sha256");
      versionCode = value.getLong("versionCode");
      size = value.getLong("size");
      minSdk = value.getInt("minSdk");
      if (!"app.cairn.trek".equals(applicationId)
          || !versionName.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
          || !apkName.equals("Cairn-" + versionName + ".apk")
          || !sha256.matches("[a-f0-9]{64}")
          || versionCode < 1 || versionCode > Integer.MAX_VALUE
          || size < 1 || size > MAX_APK_BYTES || minSdk < 26)
        throw new IOException("Invalid Cairn update details");
    } catch (JSONException error) {
      throw new IOException("Incomplete update details", error);
    }
  }

  byte[] bytes() throws IOException {
    try {
      return new JSONObject()
          .put("schema", 1).put("applicationId", applicationId).put("versionName", versionName)
          .put("versionCode", versionCode).put("minSdk", minSdk).put("apkName", apkName)
          .put("size", size).put("sha256", sha256)
          .toString().getBytes(StandardCharsets.UTF_8);
    } catch (JSONException error) {
      throw new IOException(error);
    }
  }
}
