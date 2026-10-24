package quest.server.structure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ClassFixtures;
import quest.server.auth.Entities.UserEntity;
import quest.server.flags.FlagKeys;
import quest.server.grading.Bands;
import quest.server.grading.GradingTestSupport;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.StaffScopeEntity;

/**
 * Feature 36 end to end: the academies V58 made out of the existing departments, their settings, the one visual tree,
 * the drag that moves a teacher, the three bulk tools, the grading schemes the gradebook and the exports read, and
 * the two guardrails — the Admin alone, and a confirmation before a released result reads differently.
 *
 * <p>Each test builds its own school ({@link #school}) so nothing leaks between them in the suite's one shared H2
 * database (feature 32's CI-only failures were leaked rows; {@link #clean} empties every table of this feature by
 * school prefix). The cast, per school:
 * <ul>
 *   <li>{@code director}: a MANAGERIAL account whose `staff_scopes` row names `british` — the British academy's
 *       director, exactly as feature 14 models one;</li>
 *   <li>{@code coord}: a COORDINATOR whose row names the subject `math` with no curriculum, so she covers both;</li>
 *   <li>{@code sara}: a teacher of the British 1M (math) and {@code amy} a teacher of the American 1M;</li>
 *   <li>{@code nadia}: a floor supervisor whose floor holds the British 1M;</li>
 *   <li>classes: British grade 1 and grade 2, American grade 1; one child on the British grade 1.</li>
 * </ul>
 */
class SchoolStructureApiTest extends GradingTestSupport {
    private static final String BRITISH = "british", AMERICAN = "american";

    @Autowired AcademyRepository academyRows;
    @Autowired AcademySubjectRepository subjectRows;
    @Autowired AcademyTermRepository termRows;
    @Autowired GradingSchemeRepository schemeRows;
    @Autowired GradingBandRepository bandRows;
    @Autowired GradingSchemes schemes;
    @Autowired quest.server.tenancy.StaffScopeRepository scopes;
    @Autowired quest.server.floors.FloorRepository floorRows;
    @Autowired quest.server.floors.FloorClassRepository floorClassRows;
    @Autowired quest.server.floors.FloorSupervisorRepository floorSupervisorRows;

    @Override public String prefix() { return "st36-"; }

