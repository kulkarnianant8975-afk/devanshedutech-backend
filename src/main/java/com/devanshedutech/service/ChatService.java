package com.devanshedutech.service;

import com.devanshedutech.model.Course;
import com.devanshedutech.repository.CourseRepository;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatService {

    private final CourseRepository courseRepository;
    private final RestTemplate restTemplate;

    @Value("${spring.gemini.api-key:#{null}}")
    private String apiKey;

    @Value("${app.institute.name:Devansh Edu-Tech Classes}")
    private String instituteName;

    @Value("${app.institute.city:Parbhani}")
    private String city;

    @Value("${app.institute.state:Maharashtra}")
    private String state;

    @Value("${app.institute.phone:}")
    private String phone;

    public ChatService(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
        this.restTemplate = new RestTemplate();
    }

    public String getAiResponse(String message, List<Map<String, Object>> history) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("GEMINI_API_KEY is not configured.");
        }

        List<Course> courses = courseRepository.findAll();
        String coursesContext = courses.stream()
                .map(c -> "- " + c.getName() + ": Duration " + c.getDuration() + ", Fee " + c.getPrice() + ". " + c.getDescription())
                .collect(Collectors.joining("\n"));

        // The subjects taught are read from the catalogue rather than written here. A second
        // hardcoded list is a list that goes stale: this one claimed Full Stack, Data Science
        // and Software Testing regardless of what the institute had actually been running.
        String subjects = courses.stream()
                .map(Course::getName)
                .filter(n -> n != null && !n.isBlank())
                .collect(Collectors.joining(", "));

        String systemInstructionText = "You are \"Devansh\", the AI academic counsellor for "
                + instituteName + ". Help prospective students understand the courses and work "
                + "out which one fits them.\n\n"
                + "### About the institute:\n"
                + "- Name: " + instituteName + "\n"
                + "- Location: " + city + ", " + state + ", India\n"
                + (subjects.isBlank() ? "" : "- Courses offered: " + subjects + "\n")
                + "- Approach: practical, project-based training aimed at getting students hired\n"
                + (phone == null || phone.isBlank() ? ""
                        : "- To enrol or speak to a person: " + phone + " (WhatsApp or call)\n")
                + "\n### Course details:\n"
                + (coursesContext.isBlank()
                        ? "No course details are loaded. Do not invent any — offer to put the "
                          + "student in touch with a counsellor instead.\n"
                        : coursesContext + "\n")
                + "\n### How to answer:\n"
                + "1. Be warm and straightforward. Students here are often deciding between an "
                + "institute and another year of college, so treat the question seriously.\n"
                + "2. Only state fees, durations and dates that appear above. If you do not know "
                + "something — a batch date, a discount, a placement figure — say so and offer "
                + "to have a counsellor confirm it. Never estimate a fee or invent a "
                + "placement statistic.\n"
                + "3. Recommend the course that actually suits what they describe, even when it "
                + "is the cheaper one. A student steered into the wrong course drops out, and "
                + "that costs the institute more than the fee difference.\n"
                + "4. Ask for their name and number once they seem seriously interested, so a "
                + "counsellor can follow up properly.\n"
                + "5. Keep replies short. Use bullet points for lists of courses or fees.";

        GeminiRequest geminiRequest = new GeminiRequest();
        List<Content> contents = new ArrayList<>();
        
        // Prepend system instruction as the first user message for max compatibility
        contents.add(new Content("user", List.of(new Part(systemInstructionText))));
        contents.add(new Content("model", List.of(new Part("Understood. I am Devansh from Devansh Edu-Tech Classes. I'm ready to assist students with their academic and career inquiries."))));

        if (history != null) {
            for (Map<String, Object> item : history) {
                String role = (String) item.get("role");
                List<Map<String, String>> parts = (List<Map<String, String>>) item.get("parts");
                if (parts != null && !parts.isEmpty()) {
                    contents.add(new Content(role, List.of(new Part(parts.get(0).get("text")))));
                }
            }
        }
        contents.add(new Content("user", List.of(new Part(message))));
        geminiRequest.setContents(contents);

        // The key travels in a header, never the query string. A RestTemplate failure
        // (timeout, connect error) puts the request URL into the exception message, so a
        // key in the URL can be echoed to the caller by any handler that logs or returns it.
        String url = "https://generativelanguage.googleapis.com/v1/models/gemini-2.5-flash:generateContent";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);
        HttpEntity<GeminiRequest> entity = new HttpEntity<>(geminiRequest, headers);

        try {
            Map<String, Object> response = restTemplate.postForObject(url, entity, Map.class);
            if (response != null && response.containsKey("candidates")) {
                List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
                if (!candidates.isEmpty()) {
                    Map<String, Object> firstCandidate = candidates.get(0);
                    // A safety-blocked candidate has a finishReason but no content/parts.
                    Map<String, Object> content = (Map<String, Object>) firstCandidate.get("content");
                    if (content != null) {
                        List<Map<String, String>> parts = (List<Map<String, String>>) content.get("parts");
                        if (parts != null && !parts.isEmpty()) {
                            return parts.get(0).get("text");
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Log server-side with the full cause; never surface it to the caller.
            log.error("Gemini request failed", e);
            throw new ChatUnavailableException();
        }

        return "I'm sorry, I couldn't generate a response. Please try rephrasing your question.";
    }

    /** Signals chat failure without carrying any upstream detail into the response body. */
    public static class ChatUnavailableException extends RuntimeException {
        public ChatUnavailableException() {
            super("Chat is temporarily unavailable.");
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeminiRequest {
        private List<Content> contents;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Content {
        private String role;
        private List<Part> parts;
        public Content(List<Part> parts) { this.parts = parts; }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Part {
        private String text;
    }
}
