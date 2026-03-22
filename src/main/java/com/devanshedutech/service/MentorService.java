package com.devanshedutech.service;

import com.devanshedutech.dto.MentorDTOs;
import com.devanshedutech.model.Mentor;
import com.devanshedutech.repository.MentorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MentorService {
    private final MentorRepository mentorRepository;

    public List<MentorDTOs.MentorResponse> getAllMentors() {
        return mentorRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public MentorDTOs.MentorResponse createMentor(MentorDTOs.MentorRequest request) {
        Mentor mentor = Mentor.builder()
                .name(request.getName())
                .role(request.getRole())
                .description(request.getDescription())
                .imageUrl(request.getImageUrl())
                .linkedinUrl(request.getLinkedinUrl())
                .build();
        return mapToResponse(mentorRepository.save(mentor));
    }

    @Transactional
    public MentorDTOs.MentorResponse updateMentor(String id, MentorDTOs.MentorRequest request) {
        Mentor mentor = mentorRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Mentor not found"));
        mentor.setName(request.getName());
        mentor.setRole(request.getRole());
        mentor.setDescription(request.getDescription());
        mentor.setImageUrl(request.getImageUrl());
        mentor.setLinkedinUrl(request.getLinkedinUrl());
        return mapToResponse(mentorRepository.save(mentor));
    }

    @Transactional
    public void deleteMentor(String id) {
        mentorRepository.deleteById(id);
    }

    private MentorDTOs.MentorResponse mapToResponse(Mentor mentor) {
        return MentorDTOs.MentorResponse.builder()
                .id(mentor.getId())
                .name(mentor.getName())
                .role(mentor.getRole())
                .description(mentor.getDescription())
                .imageUrl(mentor.getImageUrl())
                .linkedinUrl(mentor.getLinkedinUrl())
                .createdAt(mentor.getCreatedAt())
                .build();
    }
}
