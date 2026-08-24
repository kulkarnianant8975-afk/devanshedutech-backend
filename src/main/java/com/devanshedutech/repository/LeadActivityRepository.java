package com.devanshedutech.repository;

import com.devanshedutech.model.LeadActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface LeadActivityRepository extends JpaRepository<LeadActivity, String> {

    List<LeadActivity> findByLeadIdOrderByCreatedAtDesc(String leadId);

    /** Real contact attempts on a lead, ignoring stage/grade bookkeeping entries. */
    @Query("select count(a) from LeadActivity a where a.leadId = :leadId "
         + "and a.type in (com.devanshedutech.model.crm.ActivityType.CALL, "
         + "com.devanshedutech.model.crm.ActivityType.WHATSAPP, "
         + "com.devanshedutech.model.crm.ActivityType.EMAIL, "
         + "com.devanshedutech.model.crm.ActivityType.DEMO)")
    long countRealTouches(@Param("leadId") String leadId);

    @Query("select a.createdById, count(a) from LeadActivity a where a.createdAt between :from and :to "
         + "and a.createdById is not null group by a.createdById")
    List<Object[]> countByCounsellorBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Every actual contact in a window, newest first, optionally for one counsellor.
     *
     * <p>Contacts only — the same filter {@link #countRealTouches} uses. A feed that also carried
     * grade changes, stage moves and system entries would answer "what happened in the CRM"
     * when the question being asked is "who did we speak to".</p>
     *
     * <p>The counsellor filter is a nullable parameter rather than a second method because the
     * two differ by one predicate, and two queries drift apart the moment one is changed.</p>
     */
    @Query("select a from LeadActivity a "
         + "where a.createdAt between :from and :to "
         + "and a.type in (com.devanshedutech.model.crm.ActivityType.CALL, "
         + "com.devanshedutech.model.crm.ActivityType.WHATSAPP, "
         + "com.devanshedutech.model.crm.ActivityType.EMAIL, "
         + "com.devanshedutech.model.crm.ActivityType.DEMO) "
         + "and (:counsellorId is null or a.createdById = :counsellorId) "
         + "order by a.createdAt desc")
    List<LeadActivity> findContactsBetween(@Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to,
                                           @Param("counsellorId") String counsellorId);

    /**
     * The deepest stage every lead has ever reached, in one query.
     *
     * <p>Conversion rates need history, not the current snapshot: a student who attended a demo
     * and then chose another institute still counts towards the enquiry-to-demo rate. Doing this
     * per lead would be a query per row, so it is fetched once and joined in memory.</p>
     */
    @Query("select a.leadId, max(a.stageTo) from LeadActivity a where a.stageTo is not null group by a.leadId")
    List<Object[]> maxStageReachedPerLead();

    /** Real contact counts for a set of leads, for "average follow-ups before enrolment". */
    @Query("select a.leadId, count(a) from LeadActivity a where a.leadId in :leadIds "
         + "and a.type in (com.devanshedutech.model.crm.ActivityType.CALL, "
         + "com.devanshedutech.model.crm.ActivityType.WHATSAPP, "
         + "com.devanshedutech.model.crm.ActivityType.EMAIL, "
         + "com.devanshedutech.model.crm.ActivityType.DEMO) group by a.leadId")
    List<Object[]> countTouchesForLeads(@Param("leadIds") List<String> leadIds);
}
