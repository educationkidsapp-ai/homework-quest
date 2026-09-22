package quest.server.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.ClassFixtures;
import quest.server.grading.GradingTestSupport;
import quest.server.tenancy.Entities.ClassEntity;

class AttendanceApiTest extends GradingTestSupport {

    static final String SCHOOL_A = "att-school-a", SCHOOL_B = "att-school-b";
    static final String TEACHER_SARA = "att-teacher-sara", TEACHER_OTHER = "att-teacher-other";
    static final String CLASS_1A = "att-class-1a";

    @Autowired AttendanceRepository attendanceRepo;

    private String saraToken, otherToken;
    private ClassEntity class1a;
    private String childMaya, childOmar;

    @Override public String prefix() { return "att-"; }

    @BeforeEach void setUp() throws Exception {
        school(SCHOOL_A, "Attendance Academy", "ATTSCHA");
        school(SCHOOL_B, "Other Academy", "ATTSCHB");

        teacher(TEACHER_SARA, SCHOOL_A, "Ms Sara");
        teacher(TEACHER_OTHER, SCHOOL_B, "Ms Other");

        class1a = klass(CLASS_1A, SCHOOL_A, TEACHER_SARA, "1A");
        ClassFixtures.assign(assignments, class1a, "math", TEACHER_SARA);

        saraToken = token(TEACHER_SARA, "TEACHER", SCHOOL_A);
        otherToken = token(TEACHER_OTHER, "TEACHER", SCHOOL_B);

        childMaya = child("Maya", "ATTSCHA", class1a);
        childOmar = child("Omar", "ATTSCHA", class1a);
    }

    @AfterEach void tearDown() {
        removeSeed();
    }

    @Test void teacher_reads_and_records_class_attendance_and_parent_sees_result() throws Exception {
        LocalDate today = LocalDate.now();

        // 1. Initial read by assigned teacher: 2 active children on roster, status NOT_MARKED
        var initialRes = mvc.perform(get("/teacher/classes/" + class1a.getId() + "/attendance?date=" + today)
                .header("Authorization", "Bearer " + saraToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classId").value(class1a.getId()))
                .andExpect(jsonPath("$.className").value("1A"))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.students").isArray())
                .andReturn();

        var initialJson = json(initialRes);
        assertThat(initialJson.get("students")).hasSize(2);

        // 2. Teacher records attendance: Maya is PRESENT, Omar is LATE with a note
        String payload = """
                {
                  "date": "%s",
                  "items": [
                    { "childId": "%s", "status": "PRESENT", "notes": null },
                    { "childId": "%s", "status": "LATE", "notes": "Bus delayed 15 mins" }
                  ]
                }
                """.formatted(today, childMaya, childOmar);

        mvc.perform(post("/teacher/classes/" + class1a.getId() + "/attendance")
                .header("Authorization", "Bearer " + saraToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.presentCount").value(1))
                .andExpect(jsonPath("$.lateCount").value(1))
                .andExpect(jsonPath("$.absentCount").value(0))
                .andExpect(jsonPath("$.attendanceRate").value(100.0));

        // 3. Parent of Maya reads child attendance history
        var parentRes = parentGet("/children/" + childMaya + "/attendance?from=" + today.minusDays(1) + "&to=" + today);
        assertThat(parentRes.get("records")).hasSize(1);
        assertThat(parentRes.get("records").get(0).get("status").asText()).isEqualTo("PRESENT");
        assertThat(parentRes.get("summary").get("presentDays").asInt()).isEqualTo(1);
        assertThat(parentRes.get("summary").get("attendanceRate").asDouble()).isEqualTo(100.0);

        // 4. Parent of Omar checks today's attendance endpoint
        var omarToday = parentGet("/children/" + childOmar + "/attendance/today");
        assertThat(omarToday.get("status").asText()).isEqualTo("LATE");
        assertThat(omarToday.get("notes").asText()).isEqualTo("Bus delayed 15 mins");

        // 5. Unassigned teacher cannot read or record attendance for class 1A (returns 403 or 404)
        mvc.perform(get("/teacher/classes/" + class1a.getId() + "/attendance")
                .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().is4xxClientError());
    }
}
