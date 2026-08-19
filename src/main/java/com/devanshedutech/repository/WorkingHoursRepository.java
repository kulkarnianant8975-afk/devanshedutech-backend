package com.devanshedutech.repository;

import com.devanshedutech.model.WorkingHours;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.DayOfWeek;

public interface WorkingHoursRepository extends JpaRepository<WorkingHours, DayOfWeek> {
}
