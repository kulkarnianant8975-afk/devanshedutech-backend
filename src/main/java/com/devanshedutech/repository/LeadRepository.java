package com.devanshedutech.repository;

import com.devanshedutech.model.Lead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Lead persistence.
 *
 * <p>Filtering is done with {@link com.devanshedutech.crm.LeadSpecifications} rather than with
 * JPQL that compares optional parameters against null. On Postgres that pattern makes the
 * driver unable to infer a parameter's type when the value is null, which fails at runtime
 * rather than at compile time. The Criteria API builds only the predicates that apply, so the
 * problem cannot arise.</p>
 */
public interface LeadRepository extends JpaRepository<Lead, String>, JpaSpecificationExecutor<Lead> {

    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    /** Dedupe hint: the same student enquiring a second time. */
    List<Lead> findByPhoneNormalized(String phoneNormalized);

    /**
     * The recycle bin.
     *
     * <p>Native, because the entity carries an SQLRestriction that hides deleted rows from every
     * ordinary query — which is the point of it. Reading them back has to step around that
     * deliberately rather than by accident.</p>
     */
    @Query(value = "SELECT * FROM leads WHERE deleted_at IS NOT NULL "
                 + "ORDER BY deleted_at DESC LIMIT 200", nativeQuery = true)
    List<Lead> findDeleted();

    /** Puts one back. Returns the number of rows changed, so a bad id is distinguishable. */
    @Modifying
    @Query(value = "UPDATE leads SET deleted_at = NULL, deleted_by_id = NULL "
                 + "WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
    int restore(@Param("id") String id);

    /**
     * Only the two timestamps the first-response metric needs, rather than whole entities.
     * A dashboard should not pull every lead into memory to compute one average.
     */
    @Query("select l.createdAt, l.firstRespondedAt from Lead l "
         + "where l.firstRespondedAt is not null and l.createdAt >= :since")
    List<Object[]> firstResponseTimestampsSince(@Param("since") LocalDateTime since);

    /** Stage and source for every lead, for funnel and attribution maths, without the rest of the row. */
    @Query("select l.id, l.stage, l.source, l.assignedToId from Lead l")
    List<Object[]> funnelProjection();

    List<Lead> findByStage(com.devanshedutech.model.crm.Stage stage);

    @Query("select l.stage, count(l) from Lead l group by l.stage")
    List<Object[]> countGroupedByStage();

    @Query("select l.grade, count(l) from Lead l group by l.grade")
    List<Object[]> countGroupedByGrade();

    @Query("select l.source, count(l) from Lead l group by l.source")
    List<Object[]> countGroupedBySource();

    /** Enrolments per source — the playbook's "which source actually produces admissions". */
    @Query("select l.source, count(l) from Lead l "
         + "where l.stage = com.devanshedutech.model.crm.Stage.ENROLLED group by l.source")
    List<Object[]> countEnrolledGroupedBySource();

    @Query("select coalesce(l.courseInterested, 'Not specified'), count(l) "
         + "from Lead l group by l.courseInterested")
    List<Object[]> countGroupedByCourse();
}
