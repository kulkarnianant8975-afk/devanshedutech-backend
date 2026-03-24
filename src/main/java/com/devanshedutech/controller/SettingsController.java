package com.devanshedutech.controller;

import com.devanshedutech.model.AppSetting;
import com.devanshedutech.model.Course;
import com.devanshedutech.repository.AppSettingRepository;
import com.devanshedutech.repository.CourseRepository;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class SettingsController {
    
    private final AppSettingRepository appSettingRepository;
    private final CourseRepository courseRepository;

    public SettingsController(AppSettingRepository repository, CourseRepository courseRepository) {
        this.appSettingRepository = repository;
        this.courseRepository = courseRepository;
    }

    @GetMapping("/public/brochure")
    public ResponseEntity<?> getBrochureInfo() {
        return appSettingRepository.findById("GLOBAL_BROCHURE")
                .map(setting -> ResponseEntity.ok(Map.of("downloadUrl", "/api/public/brochure/download")))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/public/brochure/download")
    public ResponseEntity<Resource> downloadBrochure() {
        return downloadFile("GLOBAL_BROCHURE", "Devansh_EduTech_Brochure.pdf");
    }

    @PostMapping("/settings/brochure/upload")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<?> uploadBrochureFile(@RequestParam("file") MultipartFile file) {
        return saveFile("GLOBAL_BROCHURE", file);
    }

    @GetMapping("/public/brochure/{courseId}")
    public ResponseEntity<?> getCourseBrochureInfo(@PathVariable String courseId) {
        return appSettingRepository.findById("COURSE_BROCHURE_" + courseId)
                .map(setting -> ResponseEntity.ok(Map.of("downloadUrl", "/api/public/brochure/download/" + courseId)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/public/brochure/download/{courseId}")
    public ResponseEntity<Resource> downloadCourseBrochure(@PathVariable String courseId) {
        String fileName = "Course_Brochure.pdf";
        Optional<Course> course = courseRepository.findById(courseId);
        if (course.isPresent()) {
            fileName = course.get().getName().replace(" ", "_") + "_Brochure.pdf";
        }
        return downloadFile("COURSE_BROCHURE_" + courseId, fileName);
    }

    @PostMapping("/settings/brochure/upload/{courseId}")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<?> uploadCourseBrochureFile(@PathVariable String courseId, @RequestParam("file") MultipartFile file) {
        return saveFile("COURSE_BROCHURE_" + courseId, file);
    }

    private ResponseEntity<Resource> downloadFile(String key, String downloadName) {
        Optional<AppSetting> setting = appSettingRepository.findById(key);
        if (setting.isPresent()) {
            try {
                String base64Content = setting.get().getSettingValue();
                if (base64Content != null && base64Content.startsWith("data:application/pdf;base64,")) {
                    base64Content = base64Content.substring("data:application/pdf;base64,".length());
                }
                
                byte[] pdfBytes;
                try {
                    pdfBytes = java.util.Base64.getDecoder().decode(base64Content);
                } catch (IllegalArgumentException e) {
                    // Not a valid base64 - likely an old filename from disk-based storage
                    // Since Render is ephemeral, these old files are likely gone anyway.
                    return ResponseEntity.notFound().build();
                }
                
                Resource resource = new org.springframework.core.io.ByteArrayResource(pdfBytes);
                
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"")
                        .body(resource);
            } catch (Exception e) {
                return ResponseEntity.internalServerError().build();
            }
        }
        return ResponseEntity.notFound().build();
    }

    private ResponseEntity<?> saveFile(String key, MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
            }
            
            byte[] fileBytes = file.getBytes();
            String base64Content = "data:application/pdf;base64," + java.util.Base64.getEncoder().encodeToString(fileBytes);
            
            AppSetting setting = AppSetting.builder()
                    .settingKey(key)
                    .settingValue(base64Content)
                    .build();
            appSettingRepository.save(setting);
            
            return ResponseEntity.ok(Map.of("message", "Brochure updated successfully stored in database"));
        } catch (Exception e) {
            e.printStackTrace(); // Log the stack trace in production logs
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not store file: " + e.getMessage()));
        }
    }
}
