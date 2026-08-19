package com.devanshedutech.repository;

import com.devanshedutech.crm.LeadSpecifications;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.LeadActivity;
import com.devanshedutech.model.crm.ActivityType;
import com.devanshedutech.model.crm.Direction;
import com.devanshedutech.model.crm.Grade;
import com.devanshedutech.model.crm.LeadSource;
import com.devanshedutech.model.crm.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the pipeline queries actually run.
 *
 * <p>Unit tests with mocked repositories verify the rules but cannot catch a bad column
 * mapping, an invalid JPQL string, or a Criteria predicate that references a field that does
 * not exist — all of which fail only when a real database is asked to execute them.</p>
 *
 * <p>This runs on H2 in PostgreSQL compatibility mode, because no container runtime is
 * available here. It is a genuine check of the mappings and queries; it is not a guarantee of
 * identical behaviour on Postgres, so a staging run is still required before production.</p>
 */
@DataJpaTest
class LeadRepositoryQueryTest {

    @Autowired private LeadRepository leads;
    @Autowired private LeadActivityRepository activities;

    private static final String SNEHA = "u-sneha";
    private static final String ADITYA = "u-aditya";

    private Lead lead(String name, String phone, Stage stage, Grade grade,
                      String owner, LocalDate nextTouch) {
        Lead l = Lead.builder()
                .id(UUID.randomUUID().toString())
                .fullName(name)
                .mobileNumber(phone)
                .phoneNormalized(Lead.normalizePhone(phone))
                .cityName("Parbhani")
                .courseInterested("Data Analytics")
                .stage(stage)
                .grade(grade)
                .source(LeadSource.INSTAGRAM_AD)
                .assignedToId(owner)
                .nextTouchOn(nextTouch)
                .callAttempts(0)
                .updatesOnly(false)
                .optedOut(false)
                .createdAt(LocalDateTime.now())
                .build();
        return leads.save(l);
    }

    @BeforeEach
    void seed() {
        leads.deleteAll();
        lead("Rohit Deshmukh",  "+91 9876543210", Stage.CONTACTED,   Grade.HOT,  SNEHA,  LocalDate.now());
        lead("Sanika Pawar",    "+91 9876543211", Stage.CONTACTED,   Grade.WARM, SNEHA,  LocalDate.now().minusDays(2));
        lead("Omkar Bhosale",   "+91 9876543212", Stage.DEMO_BOOKED, Grade.HOT,  ADITYA, LocalDate.now().plusDays(1));
        lead("Pooja Waghmare",  "+91 9876543213", Stage.CONTACTED,   Grade.WARM, ADITYA, null);
        lead("Kiran Solanke",   "+91 9876543214", Stage.LOST,        Grade.COLD, ADITYA, null);
        lead("Nikhil Mane",     "+91 9876543215", Stage.NEW,         null,       null,   null);
    }

