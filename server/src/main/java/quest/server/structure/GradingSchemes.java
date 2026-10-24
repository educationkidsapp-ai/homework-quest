package quest.server.structure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import quest.server.grading.Bands;

/**
 * Feature 36's one answer to "which band is this score in, in this academy" — the question the gradebook, its CSV and
 * Excel exports, the exam results and the child page all ask.
 *
 * <p><strong>Existing data keeps working.</strong> An academy without a scheme of its own reads {@link #DEFAULT},
 * which is <em>exactly</em> today's four bands in {@link Bands}: emerging, developing, secure and exceeding at 0, 40,
 * 60 and 85, with the band key equal to the band's English name. So a school that never opens this screen sees the
 * very strings it sees now, in the API and in the exports, and `SchoolStructureApiTest` pins the default against
 * {@link Bands#band} at every boundary.
 *
 * <p><strong>The score is never changed</strong> — only the band it is shown in. The legacy `band` key stays beside
 * the new `bandLabel` on every DTO that carried one (D: additive), and {@link quest.api.progress.ProgressBands},
 * which is what a parent and a child see in the app, is untouched.
 */
@Component
public class GradingSchemes {

    /** One band: its key, its lower threshold over 0–100 and its name in both languages. */
    public record Band(String key, int lower, String nameEn, String nameAr) {}

    /** An academy's bands, lowest first. Immutable, and cheap enough to resolve once per read and ask many times. */
    public record Scheme(String academyKey, String kind, boolean custom, List<Band> bands) {

        /** The band a score falls in: the highest one whose threshold it reaches, and the lowest one otherwise. */
        public Band bandFor(double score) {
            Band found = bands.getFirst();
            for (Band b : bands) if (score >= b.lower()) found = b;
            return found;
        }

        /** The band key of a score, or null for no score — what a DTO's `bandLabel` carries. */
        public String keyFor(Double score) { return score == null ? null : bandFor(score).key(); }

        /** The band's English name, which is what an export column prints. */
        public String nameFor(Double score) { return score == null ? null : bandFor(score).nameEn(); }

        /** The English name of a band key already resolved, for an export reading a DTO rather than a score. */
        public String nameOf(String bandKey) {
            if (bandKey == null) return null;
            for (Band b : bands) if (b.key().equals(bandKey)) return b.nameEn();
            return bandKey;
        }

        public StructureDto.GradingScheme view() {
            return new StructureDto.GradingScheme(academyKey, kind, custom,
                    bands.stream().map(b -> new StructureDto.GradingBand(b.key(), b.lower(), b.nameEn(), b.nameAr())).toList());
        }
    }

    /** Today's four bands, word for word: the scheme every academy reads until the Admin chooses another. */
    public static final List<Band> DEFAULT_BANDS = List.of(
            new Band(Bands.EMERGING, 0, Bands.EMERGING, "مبتدئ"),
            new Band(Bands.DEVELOPING, Bands.DEVELOPING_AT, Bands.DEVELOPING, "نامٍ"),
            new Band(Bands.SECURE, Bands.SECURE_AT, Bands.SECURE, "متمكّن"),
            new Band(Bands.EXCEEDING, Bands.EXCEEDING_AT, Bands.EXCEEDING, "متفوّق"));

    public static final Scheme DEFAULT = new Scheme(null, StructureDto.LETTER, false, DEFAULT_BANDS);

    private final GradingSchemeRepository schemes; private final GradingBandRepository bands;

    public GradingSchemes(GradingSchemeRepository schemes, GradingBandRepository bands) {
        this.schemes = schemes; this.bands = bands;
    }

    /**
     * The scheme of one academy of one school, or {@link #DEFAULT}. The school is named rather than taken from the
     * tenant scope, because the gradebook is also read by jobs and by a parent's request, neither of which has one.
     */
    public Scheme of(String schoolId, String academyKey) {
        if (schoolId == null || academyKey == null) return defaultFor(academyKey);
        var row = schemes.findBySchoolIdAndAcademyKey(schoolId, normalise(academyKey)).orElse(null);
        if (row == null) return defaultFor(academyKey);
        var loaded = bands.findBySchoolIdAndSchemeIdOrderByLowerBoundAsc(schoolId, row.getId()).stream()
                .map(b -> new Band(b.getBandKey(), b.getLowerBound(), b.getNameEn(), b.getNameAr())).toList();
        if (loaded.isEmpty()) return defaultFor(academyKey);
        return new Scheme(normalise(academyKey), row.getKind(), true, loaded);
    }

    /** {@link #DEFAULT} named after the academy that is reading it, so a client can label the scheme it got. */
    public Scheme defaultFor(String academyKey) {
        return new Scheme(normalise(academyKey), StructureDto.LETTER, false, DEFAULT_BANDS);
    }

    /** `GradingSchemes.bandFor(schoolId, academyKey, score)` — the one call the spec names. */
    public Band bandFor(String schoolId, String academyKey, double score) { return of(schoolId, academyKey).bandFor(score); }

    /**
     * The bands an Admin sent, validated into the shape {@link Scheme} relies on: at least two bands, thresholds
     * inside 0–100, no two the same, a band at 0 so every score has one, and a key and two names on each.
     */
    public static List<Band> validate(List<StructureDto.GradingBand> sent) {
        if (sent == null || sent.size() < 2) throw quest.server.config.ApiException.badRequest("a grading scheme needs at least two bands");
        var out = new ArrayList<Band>(sent.size());
        var seenKeys = new ArrayList<String>(); var seenLowers = new ArrayList<Integer>();
        for (var b : sent) {
            if (b.band() == null || b.band().isBlank()) throw quest.server.config.ApiException.badRequest("every band needs a key");
            if (b.nameEn() == null || b.nameEn().isBlank() || b.nameAr() == null || b.nameAr().isBlank())
                throw quest.server.config.ApiException.badRequest("every band needs a name in English and in Arabic");
            if (b.lower() < 0 || b.lower() > 100) throw quest.server.config.ApiException.badRequest("a band's lower threshold is a score between 0 and 100");
            if (seenKeys.contains(b.band())) throw quest.server.config.ApiException.badRequest("two bands cannot share the key " + b.band());
            if (seenLowers.contains(b.lower())) throw quest.server.config.ApiException.badRequest("two bands cannot start at " + b.lower());
            seenKeys.add(b.band()); seenLowers.add(b.lower());
            out.add(new Band(b.band().trim(), b.lower(), b.nameEn().trim(), b.nameAr().trim()));
        }
        out.sort(java.util.Comparator.comparingInt(Band::lower));
        if (out.getFirst().lower() != 0) throw quest.server.config.ApiException.badRequest("the lowest band must start at 0, so every score has a band");
        return List.copyOf(out);
    }

    static String normalise(String key) { return key == null ? null : key.trim().toLowerCase(Locale.ROOT); }
}
