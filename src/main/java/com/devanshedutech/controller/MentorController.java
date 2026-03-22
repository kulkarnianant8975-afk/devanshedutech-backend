package com.devanshedutech.controller;

import com.devanshedutech.dto.MentorDTOs;
import com.devanshedutech.service.MentorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/mentors")
@RequiredArgsConstructor
public class MentorController {
    private final MentorService mentorService;

    @GetMapping
    public List<MentorDTOs.MentorResponse> getAllMentors() {
        return mentorService.getAllMentors();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('admin')")
    public MentorDTOs.MentorResponse createMentor(@RequestBody MentorDTOs.MentorRequest request) {
        return mentorService.createMentor(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    public MentorDTOs.MentorResponse updateMentor(@PathVariable String id, @RequestBody MentorDTOs.MentorRequest request) {
        return mentorService.updateMentor(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('admin')")
    public void deleteMentor(@PathVariable String id) {
        mentorService.deleteMentor(id);
    }
}
