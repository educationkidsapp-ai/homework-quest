package quest.server.staffattendance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Feature 15's distance, on its own. A geofence of 50–500 m is decided by metres, so the arithmetic is pinned here
 * rather than only through the API: a formula that was out by a percent would move the edge of a 150 m radius by a
 * metre and a half, which is the difference between the car park and the gate.
 *
 * <p>A meridian is exact on a sphere, so "north by N metres" is the fixture of choice: {@link #northOf} converts
 * metres to degrees of latitude with the same radius {@link Geo} uses, and the two must agree to the millimetre.
 */
class GeoTest {
    private static final double LAT = 24.7136, LON = 46.6753;                   // the QA school's pin, Riyadh
    private static final double METRES_PER_DEGREE = Geo.EARTH_RADIUS_M * Math.PI / 180;

    @Test void the_distance_to_the_same_point_is_zero() {
        assertThat(Geo.distanceMeters(LAT, LON, LAT, LON)).isZero();
    }

    @Test void a_degree_of_latitude_is_the_meridian_arc() {
        assertThat(Geo.distanceMeters(0, LON, 1, LON)).isCloseTo(METRES_PER_DEGREE, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test void moving_north_by_a_known_number_of_metres_measures_that_many() {
        for (int metres : new int[] {1, 49, 149, 150, 151, 199, 201, 500, 4_000})
            assertThat(Geo.distanceMeters(LAT, LON, northOf(LAT, metres), LON))
                    .as("%d m north", metres).isCloseTo(metres, org.assertj.core.data.Offset.offset(0.01));
    }

    /** The radius edge: 1 m inside and 1 m outside a 200 m geofence, as metres, round to 199 and 201. */
    @Test void the_edge_of_a_radius_rounds_to_either_side_of_it() {
        assertThat(Math.round(Geo.distanceMeters(LAT, LON, northOf(LAT, 199), LON))).isEqualTo(199);
        assertThat(Math.round(Geo.distanceMeters(LAT, LON, northOf(LAT, 201), LON))).isEqualTo(201);
    }

    @Test void the_distance_is_symmetric_and_shrinks_with_the_cosine_of_the_latitude() {
        double a = Geo.distanceMeters(LAT, LON, LAT + 0.01, LON + 0.02);
        assertThat(Geo.distanceMeters(LAT + 0.01, LON + 0.02, LAT, LON)).isCloseTo(a, org.assertj.core.data.Offset.offset(1e-6));

        // A degree of longitude at 24.7° N is cos(24.7°) of one at the equator.
        double atEquator = Geo.distanceMeters(0, 0, 0, 1);
        double here = Geo.distanceMeters(LAT, LON, LAT, LON + 1);
        assertThat(here / atEquator).isCloseTo(Math.cos(Math.toRadians(LAT)), org.assertj.core.data.Offset.offset(0.0005));
    }

    /** The far field stays finite: `asin` is clamped, so antipodal points are half the great circle and not NaN. */
    @Test void antipodal_points_are_half_the_circumference() {
        double half = Math.PI * Geo.EARTH_RADIUS_M;
        assertThat(Geo.distanceMeters(90, 0, -90, 0)).isCloseTo(half, org.assertj.core.data.Offset.offset(0.001));
        assertThat(Geo.distanceMeters(0, 0, 0, 180)).isCloseTo(half, org.assertj.core.data.Offset.offset(1.0)).isFinite();
    }

    /** `metres` north of `latitude`, on the sphere {@link Geo} measures with. */
    static double northOf(double latitude, double metres) { return latitude + metres / METRES_PER_DEGREE; }
}
