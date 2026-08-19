package com.devanshedutech.repository;

import com.devanshedutech.model.Batch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface BatchRepository extends JpaRepository<Batch, String> {

    List<Batch> findAllByOrderByStartDateAsc();

    /** Batches a counsellor can still offer, soonest first. */
    @Query("select b from Batch b where b.startDate >= :today and b.status in ('PLANNED','OPEN') "
         + "order by b.startDate asc")
    List<Batch> findUpcoming(@Param("today") LocalDate today);

    /** The next intake for one course, which is what day twelve of the ladder asks for. */
    @Query("select b from Batch b where b.courseId = :courseId and b.startDate >= :today "
         + "and b.status in ('PLANNED','OPEN') order by b.startDate asc")
    List<Batch> findUpcomingForCourse(@Param("courseId") String courseId, @Param("today") LocalDate today);

    long countByStatus(String status);
}
