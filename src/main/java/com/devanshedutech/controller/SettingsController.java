package com.devanshedutech.controller;

import com.devanshedutech.model.AppSetting;
import com.devanshedutech.model.Course;
import com.devanshedutech.repository.AppSettingRepository;
import com.devanshedutech.repository.CourseRepository;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class SettingsController {
    
    private final AppSettingRepository appSettingRepository;

    private final CourseRepository courseRepository;

    private final Path uploadDir = Paths.get("uploads/brochures");

    public SettingsController(AppSettingRepository repository, CourseRepository courseRepository) {
        this.appSettingRepository = repository;
        this.courseRepository = courseRepository;
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize upload directory", e);
        }
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
                Path filePath = uploadDir.resolve(setting.get().getSettingValue()).normalize();
                Resource resource = new UrlResource(filePath.toUri());
                if (resource.exists()) {
                    return ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_PDF)
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"")
                            .body(resource);
                }
            } catch (Exception e) {
                return ResponseEntity.internalServerError().build();
            }
        }
        return ResponseEntity.notFound().build();
    }

    private ResponseEntity<?> saveFile(String key, MultipartFile file) {
        try {
            String fileName = key + "_" + System.currentTimeMillis() + ".pdf";
            Path targetLocation = uploadDir.resolve(fileName);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

            AppSetting setting = AppSetting.builder()
                    .settingKey(key)
                    .settingValue(fileName)
                    .build();
            appSettingRepository.save(setting);
            
            return ResponseEntity.ok(Map.of("message", "Brochure updated successfully", "fileName", fileName));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not store file: " + e.getMessage()));
        }
    }
}
