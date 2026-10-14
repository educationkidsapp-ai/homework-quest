package quest.server.staffattendance;

/**
 * Feature 15: how far a fix is from the school pin, in metres. The haversine formula on a sphere of the IUGG mean
 * radius — the error against the ellipsoid is under 0.5 % anywhere, which is centimetres at the few hundred metres a
 * geofence spans and never decides a check-in at the edge of a 50–500 m radius.
 *
 * <p>Pure and static on purpose: the distance is the one piece of the check with arithmetic worth testing on its own
 * ({@code GeoTest}), and it is the last place the coordinates exist. Nothing here keeps, logs or returns them.
 */
final class Geo {
    /** The IUGG mean Earth radius, in metres. */
    static final double EARTH_RADIUS_M = 6_371_008.8;

    private Geo() {}

    /** Great-circle distance between two WGS-84 points, in metres. Never negative, never NaN for finite inputs. */
    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double sinLat = Math.sin(dLat / 2), sinLon = Math.sin(dLon / 2);
        double a = sinLat * sinLat + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinLon * sinLon;
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