    @AfterEach void clean() {
        bandRows.deleteAll(bandRows.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        schemeRows.deleteAll(schemeRows.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        termRows.deleteAll(termRows.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        subjectRows.deleteAll(subjectRows.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        academyRows.deleteAll(academyRows.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        floorSupervisorRows.deleteAll(floorSupervisorRows.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        floorClassRows.deleteAll(floorClassRows.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        floorRows.deleteAll(floorRows.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        scopes.deleteAll(scopes.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        removeSeed();
        assignments.deleteAll(assignments.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        // `children.class_id` is a foreign key, and `removeSeed` only marks the children deleted: the roster link has
        // to go before the sections do, or the delete below trips the constraint.
        childRows.findAll().stream().filter(c -> c.getSchoolId().startsWith(prefix()) && c.getClassId() != null)
                .forEach(c -> { c.setClassId(null); childRows.save(c); });
        classes.deleteAll(classes.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
    }

    // ---------------------------------------------------------------- the migration's academies

    @Test void the_existing_departments_appear_as_academies_with_their_directors() throws Exception {
        var s = school("seed");
        var academies = ok(s.admin(get("/admin/structure/academies")));
        assertThat(keys(academies)).as("one academy per curriculum key already in use").containsExactly(AMERICAN, BRITISH);
        var british = academies.get(1);
        assertThat(british.get("nameEn").asText()).isEqualTo("British");
        assertThat(british.get("nameAr").asText()).isEqualTo("بريطاني");
        assertThat(british.get("curriculumType").asText()).isEqualTo("BRITISH");
        assertThat(british.get("director").get("userId").asText()).as("the MANAGERIAL staff_scopes row for `british`")
                .isEqualTo(s.director);
        assertThat(academies.get(0).get("director").isNull()).as("the American department has no manager yet").isTrue();
        // The naming the spec's own example asks for: grade 1 is "Year 2" in British and "Grade 1" in American.
        assertThat(british.get("naming").get("style").asText()).isEqualTo("YEAR");
        assertThat(british.get("naming").get("offset").asInt()).isEqualTo(1);
        // Nothing that used the key changed: the classes, the children and the assignments still resolve.
        assertThat(classes.findOneById(s.british1)).isPresent();
        assertThat(ok(s.admin(get("/admin/structure"))).get("academies")).hasSize(2);
    }

    @Test void an_academys_settings_naming_subjects_terms_and_director_are_the_admins() throws Exception {
        var s = school("settings");
        var updated = ok(s.admin(put("/admin/structure/academies/british").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nameEn\":\"British section\",\"nameAr\":\"القسم البريطاني\",\"curriculumType\":\"MINISTRY\","
                        + "\"gradeStyle\":\"GRADE\",\"gradeOffset\":0,\"gradeWordEn\":\"Grade\",\"gradeWordAr\":\"الصف\","
                        + "\"directorId\":\"" + s.secondDirector + "\"}")));
        assertThat(updated.get("nameEn").asText()).isEqualTo("British section");
        assertThat(updated.get("curriculumType").asText()).isEqualTo("MINISTRY");
        assertThat(updated.get("naming").get("style").asText()).isEqualTo("GRADE");
        assertThat(updated.get("director").get("userId").asText()).isEqualTo(s.secondDirector);
        // Changing the director moved her `staff_scopes` row: that is the one place the rest of the platform reads.
        assertThat(departmentRows(s.schoolId)).containsExactly(s.secondDirector);

        // A curriculum type nobody defined, and a director who is not a department manager, are both refused.
        s.expect(put("/admin/structure/academies/british").contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculumType\":\"SWISS\"}"), status().isBadRequest());
        s.expect(put("/admin/structure/academies/british").contentType(MediaType.APPLICATION_JSON)
                .content("{\"directorId\":\"" + s.sara + "\"}"), status().isBadRequest());
        s.expect(put("/admin/structure/academies/swiss").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nameEn\":\"Swiss\"}"), status().isNotFound());
    }

    @Test void subjects_are_the_shared_ministry_ones_plus_the_academys_own() throws Exception {
        var s = school("subjects");
        var before = ok(s.admin(get("/admin/structure/academies")));
        assertThat(subjectKeys(before.get(1).get("subjects")))
                .as("an academy that has never chosen offers every subject key, which is what is true today")
                .contains("arabic", "religion", "math", "english", "science", "french");
        assertThat(before.get(1).get("subjects").get(0).get("ministry").asBoolean()).as("Arabic is a Ministry subject").isTrue();

        var saved = ok(s.admin(put("/admin/structure/academies/british/subjects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjects\":[{\"subject\":\"arabic\",\"nameEn\":\"Arabic\",\"nameAr\":\"اللغة العربية\",\"ministry\":true},"
                        + "{\"subject\":\"math\",\"nameEn\":\"Maths\",\"nameAr\":\"رياضيات\",\"ministry\":false}]}")));
        assertThat(subjectKeys(saved)).containsExactly("arabic", "math");
        assertThat(saved.get(1).get("nameEn").asText()).as("the academy's own name for the shared key").isEqualTo("Maths");

        // A key this platform does not have is refused — `Subject` is a closed enum on the wire (the leftover).
        s.expect(put("/admin/structure/academies/british/subjects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjects\":[{\"subject\":\"astronomy\",\"nameEn\":\"Astronomy\",\"nameAr\":\"فلك\"}]}"),
                status().isBadRequest());
        s.expect(put("/admin/structure/academies/british/subjects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjects\":[]}"), status().isBadRequest());

        // An assignment cannot be moved onto a subject the class's academy does not offer.
        s.expect(put("/admin/structure/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(move(s.british1, "math", s.british2, "science", false)), status().isBadRequest());
    }

    @Test void terms_need_valid_dates_and_may_not_overlap_inside_one_year() throws Exception {
        var s = school("terms");
        var saved = ok(s.admin(put("/admin/structure/academies/british/terms").contentType(MediaType.APPLICATION_JSON)
                .content("{\"terms\":[" + term("2026/2027", "Term 1", "2026-09-01", "2026-12-20") + ","
                        + term("2026/2027", "Term 2", "2027-01-05", "2027-03-25") + ","
                        + term("2027/2028", "Term 1", "2027-09-01", "2027-12-20") + "]}")));
        assertThat(saved).hasSize(3);
        assertThat(saved.get(0).get("nameEn").asText()).isEqualTo("Term 1");
        assertThat(saved.get(0).get("startsOn").asText()).isEqualTo("2026-09-01");

        // Ending before it starts is not a term…
        s.expect(put("/admin/structure/academies/british/terms").contentType(MediaType.APPLICATION_JSON)
                .content("{\"terms\":[" + term("2026/2027", "Backwards", "2026-12-20", "2026-09-01") + "]}"),
                status().isBadRequest());
        // …and two terms of one year may not overlap, while the same dates in another year are fine.
        s.expect(put("/admin/structure/academies/british/terms").contentType(MediaType.APPLICATION_JSON)
                .content("{\"terms\":[" + term("2026/2027", "Term 1", "2026-09-01", "2026-12-20") + ","
                        + term("2026/2027", "Term 2", "2026-12-01", "2027-03-25") + "]}"),
                status().isConflict());
        assertThat(ok(s.admin(put("/admin/structure/academies/british/terms").contentType(MediaType.APPLICATION_JSON)
                .content("{\"terms\":[" + term("2026/2027", "Term 1", "2026-09-01", "2026-12-20") + ","
                        + term("2027/2028", "Term 1", "2026-09-01", "2026-12-20") + "]}")))).hasSize(2);
        // A refused call changed nothing: the last accepted list is what is stored.
        assertThat(ok(s.admin(get("/admin/structure/academies")))).hasSize(2);
    }

    // ---------------------------------------------------------------- the grading schemes

    @Test void the_default_scheme_is_todays_four_bands_at_every_boundary() {
        for (int score = 0; score <= 100; score++)
            assertThat(GradingSchemes.DEFAULT.bandFor(score).key())
                    .as("the default scheme must equal Bands at %s", score).isEqualTo(Bands.band(score));
        for (int boundary : List.of(0, Bands.DEVELOPING_AT, Bands.SECURE_AT, Bands.EXCEEDING_AT)) {
            assertThat(GradingSchemes.DEFAULT.bandFor(boundary).key()).isEqualTo(Bands.band(boundary));
            assertThat(GradingSchemes.DEFAULT.bandFor(boundary - 1).key()).isEqualTo(Bands.band(boundary - 1));
        }
        assertThat(GradingSchemes.DEFAULT.custom()).isFalse();
        assertThat(GradingSchemes.DEFAULT.bands()).extracting(GradingSchemes.Band::nameEn)
                .as("the names are today's own words, so every export stays byte-identical")
                .containsExactly(Bands.EMERGING, Bands.DEVELOPING, Bands.SECURE, Bands.EXCEEDING);
    }

    @Test void a_letter_scheme_and_a_numeric_scheme_band_scores_at_their_thresholds() throws Exception {
        var s = school("scheme");
        var letters = ok(s.admin(put("/admin/structure/academies/british/grading-scheme").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"LETTER\",\"bands\":[" + band("U", 0, "Ungraded", "غير مصنّف") + ","
                        + band("C", 50, "C", "ج") + "," + band("A*", 90, "A*", "أ*") + "],\"confirm\":true}")));
        assertThat(letters.get("kind").asText()).isEqualTo("LETTER");
        assertThat(letters.get("custom").asBoolean()).isTrue();
        var scheme = schemes.of(s.schoolId, BRITISH);
        assertThat(scheme.bandFor(0).key()).isEqualTo("U");
        assertThat(scheme.bandFor(49).key()).isEqualTo("U");
        assertThat(scheme.bandFor(50).key()).as("a band starts at its own threshold").isEqualTo("C");
        assertThat(scheme.bandFor(89).key()).isEqualTo("C");
        assertThat(scheme.bandFor(90).key()).isEqualTo("A*");
        assertThat(scheme.bandFor(100).key()).isEqualTo("A*");
        // The other academy is untouched: a scheme is per academy, and American still reads today's four.
        assertThat(schemes.of(s.schoolId, AMERICAN).custom()).isFalse();
        assertThat(schemes.of(s.schoolId, AMERICAN).bandFor(70).key()).isEqualTo(Bands.SECURE);

        var numeric = ok(s.admin(put("/admin/structure/academies/american/grading-scheme").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"NUMERIC\",\"bands\":[" + band("1", 0, "1", "١") + "," + band("4", 75, "4", "٤")
                        + "],\"confirm\":true}")));
        assertThat(numeric.get("kind").asText()).isEqualTo("NUMERIC");
        assertThat(schemes.of(s.schoolId, AMERICAN).bandFor(74).key()).isEqualTo("1");
        assertThat(schemes.of(s.schoolId, AMERICAN).bandFor(75).key()).isEqualTo("4");

        // One band is not a scheme, a threshold outside 0–100 is not a threshold, and something has to start at 0.
        s.expect(put("/admin/structure/academies/british/grading-scheme").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"LETTER\",\"bands\":[" + band("U", 0, "Ungraded", "غير مصنّف") + "]}"),
                status().isBadRequest());
        s.expect(put("/admin/structure/academies/british/grading-scheme").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"LETTER\",\"bands\":[" + band("U", 0, "U", "غ") + "," + band("A", 140, "A", "أ") + "]}"),
                status().isBadRequest());
        s.expect(put("/admin/structure/academies/british/grading-scheme").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"LETTER\",\"bands\":[" + band("U", 10, "U", "غ") + "," + band("A", 90, "A", "أ") + "]}"),
                status().isBadRequest());
        s.expect(put("/admin/structure/academies/british/grading-scheme").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"STARS\",\"bands\":[" + band("U", 0, "U", "غ") + "," + band("A", 90, "A", "أ") + "]}"),
                status().isBadRequest());
    }

    @Test void the_gradebook_the_exports_and_the_child_page_read_the_academys_scheme() throws Exception {
        var s = school("book");
        var lesson = s.playedLesson(false);
        enableGrading(s.token, s.schoolId);

        // With no scheme set: exactly today's words, in the API and in the CSV.
        var before = ok(as(get("/teacher/classes/" + s.british1 + "/gradebook"), s.token(s.sara)));
        assertThat(before.get("scheme").get("custom").asBoolean()).isFalse();
        var row = before.get("children").get(0);
        assertThat(row.get("average").isNull()).as("Maya has played, so there is a score to band").isFalse();
        int score = row.get("average").asInt();
        assertThat(row.get("band").asText()).isEqualTo(Bands.band(score));
        assertThat(row.get("bandLabel").asText()).as("the additive label beside the legacy key").isEqualTo(Bands.band(score));
        assertThat(csv(s, s.british1)).contains(Bands.band(score));

        // With a letter scheme whose top band starts below her score: letters, in both.
        ok(s.admin(put("/admin/structure/academies/british/grading-scheme").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"LETTER\",\"bands\":[" + band("U", 0, "Ungraded", "غير مصنّف") + ","
                        + band("A*", Math.max(0, score), "A*", "أ*") + "],\"confirm\":true}")));
        var after = ok(as(get("/teacher/classes/" + s.british1 + "/gradebook"), s.token(s.sara)));
        assertThat(after.get("scheme").get("custom").asBoolean()).isTrue();
        assertThat(after.get("children").get(0).get("band").asText()).as("the legacy key never moves").isEqualTo(Bands.band(score));
        assertThat(after.get("children").get(0).get("bandLabel").asText()).isEqualTo("A*");
        assertThat(after.get("children").get(0).get("cells").get(0).get("bandLabel").asText()).isEqualTo("A*");
        assertThat(csv(s, s.british1)).as("the download follows the scheme too").contains("A*");

        // The child page reads the same scheme, and so does the Results page the exports are built from.
        var child = ok(as(get("/teacher/children/" + s.child), s.token(s.sara)));
        assertThat(child.get("scheme").get("bands").get(1).get("band").asText()).isEqualTo("A*");
        assertThat(child.get("levels").get(0).get("bandLabel").asText()).isEqualTo("A*");
        var results = ok(as(get("/teacher/lessons/" + lesson + "/results"), s.token(s.sara)));
        assertThat(results.get("scheme").get("custom").asBoolean()).isTrue();
        assertThat(results.get("children").get(0).get("bandLabel").asText()).isEqualTo("A*");
    }

    // ---------------------------------------------------------------- the tree and the drag

    @Test void the_tree_is_academy_then_grades_then_classes_then_teachers_per_subject() throws Exception {
        var s = school("tree");
        var tree = ok(s.admin(get("/admin/structure"))).get("academies");
        var british = tree.get(1);
        assertThat(british.get("academy").get("academyKey").asText()).isEqualTo(BRITISH);
        assertThat(british.get("grades")).as("every grade of the curriculum's courses, even an empty one").hasSize(3);
        assertThat(british.get("grades").get(0).get("labelEn").asText()).isEqualTo("Year 2");
        assertThat(tree.get(0).get("grades").get(0).get("labelEn").asText()).as("the same grade, the other naming")
                .isEqualTo("Grade 1");
        assertThat(british.get("grades").get(2).get("classes")).as("a grade with no class is still a node").isEmpty();

        var section = british.get("grades").get(0).get("classes").get(0);
        assertThat(section.get("classId").asText()).isEqualTo(s.british1);
        assertThat(section.get("children").asInt()).isEqualTo(1);
        assertThat(section.get("floorSupervisors").get(0).get("userId").asText()).isEqualTo(s.nadia);
        var math = slot(section, "math");
        assertThat(math.get("teacher").get("userId").asText()).isEqualTo(s.sara);
        assertThat(math.get("coordinators").get(0).get("userId").asText()).as("the coordinator of that subject").isEqualTo(s.coord);
        assertThat(slot(section, "science").get("teacher").isNull()).as("an empty spot is visible").isTrue();

        // Feature 20 is a feature of its own: with its switch off the tree draws no floor supervisor.
        setFlag(s.token, s.schoolId, FlagKeys.FLOOR_SUPERVISOR, false);
        var without = ok(s.admin(get("/admin/structure"))).get("academies").get(1)
                .get("grades").get(0).get("classes").get(0);
        assertThat(without.get("floorSupervisors")).isEmpty();
        setFlag(s.token, s.schoolId, FlagKeys.FLOOR_SUPERVISOR, true);
    }

    @Test void dragging_a_teacher_moves_her_assignment_and_replacing_one_is_asked_first() throws Exception {
        var s = school("drag");
        // The empty spot first: British grade 2 teaches no maths, so the move needs no confirmation.
        var moved = ok(s.admin(put("/admin/structure/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(move(s.british1, "math", s.british2, "math", false))));
        assertThat(moved.get("classId").asText()).isEqualTo(s.british2);
        assertThat(moved.get("teacher").get("userId").asText()).isEqualTo(s.sara);
        assertThat(moved.get("replaced").isNull()).isTrue();
        assertThat(assignments.findByClassIdAndSubject(s.british1, "math")).isEmpty();
        assertThat(assignments.findByClassIdAndSubject(s.british2, "math")).isPresent();

        // Dropping onto a taken slot asks first, by name, and changes nothing until `replace` is sent.
        assign(s, s.british1, "math", s.amy);
        var refused = mvc.perform(s.admin(put("/admin/structure/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(move(s.british2, "math", s.british1, "math", false))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(json(refused).get("code").asText()).isEqualTo("replace_required");
        assertThat(json(refused).get("message").asText()).contains("Amy");
        assertThat(assignments.findByClassIdAndSubject(s.british1, "math").orElseThrow().getTeacherId()).isEqualTo(s.amy);

        var replaced = ok(s.admin(put("/admin/structure/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(move(s.british2, "math", s.british1, "math", true))));
        assertThat(replaced.get("teacher").get("userId").asText()).isEqualTo(s.sara);
        assertThat(replaced.get("replaced").get("userId").asText()).isEqualTo(s.amy);
        assertThat(assignments.findByClassIdAndSubject(s.british1, "math").orElseThrow().getTeacherId()).isEqualTo(s.sara);
        assertThat(assignments.findByClassIdAndSubject(s.british2, "math")).isEmpty();

        // A slot nobody holds is a 404, and a class of another school is a 404 too.
        s.expect(put("/admin/structure/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(move(s.british2, "english", s.british1, "english", false)), status().isNotFound());
        s.expect(put("/admin/structure/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(move("someone-elses-class", "math", s.british1, "math", false)), status().isNotFound());
    }

    // ---------------------------------------------------------------- the bulk tools

    @Test void next_years_classes_are_previewed_then_created_with_or_without_the_children() throws Exception {
        var s = school("year");
        var preview = ok(s.admin(post("/admin/structure/next-year/preview").contentType(MediaType.APPLICATION_JSON)
                .content("{\"academyKey\":\"british\",\"carryChildren\":true}")));
        assertThat(preview.get("classesCreated").asInt()).isEqualTo(2);
        assertThat(preview.get("childrenMoved").asInt()).isEqualTo(1);
        assertThat(preview.get("graduating").asInt()).as("grade 3 has nowhere to go").isZero();
        assertThat(preview.get("classes").get(0).get("name").asText()).as("the same name pattern, a grade up").isEqualTo("2M");
        assertThat(preview.get("classes").get(0).get("toLabelEn").asText()).isEqualTo("Year 3");
        assertThat(classes.findBySchoolIdAndCurriculumAndGrade(s.schoolId, BRITISH, 3))
                .as("a preview writes nothing").isEmpty();

        var applied = ok(s.admin(post("/admin/structure/next-year/apply").contentType(MediaType.APPLICATION_JSON)
                .content("{\"academyKey\":\"british\",\"carryChildren\":true}")));
        assertThat(applied.get("classesCreated").asInt()).isEqualTo(2);
        var created = classes.findBySchoolIdAndCurriculumAndGradeAndNameIsNotNullOrderByNameAsc(s.schoolId, BRITISH, 2);
        assertThat(created).extracting(ClassEntity::getName).contains("2M");
        var third = classes.findBySchoolIdAndCurriculumAndGradeAndNameIsNotNullOrderByNameAsc(s.schoolId, BRITISH, 3);
        assertThat(third).as("grade 2's class moved up to grade 3").hasSize(1);
        // The child came along; the teachers did not.
        var target = created.stream().filter(k -> "2M".equals(k.getName())).findFirst().orElseThrow();
        assertThat(childRows.findByClassIdAndDeletedAtIsNullOrderByNameAsc(target.getId())).hasSize(1);
        assertThat(assignments.findByClassIdOrderBySubjectAsc(target.getId())).as("a new year's teachers are a decision").isEmpty();

        // Without the children, the classes are made and the rosters stay where they are.
        var s2 = school("year2");
        ok(s2.admin(post("/admin/structure/next-year/apply").contentType(MediaType.APPLICATION_JSON)
                .content("{\"academyKey\":\"british\"}")));
        var fresh = classes.findBySchoolIdAndCurriculumAndGradeAndNameIsNotNullOrderByNameAsc(s2.schoolId, BRITISH, 2).stream()
                .filter(k -> "2M".equals(k.getName())).findFirst().orElseThrow();
        assertThat(childRows.findByClassIdAndDeletedAtIsNullOrderByNameAsc(fresh.getId())).isEmpty();
        assertThat(childRows.findByClassIdAndDeletedAtIsNullOrderByNameAsc(s2.british1)).hasSize(1);
        // An academy nobody has is a 404, and no academy at all is a 400.
        s.expect(post("/admin/structure/next-year/preview").contentType(MediaType.APPLICATION_JSON)
                .content("{\"academyKey\":\"swiss\"}"), status().isNotFound());
        s.expect(post("/admin/structure/next-year/preview").contentType(MediaType.APPLICATION_JSON).content("{}"),
                status().isBadRequest());
    }

    @Test void renaming_the_grades_of_an_academy_relabels_every_grade_at_once() throws Exception {
        var s = school("names");
        var renamed = ok(s.admin(put("/admin/structure/grade-names").contentType(MediaType.APPLICATION_JSON)
                .content("{\"academyKey\":\"british\",\"style\":\"GRADE\",\"offset\":0,\"wordEn\":\"Stage\",\"wordAr\":\"المرحلة\"}")));
        assertThat(renamed.get("naming").get("wordEn").asText()).isEqualTo("Stage");
        assertThat(renamed.get("grades")).hasSize(3);
        assertThat(renamed.get("grades").get(0).get("labelEn").asText()).isEqualTo("Stage 1");
        assertThat(renamed.get("grades").get(0).get("labelAr").asText()).isEqualTo("المرحلة 1");
        assertThat(ok(s.admin(get("/admin/structure"))).get("academies").get(1).get("grades").get(0)
                .get("labelEn").asText()).as("the tree follows").isEqualTo("Stage 1");
        // An offset nobody could mean, and a style nobody defined, are refused.
        s.expect(put("/admin/structure/grade-names").contentType(MediaType.APPLICATION_JSON)
                .content("{\"academyKey\":\"british\",\"offset\":9}"), status().isBadRequest());
        s.expect(put("/admin/structure/grade-names").contentType(MediaType.APPLICATION_JSON)
                .content("{\"academyKey\":\"british\",\"style\":\"TERM\"}"), status().isBadRequest());
    }

    @Test void moving_a_class_lists_everything_that_changes_and_then_applies_it() throws Exception {
        var s = school("move");
        // The target academy offers Arabic only, so the class's maths assignment is one of the things that changes.
        ok(s.admin(put("/admin/structure/academies/american/subjects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjects\":[{\"subject\":\"arabic\",\"nameEn\":\"Arabic\",\"nameAr\":\"اللغة العربية\",\"ministry\":true}]}")));
        var preview = ok(s.admin(post("/admin/structure/classes/" + s.british1 + "/move/preview")
                .contentType(MediaType.APPLICATION_JSON).content("{\"toAcademyKey\":\"american\"}")));
        assertThat(preview.get("fromAcademyKey").asText()).isEqualTo(BRITISH);
        assertThat(preview.get("toAcademyKey").asText()).isEqualTo(AMERICAN);
        assertThat(preview.get("children").asInt()).isEqualTo(1);
        assertThat(preview.get("droppedAssignments").get(0).get("subject").asText()).isEqualTo("math");
        assertThat(preview.get("fromDirector").get("userId").asText()).isEqualTo(s.director);
        assertThat(preview.get("toDirector").isNull()).isTrue();
        assertThat(preview.get("fromCoordinators").get(0).get("userId").asText()).isEqualTo(s.coord);
        assertThat(preview.get("fromScheme").get("custom").asBoolean()).isFalse();
        assertThat(classes.findOneById(s.british1).orElseThrow().getCurriculum()).as("a preview writes nothing").isEqualTo(BRITISH);

        var applied = ok(s.admin(post("/admin/structure/classes/" + s.british1 + "/move/apply")
                .contentType(MediaType.APPLICATION_JSON).content("{\"toAcademyKey\":\"american\"}")));
        assertThat(applied.get("toAcademyKey").asText()).isEqualTo(AMERICAN);
        assertThat(classes.findOneById(s.british1).orElseThrow().getCurriculum()).isEqualTo(AMERICAN);
        assertThat(childRows.findByClassIdAndDeletedAtIsNullOrderByNameAsc(s.british1))
                .allSatisfy(c -> assertThat(c.getCurriculum()).isEqualTo(AMERICAN));
        assertThat(assignments.findByClassIdAndSubject(s.british1, "math"))
                .as("the target academy has no maths to teach").isEmpty();
        // Moving it where it already is, and to an academy nobody has, are both refused.
        s.expect(post("/admin/structure/classes/" + s.british1 + "/move/preview").contentType(MediaType.APPLICATION_JSON)
                .content("{\"toAcademyKey\":\"american\"}"), status().isBadRequest());
        s.expect(post("/admin/structure/classes/" + s.british1 + "/move/preview").contentType(MediaType.APPLICATION_JSON)
                .content("{\"toAcademyKey\":\"swiss\"}"), status().isNotFound());
    }

    // ---------------------------------------------------------------- the released-results guardrail

    @Test void a_scheme_change_touching_released_results_is_confirmed_first() throws Exception {
        var s = school("released");
        s.playedLesson(true);
        enableGrading(s.token, s.schoolId);
        int score = ok(as(get("/teacher/classes/" + s.british1 + "/gradebook"), s.token(s.sara)))
                .get("children").get(0).get("average").asInt();

        // A scheme that reads her released score as something else asks first, with the count.
        String body = "{\"kind\":\"LETTER\",\"bands\":[" + band("U", 0, "Ungraded", "غير مصنّف") + ","
                + band("A*", Math.max(0, score), "A*", "أ*") + "]}";
        var refused = mvc.perform(s.admin(put("/admin/structure/academies/british/grading-scheme")
                .contentType(MediaType.APPLICATION_JSON).content(body))).andExpect(status().isConflict()).andReturn();
        assertThat(json(refused).get("code").asText()).isEqualTo("confirm_released");
        assertThat(json(refused).get("message").asText()).contains("released results");
        assertThat(schemes.of(s.schoolId, BRITISH).custom()).as("a refused change wrote nothing").isFalse();

        var confirmed = ok(s.admin(put("/admin/structure/academies/british/grading-scheme")
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("]}", "],\"confirm\":true}"))));
        assertThat(confirmed.get("custom").asBoolean()).isTrue();
        // The score itself never changed — only the band it is shown in.
        assertThat(ok(as(get("/teacher/classes/" + s.british1 + "/gradebook"), s.token(s.sara)))
                .get("children").get(0).get("average").asInt()).isEqualTo(score);

        // Nothing released means nothing to confirm: the American academy's scheme saves straight away.
        var american = ok(s.admin(put("/admin/structure/academies/american/grading-scheme")
                .contentType(MediaType.APPLICATION_JSON).content(body)));
        assertThat(american.get("custom").asBoolean()).isTrue();
    }

    @Test void moving_a_class_with_released_results_is_confirmed_first() throws Exception {
        var s = school("movereleased");
        s.playedLesson(true);
        enableGrading(s.token, s.schoolId);
        int score = ok(as(get("/teacher/classes/" + s.british1 + "/gradebook"), s.token(s.sara)))
                .get("children").get(0).get("average").asInt();
        ok(s.admin(put("/admin/structure/academies/american/grading-scheme").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"LETTER\",\"bands\":[" + band("U", 0, "Ungraded", "غير مصنّف") + ","
                        + band("A*", Math.max(0, score), "A*", "أ*") + "],\"confirm\":true}")));

        var preview = ok(s.admin(post("/admin/structure/classes/" + s.british1 + "/move/preview")
                .contentType(MediaType.APPLICATION_JSON).content("{\"toAcademyKey\":\"american\"}")));
        assertThat(preview.get("releasedResults").asInt()).isPositive();
        assertThat(preview.get("rebandedResults").asInt()).isPositive();
        var refused = mvc.perform(s.admin(post("/admin/structure/classes/" + s.british1 + "/move/apply")
                .contentType(MediaType.APPLICATION_JSON).content("{\"toAcademyKey\":\"american\"}")))
                .andExpect(status().isConflict()).andReturn();
        assertThat(json(refused).get("code").asText()).isEqualTo("confirm_released");
        assertThat(classes.findOneById(s.british1).orElseThrow().getCurriculum()).isEqualTo(BRITISH);

        ok(s.admin(post("/admin/structure/classes/" + s.british1 + "/move/apply?confirm=true")
                .contentType(MediaType.APPLICATION_JSON).content("{\"toAcademyKey\":\"american\"}")));
        assertThat(classes.findOneById(s.british1).orElseThrow().getCurriculum()).isEqualTo(AMERICAN);
        assertThat(ok(as(get("/teacher/classes/" + s.british1 + "/gradebook"), s.token(s.sara)))
                .get("children").get(0).get("bandLabel").asText()).isEqualTo("A*");
    }

    // ---------------------------------------------------------------- the two guardrails on the door

    @Test void the_admin_alone_and_only_while_the_switch_is_on() throws Exception {
        var s = school("roles");
        for (String path : List.of("/admin/structure", "/admin/structure/academies")) {
            mvc.perform(as(get(path), s.token(s.sara))).andExpect(status().isForbidden());
            mvc.perform(as(get(path), s.token(s.director))).andExpect(status().isForbidden());
            mvc.perform(as(get(path), s.token(s.coord))).andExpect(status().isForbidden());
            mvc.perform(as(get(path), s.token(s.nadia))).andExpect(status().isForbidden());
            mvc.perform(get(path)).andExpect(status().is4xxClientError());
        }
        mvc.perform(as(put("/admin/structure/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(move(s.british1, "math", s.british2, "math", false)), s.token(s.director)))
                .andExpect(status().isForbidden());

        setFlag(s.token, s.schoolId, FlagKeys.SCHOOL_STRUCTURE, false);
        try {
            s.expect(get("/admin/structure"), status().isNotFound());
            s.expect(put("/admin/structure/grade-names").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"academyKey\":\"british\"}"), status().isNotFound());
        } finally {
            setFlag(s.token, s.schoolId, FlagKeys.SCHOOL_STRUCTURE, true);
        }
    }

    @Test void another_schools_structure_is_invisible() throws Exception {
        var mine = school("mine");
        var theirs = school("theirs");
        var tree = ok(mine.admin(get("/admin/structure")));
        assertThat(tree.toString()).doesNotContain(theirs.british1).doesNotContain(theirs.sara);
        // A class of the other school is simply not found, join code and all.
        mine.expect(post("/admin/structure/classes/" + theirs.british1 + "/move/preview")
                .contentType(MediaType.APPLICATION_JSON).content("{\"toAcademyKey\":\"american\"}"), status().isNotFound());
        mine.expect(put("/admin/structure/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(move(theirs.british1, "math", mine.british1, "math", false)), status().isNotFound());
        // And a scheme of one school does not reach the other.
        ok(mine.admin(put("/admin/structure/academies/british/grading-scheme").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"LETTER\",\"bands\":[" + band("U", 0, "Ungraded", "غير مصنّف") + ","
                        + band("A*", 10, "A*", "أ*") + "],\"confirm\":true}")));
        assertThat(schemes.of(theirs.schoolId, BRITISH).custom()).isFalse();
    }

    // ---------------------------------------------------------------- the fixture

    /** One school of this feature's cast, with the ids every test names. */
    private final class School {
        final String schoolId, code, token;
        final String director, secondDirector, coord, sara, amy, nadia, child;
        final String british1, british2, american1;

        School(String tag) throws Exception {
            schoolId = prefix() + tag; code = code(tag);
            school(schoolId, "Structure " + tag, code);
            token = adminToken();
            director = user(tag + "-director", "MANAGERIAL", "Dina");
            secondDirector = user(tag + "-director2", "MANAGERIAL", "Dalia");
            coord = user(tag + "-coord", "COORDINATOR", "Karim");
            nadia = user(tag + "-nadia", "FLOOR_SUPERVISOR", "Nadia");
            sara = teacher(schoolId + ":sara", schoolId, "Sara").getId();
            amy = teacher(schoolId + ":amy", schoolId, "Amy").getId();
            british1 = section(BRITISH, 1, "math", sara).getId();
            british2 = section(BRITISH, 2, "math", null).getId();
            american1 = section(AMERICAN, 1, "math", amy).getId();
            scope(tag + "-dep", director, null, BRITISH);
            scope(tag + "-coord-scope", coord, "math", null);
            floor(tag, british1, nadia);
            child = child("Maya", code, classes.findById(british1).orElseThrow());
        }

        /** The Admin, scoped to this school with `X-School-Id` as every Admin request is. */
        MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder builder) {
            return as(builder, token).header("X-School-Id", schoolId);
        }

        String token(String userId) {
            var row = users.findById(userId).orElseThrow();
            return jwt.issue(row.getId(), row.getEmail(), row.getRole(), schoolId).token();
        }

        void expect(MockHttpServletRequestBuilder builder, org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            mvc.perform(admin(builder)).andExpect(matcher);
        }

        /** A published lesson Maya has played, released or not, so there is a score to band. */
        String playedLesson(boolean released) {
            var section = classes.findById(british1).orElseThrow();
            var lesson = lesson(schoolId + ":lesson", schoolId, section, LocalDate.now().minusDays(3));
            if (released) { lesson.setReleasedAt(Instant.now()); lessons.save(lesson); }
            attempt(child, lesson.getId(), 1, 1, true, 3);
            attempt(child, lesson.getId(), 2, 1, true, 3);
            return lesson.getId();
        }

        private String user(String id, String role, String displayName) {
            var u = users.findById(prefix() + id).orElseGet(UserEntity::new);
            u.setId(prefix() + id); u.setSchoolId(schoolId); u.setEmail(prefix() + id + "@seed.test");
            u.setPasswordHash("x"); u.setRole(role); u.setStatus("active"); u.setDisplayName(displayName);
            if (u.getCreatedAt() == null) u.setCreatedAt(Instant.now());
            u.setUpdatedAt(Instant.now());
            return users.save(u).getId();
        }

        private ClassEntity section(String curriculum, int grade, String subject, String teacherId) {
            return ClassFixtures.section(classes, assignments, schoolId + ":" + curriculum + ":" + grade,
                    schoolId, curriculum, grade, subject, teacherId);
        }

        private void scope(String id, String userId, String subject, String curriculum) {
            var row = new StaffScopeEntity();
            row.setId(prefix() + id); row.setSchoolId(schoolId); row.setUserId(userId);
            row.setSubject(subject); row.setCurriculum(curriculum); row.setCreatedAt(Instant.now());
            scopes.save(row);
        }

        private void floor(String tag, String sectionId, String supervisor) {
            var floor = new quest.server.floors.Entities.FloorEntity();
            floor.setId(prefix() + tag + "-floor"); floor.setSchoolId(schoolId); floor.setName("Ground");
            floor.setAcademy(BRITISH); floor.setCreatedAt(Instant.now());
            floorRows.save(floor);
            var on = new quest.server.floors.Entities.FloorClassEntity();
            on.setId(prefix() + tag + "-floor-class"); on.setSchoolId(schoolId); on.setFloorId(floor.getId());
            on.setSectionId(sectionId); on.setCreatedAt(Instant.now());
            floorClassRows.save(on);
            var who = new quest.server.floors.Entities.FloorSupervisorEntity();
            who.setId(prefix() + tag + "-floor-supervisor"); who.setSchoolId(schoolId); who.setFloorId(floor.getId());
            who.setUserId(supervisor); who.setCreatedAt(Instant.now());
            floorSupervisorRows.save(who);
        }
    }

    private School school(String tag) throws Exception { return new School(tag); }

    /** A second teacher's assignment, with an id of its own: the moved row already holds `ClassFixtures`'s. */
    private void assign(School s, String classId, String subject, String teacherId) {
        var row = new quest.server.tenancy.Entities.TeachingAssignmentEntity();
        row.setId(prefix() + java.util.UUID.randomUUID()); row.setSchoolId(s.schoolId); row.setTeacherId(teacherId);
        row.setClassId(classId); row.setSubject(subject); row.setCreatedAt(Instant.now());
        assignments.save(row);
    }

    private List<String> departmentRows(String schoolId) {
        return scopes.findBySchoolIdOrderByUserIdAsc(schoolId).stream()
                .filter(r -> r.getSubject() == null && BRITISH.equals(r.getCurriculum()))
                .map(StaffScopeEntity::getUserId).toList();
    }

    /** The gradebook's own CSV, which is what a teacher downloads — the Band column is read out of it. */
    private String csv(School s, String classId) throws Exception {
        return mvc.perform(as(get("/teacher/classes/" + classId + "/gradebook.csv"), s.token(s.sara)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- small helpers

    private JsonNode ok(MockHttpServletRequestBuilder builder) throws Exception {
        return json(mvc.perform(builder).andExpect(status().is2xxSuccessful()).andReturn());
    }

    private static List<String> keys(JsonNode academies) {
        var out = new java.util.ArrayList<String>();
        academies.forEach(a -> out.add(a.get("academyKey").asText()));
        return out;
    }

    private static List<String> subjectKeys(JsonNode subjects) {
        var out = new java.util.ArrayList<String>();
        subjects.forEach(x -> out.add(x.get("subject").asText()));
        return out;
    }

    private static JsonNode slot(JsonNode section, String subject) {
        for (var slot : section.get("subjects")) if (subject.equals(slot.get("subject").asText())) return slot;
        throw new AssertionError("no slot for " + subject + " in " + section);
    }

    private static String move(String fromClass, String fromSubject, String toClass, String toSubject, boolean replace) {
        return "{\"fromClassId\":\"" + fromClass + "\",\"fromSubject\":\"" + fromSubject + "\",\"toClassId\":\""
                + toClass + "\",\"toSubject\":\"" + toSubject + "\",\"replace\":" + replace + "}";
    }

    private static String term(String year, String name, String starts, String ends) {
        return "{\"schoolYear\":\"" + year + "\",\"nameEn\":\"" + name + "\",\"nameAr\":\"" + name + " ع\",\"startsOn\":\""
                + starts + "\",\"endsOn\":\"" + ends + "\"}";
    }

    private static String band(String key, int lower, String nameEn, String nameAr) {
        return "{\"band\":\"" + key + "\",\"lower\":" + lower + ",\"nameEn\":\"" + nameEn + "\",\"nameAr\":\"" + nameAr + "\"}";
    }

    /** Join codes are unique platform-wide and the suite shares one database. */
    private static String code(String tag) {
        return ("S36" + Integer.toString(Math.abs(tag.hashCode()), 36)).toUpperCase(java.util.Locale.ROOT).substring(0, 6);
    }
}
