package com.devanshedutech.repository;

import com.devanshedutech.model.Demo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DemoRepository extends JpaRepository<Demo, String> {

    List<Demo> findByLeadIdOrderByScheduledAtDesc(String leadId);

    @Query("select d from Demo d where d.scheduledAt between :from and :to order by d.scheduledAt asc")
    List<Demo> findScheduledBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Demos whose time has passed with nobody marking whether the student turned up. */
    @Query("select d from Demo d where d.attended is null and d.scheduledAt < :now order by d.scheduledAt asc")
    List<Demo> findAwaitingMarking(@Param("now") LocalDateTime now);

    long countByAttended(Boolean attended);

    @Query("select count(d) from Demo d where d.attended is not null")
    long countMarked();
}
