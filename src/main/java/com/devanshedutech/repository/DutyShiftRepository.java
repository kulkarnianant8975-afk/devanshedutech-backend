package com.devanshedutech.repository;

import com.devanshedutech.model.DutyShift;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.DayOfWeek;
import java.util.List;

public interface DutyShiftRepository extends JpaRepository<DutyShift, String> {
    List<DutyShift> findByDayOrderByStartsAtAsc(DayOfWeek day);
    List<DutyShift> findAllByOrderByDayAscStartsAtAsc();
    void deleteByUserId(String userId);
}
