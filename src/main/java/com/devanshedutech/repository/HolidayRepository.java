package com.devanshedutech.repository;

import com.devanshedutech.model.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface HolidayRepository extends JpaRepository<Holiday, LocalDate> {
    List<Holiday> findByDayBetweenOrderByDayAsc(LocalDate from, LocalDate to);
    List<Holiday> findByDayGreaterThanEqualOrderByDayAsc(LocalDate from);
}
