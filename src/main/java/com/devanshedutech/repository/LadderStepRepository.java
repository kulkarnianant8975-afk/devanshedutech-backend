package com.devanshedutech.repository;

import com.devanshedutech.model.LadderStep;
import com.devanshedutech.model.crm.Grade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LadderStepRepository extends JpaRepository<LadderStep, String> {

    List<LadderStep> findByGradeOrderByStepNoAsc(Grade grade);

    List<LadderStep> findAllByOrderByGradeAscStepNoAsc();

    long countByGrade(Grade grade);
}