    @Test
    @DisplayName("every new column persists and reads back")
    void entityMappingIsValid() {
        Lead saved = leads.findAll().get(0);
        assertNotNull(saved.getStage());
        assertNotNull(saved.getSource());
        assertNotNull(saved.getPhoneNormalized());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt(), "@PrePersist must stamp updatedAt");
    }

    @Test
    @DisplayName("the ownership filter really narrows the query")
    void ownershipFilterWorks() {
        var mine = leads.findAll(LeadSpecifications.ownedBy(SNEHA));
        assertEquals(2, mine.size());
        assertTrue(mine.stream().allMatch(l -> SNEHA.equals(l.getAssignedToId())));

        // Null means no restriction, which is what a manager gets.
        assertEquals(6, leads.findAll(LeadSpecifications.all(LeadSpecifications.ownedBy(null))).size());
    }

    @Test
    @DisplayName("chained filters combine rather than replace each other")
    void chainedSpecifications() {
        var hotForSneha = leads.findAll(LeadSpecifications.all(
                LeadSpecifications.ownedBy(SNEHA),
                LeadSpecifications.gradeIs(Grade.HOT)));
        assertEquals(1, hotForSneha.size());
        assertEquals("Rohit Deshmukh", hotForSneha.get(0).getFullName());
    }

    @Test
    @DisplayName("open() excludes closed and opted-out leads")
    void openExcludesClosed() {
        var open = leads.findAll(LeadSpecifications.open());
        assertEquals(5, open.size());
        assertTrue(open.stream().noneMatch(l -> l.getStage() == Stage.LOST));
    }

    @Test
    @DisplayName("the overdue and due-today queues split correctly")
    void touchQueues() {
        var overdue = leads.findAll(LeadSpecifications.all(
                LeadSpecifications.open(), LeadSpecifications.nextTouchBefore(LocalDate.now())));
        assertEquals(1, overdue.size());
        assertEquals("Sanika Pawar", overdue.get(0).getFullName());

        var due = leads.findAll(LeadSpecifications.all(
                LeadSpecifications.open(), LeadSpecifications.nextTouchOn(LocalDate.now())));
        assertEquals(1, due.size());
        assertEquals("Rohit Deshmukh", due.get(0).getFullName());
    }

    @Test
    @DisplayName("the blank next-touch report finds the SOP violation and exempts cold leads")
    void blankNextTouchReport() {
        var blank = leads.findAll(LeadSpecifications.all(
                LeadSpecifications.open(), LeadSpecifications.blankNextTouch()));
        List<String> names = blank.stream().map(Lead::getFullName).toList();

        assertTrue(names.contains("Pooja Waghmare"), "a warm lead with no date is a violation");
        assertTrue(names.contains("Nikhil Mane"), "an ungraded new lead counts too");
        assertFalse(names.contains("Kiran Solanke"), "cold leads are exempt and also closed");
    }

    @Test
    @DisplayName("awaiting-first-reply finds only untouched new enquiries")
    void awaitingFirstReplyQueue() {
        var awaiting = leads.findAll(LeadSpecifications.awaitingFirstReply());
        assertEquals(1, awaiting.size());
        assertEquals("Nikhil Mane", awaiting.get(0).getFullName());
    }

    @Test
    @DisplayName("search matches names and partial phone numbers")
    void freeTextSearch() {
        assertEquals(1, leads.findAll(LeadSpecifications.matching("rohit")).size());
        assertEquals(1, leads.findAll(LeadSpecifications.matching("9876543212")).size());
        assertEquals(6, leads.findAll(LeadSpecifications.matching("parbhani")).size());
        assertEquals(0, leads.findAll(LeadSpecifications.matching("nobody")).size());
    }

    @Test
    @DisplayName("paging and sorting run against the database, not in memory")
    void pagingWorks() {
        var page = leads.findAll(LeadSpecifications.open(),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")));
        assertEquals(2, page.getContent().size());
        assertEquals(5, page.getTotalElements());
        assertEquals(3, page.getTotalPages());
    }

    @Test
    @DisplayName("duplicate detection finds the same number regardless of formatting")
    void phoneLookup() {
        assertEquals(1, leads.findByPhoneNormalized("9876543210").size());
        assertEquals(0, leads.findByPhoneNormalized("0000000000").size());
    }

    @Test
    @DisplayName("the reporting group-by queries execute")
    void reportingQueriesRun() {
        assertFalse(leads.countGroupedByStage().isEmpty());
        assertFalse(leads.countGroupedByGrade().isEmpty());
        assertFalse(leads.countGroupedBySource().isEmpty());
        assertFalse(leads.countGroupedByCourse().isEmpty());
        assertNotNull(leads.countEnrolledGroupedBySource());
        assertTrue(leads.countByCreatedAtBetween(
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1)) > 0);
    }

    @Test
    @DisplayName("the activity log persists and reads back newest first")
    void activityLogWorks() {
        String leadId = leads.findAll().get(0).getId();
        for (int i = 0; i < 3; i++) {
            activities.save(LeadActivity.builder()
                    .id(UUID.randomUUID().toString())
                    .leadId(leadId)
                    .type(ActivityType.CALL)
                    .direction(Direction.OUTBOUND)
                    .summary("Call " + i)
                    .detail("Spoke about the syllabus")
                    .createdByName("Sneha")
                    .createdAt(LocalDateTime.now().plusSeconds(i))
                    .build());
        }
        var timeline = activities.findByLeadIdOrderByCreatedAtDesc(leadId);
        assertEquals(3, timeline.size());
        assertEquals("Call 2", timeline.get(0).getSummary());
        assertEquals(3, activities.countRealTouches(leadId));
    }

    @Test
    @DisplayName("the metrics projections execute and return only what they should")
    void metricsProjectionsRun() {
        // Projections exist so a dashboard never pulls whole rows into memory to compute
        // one average; if the JPQL is wrong they fail here rather than in production.
        var funnel = leads.funnelProjection();
        assertEquals(6, funnel.size());
        assertEquals(4, funnel.get(0).length, "id, stage, source, owner");

        String leadId = leads.findAll().get(0).getId();
        leads.findAll().forEach(l -> {
            l.setFirstRespondedAt(l.getCreatedAt().plusMinutes(4));
            leads.save(l);
        });
        var stamps = leads.firstResponseTimestampsSince(LocalDateTime.now().minusDays(1));
        assertEquals(6, stamps.size());
        assertEquals(2, stamps.get(0).length, "created and first-responded only");

        activities.save(LeadActivity.builder()
                .id(UUID.randomUUID().toString()).leadId(leadId)
                .type(ActivityType.STAGE_CHANGE).direction(Direction.INTERNAL)
                .stageTo(Stage.DEMO_BOOKED).summary("Stage changed")
                .createdAt(LocalDateTime.now()).build());
        activities.save(LeadActivity.builder()
                .id(UUID.randomUUID().toString()).leadId(leadId)
                .type(ActivityType.STAGE_CHANGE).direction(Direction.INTERNAL)
                .stageTo(Stage.DEMO_DONE).summary("Stage changed")
                .createdAt(LocalDateTime.now()).build());

        var reached = activities.maxStageReachedPerLead();
        assertEquals(1, reached.size());
        assertEquals(leadId, reached.get(0)[0]);

        activities.save(LeadActivity.builder()
                .id(UUID.randomUUID().toString()).leadId(leadId)
                .type(ActivityType.CALL).direction(Direction.OUTBOUND)
                .summary("Call").createdAt(LocalDateTime.now()).build());
        var touches = activities.countTouchesForLeads(List.of(leadId));
        assertEquals(1, touches.size());
        assertEquals(1L, touches.get(0)[1], "stage changes are bookkeeping, not contact");
    }
}
