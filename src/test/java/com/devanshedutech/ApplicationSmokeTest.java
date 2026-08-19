package com.devanshedutech;

import com.devanshedutech.model.Lead;
import com.devanshedutech.model.Role;
import com.devanshedutech.model.User;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.model.crm.Grade;
import com.devanshedutech.repository.LadderStepRepository;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the whole application.
 *
 * <p>Unit tests verify rules and repository tests verify queries, but neither proves the
 * application starts: a missing bean, a bad security expression, or a startup runner that
 * throws will pass every other test and then fail on deploy. This starts the real context and
 * exercises the security chain end to end.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApplicationSmokeTest {

    @Autowired private MockMvc mvc;
    @Autowired private LeadRepository leads;
    @Autowired private UserRepository users;
    @Autowired private LadderStepRepository ladderSteps;

    /**
     * Authorisation happens in two layers and they read from different places, which is worth
     * being explicit about.
     *
     * <p>{@code @PreAuthorize} checks the authorities stamped into the session at sign-in — fast,
     * but stale after a role change. {@code AccessService} re-reads the user row on every call,
     * which is what makes a role change or a deactivation take effect on the very next request.
     * The account therefore has to exist for the second layer to grant anything, so these tests
     * persist the users they authenticate as, exactly as a real session would.</p>
     *
     * <p>The two layers can disagree for the lifetime of one session, and when they do the
     * refusal always comes from the stricter side: a demoted user is caught by the live lookup,
     * and a promoted user simply waits for their next sign-in.</p>
     */
    @BeforeEach
    void seedAccounts() {
        users.save(newUser("public@x.com", Role.NONE));
        users.save(newUser("sneha@x.com", Role.SALES_EXECUTIVE));
        users.save(newUser("viewer@x.com", Role.VIEWER));
        users.save(newUser("manager@x.com", Role.MANAGER));
    }

    @Test
    @DisplayName("the application context starts with every bean wired")
    void contextLoads() {
        assertNotNull(mvc);
        assertNotNull(leads);
    }

    @Test
    @DisplayName("the public enquiry form works without signing in")
    void publicEnquiryIsAccepted() throws Exception {
        mvc.perform(post("/api/leads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"fullName":"Rohit Deshmukh","mobileNumber":"+91 9812345678",
                             "cityName":"Parbhani","education":"Final year BCA",
                             "courseInterested":"Data Analytics",
                             "utmSource":"instagram","utmMedium":"cpc","utmCampaign":"aug-parbhani"}
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.duplicate").value(false));

        Lead saved = leads.findByPhoneNormalized("9812345678").get(0);
        assertEquals(Stage.NEW, saved.getStage());
        assertNull(saved.getGrade(), "capture never guesses a grade");
        assertEquals("aug-parbhani", saved.getUtmCampaign(), "attribution must survive the round trip");
    }

    @Test
    @DisplayName("a second submission from the same number is recognised, not duplicated")
    void repeatEnquiryIsMerged() throws Exception {
        String body = """
            {"fullName":"Sanika Pawar","mobileNumber":"9800000001","cityName":"Jintur"}
            """;
        mvc.perform(post("/api/leads").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/leads").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        assertEquals(1, leads.findByPhoneNormalized("9800000001").size());
    }

    @Test
    @DisplayName("an unusable phone number is refused with a message a student can act on")
    void badPhoneIsRejected() throws Exception {
        mvc.perform(post("/api/leads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Test\",\"mobileNumber\":\"123\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------- the security chain ----------------

    @Test
    @DisplayName("the pipeline is closed to anonymous callers")
    void pipelineRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/leads")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/leads/my-day")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/stats")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a signed-in account with no permissions is refused, not merely hidden from")
    @WithMockUser(username = "public@x.com", authorities = {"ROLE_NONE"})
    void noAccessRoleIsForbidden() throws Exception {
        mvc.perform(get("/api/leads")).andExpect(status().isForbidden());
        mvc.perform(get("/api/users")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a counsellor can read the pipeline but not the staff list")
    @WithMockUser(username = "sneha@x.com", authorities = {"ROLE_SALES_EXECUTIVE", "PERM_LEAD_VIEW_OWN"})
    void counsellorScope() throws Exception {
        mvc.perform(get("/api/leads")).andExpect(status().isOk());
        mvc.perform(get("/api/users")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a viewer cannot write, even though they can see everything")
    @WithMockUser(username = "viewer@x.com", authorities = {"ROLE_VIEWER", "PERM_LEAD_VIEW_ALL", "PERM_REPORT_VIEW"})
    void viewerIsReadOnly() throws Exception {
        mvc.perform(get("/api/leads")).andExpect(status().isOk());
        mvc.perform(post("/api/leads/any-id/outcome")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"CONNECTED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("content management is closed to a manager and open to an admin")
    @WithMockUser(username = "manager@x.com", authorities = {"ROLE_MANAGER", "PERM_LEAD_VIEW_ALL"})
    void managerCannotManageContent() throws Exception {
        mvc.perform(post("/api/courses")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("self-registration is closed by default")
    void selfRegistrationIsOff() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"intruder@x.com\",\"password\":\"password123\"}"))
                .andExpect(status().isForbidden());
        assertTrue(users.findByEmailIgnoreCase("intruder@x.com").isEmpty());
    }

    @Test
    @DisplayName("the three follow-up ladders are seeded on first start")
    void laddersAreSeeded() {
        assertEquals(7, ladderSteps.countByGrade(Grade.HOT));
        assertEquals(7, ladderSteps.countByGrade(Grade.WARM));
        assertEquals(7, ladderSteps.countByGrade(Grade.COLD));

        var warm = ladderSteps.findByGradeOrderByStepNoAsc(Grade.WARM);
        assertEquals(List.of(0, 1, 3, 5, 8, 12, 18),
                warm.stream().map(s -> s.getDayOffset()).toList(),
                "the warm lane must match the SOP's twenty-one day cadence");
        assertTrue(ladderSteps.findByGradeOrderByStepNoAsc(Grade.COLD).stream()
                        .allMatch(s -> Boolean.TRUE.equals(s.getAutoSend())),
                "cold leads are broadcast-only, never manually chased");
    }

    @Test
    @DisplayName("running the follow-up pass by hand needs the assign permission")
    @WithMockUser(username = "sneha@x.com", authorities = {"ROLE_SALES_EXECUTIVE", "PERM_LEAD_VIEW_OWN"})
    void counsellorCannotRunTheLadder() throws Exception {
        mvc.perform(post("/api/leads/ladder/run")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a manager can run the pass and gets a summary back")
    @WithMockUser(username = "manager@x.com",
            authorities = {"ROLE_MANAGER", "PERM_LEAD_VIEW_ALL", "PERM_LEAD_ASSIGN"})
    void managerCanRunTheLadder() throws Exception {
        mvc.perform(post("/api/leads/ladder/run")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the board returns every stage as a column, even the empty ones")
    @WithMockUser(username = "manager@x.com", authorities = {"ROLE_MANAGER", "PERM_LEAD_VIEW_ALL"})
    void boardHasAColumnPerStage() throws Exception {
        // An empty stage must still appear, or the board would silently lose a column the first
        // time an institute had nobody at that stage.
        mvc.perform(get("/api/leads/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns.length()").value(Stage.values().length))
                .andExpect(jsonPath("$.columns[0].stage").value("NEW"));
    }

    @Test
    @DisplayName("a counsellor can open the board; a signed-in account with no role cannot")
    @WithMockUser(username = "public@x.com", authorities = {"ROLE_NONE"})
    void boardRespectsPermissions() throws Exception {
        mvc.perform(get("/api/leads/board")).andExpect(status().isForbidden());
    }

    private User newUser(String email, Role role) {
        User u = users.findByEmailIgnoreCase(email).orElseGet(() -> User.builder()
                .id(UUID.randomUUID().toString()).email(email).displayName(email).active(true).build());
        u.setRole(role);
        return u;
    }
}
