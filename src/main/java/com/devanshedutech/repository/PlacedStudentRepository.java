package com.devanshedutech.repository;

import com.devanshedutech.model.PlacedStudent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlacedStudentRepository extends JpaRepository<PlacedStudent, String> {
}
