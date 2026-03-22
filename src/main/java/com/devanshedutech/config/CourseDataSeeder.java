package com.devanshedutech.config;

import com.devanshedutech.model.Course;
import com.devanshedutech.repository.CourseRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CourseDataSeeder implements CommandLineRunner {

    private final CourseRepository courseRepository;

    public CourseDataSeeder(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    @Override
    public void run(String... args) {
        if (courseRepository.count() == 0) {
            List<Course> defaultCourses = List.of(
                Course.builder()
                    .id("1")
                    .name("Foundation using C, C++")
                    .duration("2 Months")
                    .price("₹4,999")
                    .description("Master the fundamentals of programming with C and C++. Perfect for beginners to build a strong logic base.")
                    .category("Programming")
                    .image("https://images.unsplash.com/photo-1629739947391-74708e1b9724?auto=format&fit=crop&q=80&w=800")
                    .build(),
                Course.builder()
                    .id("2")
                    .name("Full Stack Web Development")
                    .duration("6 Months")
                    .price("₹24,999")
                    .description("Become a professional web developer. Learn HTML, CSS, JS, React, Node.js, and MongoDB.")
                    .category("Web Development")
                    .image("https://images.unsplash.com/photo-1627398242454-45a1465c2479?auto=format&fit=crop&q=80&w=800")
                    .build(),
                Course.builder()
                    .id("3")
                    .name("Full Stack Python Development")
                    .duration("5 Months")
                    .price("₹19,999")
                    .description("Learn Python from scratch to advanced levels, including Django/Flask for web development.")
                    .category("Python")
                    .image("https://images.unsplash.com/photo-1526379095098-d400fd0bf935?auto=format&fit=crop&q=80&w=800")
                    .build(),
                Course.builder()
                    .id("4")
                    .name("Full Stack Java Development")
                    .duration("6 Months")
                    .price("₹24,999")
                    .description("Master Java, Spring Boot, and Microservices to build enterprise-level applications.")
                    .category("Java")
                    .image("https://images.unsplash.com/photo-1517694712202-14dd9538aa97?auto=format&fit=crop&q=80&w=800")
                    .build(),
                Course.builder()
                    .id("5")
                    .name("Software Testing")
                    .duration("3 Months")
                    .price("₹12,999")
                    .description("Learn Manual and Automation testing using Selenium, Java, and TestNG.")
                    .category("Testing")
                    .image("https://images.unsplash.com/photo-1516321318423-f06f85e504b3?auto=format&fit=crop&q=80&w=800")
                    .build(),
                Course.builder()
                    .id("6")
                    .name("Digital Marketing")
                    .duration("3 Months")
                    .price("₹9,999")
                    .description("Master SEO, SEM, Social Media Marketing, and Content Strategy.")
                    .category("Marketing")
                    .image("https://images.unsplash.com/photo-1460925895917-afdab827c52f?auto=format&fit=crop&q=80&w=800")
                    .build(),
                Course.builder()
                    .id("7")
                    .name("Soft Skills")
                    .duration("1 Month")
                    .price("₹3,999")
                    .description("Enhance your personality, time management, and professional ethics.")
                    .category("Professional")
                    .image("https://images.unsplash.com/photo-1522202176988-66273c2fd55f?auto=format&fit=crop&q=80&w=800")
                    .build(),
                Course.builder()
                    .id("8")
                    .name("Communication Skills")
                    .duration("1 Month")
                    .price("₹3,999")
                    .description("Improve your verbal and non-verbal communication for better career growth.")
                    .category("Professional")
                    .image("https://images.unsplash.com/photo-1573164713714-d95e436ab8d6?auto=format&fit=crop&q=80&w=800")
                    .build()
            );

            courseRepository.saveAll(defaultCourses);
            System.out.println("✅ Seeded " + defaultCourses.size() + " default courses into the database.");
        } else {
            System.out.println("ℹ️ Courses already exist in the database. Skipping seed.");
        }
    }
}
