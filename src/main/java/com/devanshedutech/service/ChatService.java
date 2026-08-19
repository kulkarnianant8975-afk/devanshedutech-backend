package com.devanshedutech.service;

import com.devanshedutech.ai.GeminiClient;
import com.devanshedutech.model.Course;
import com.devanshedutech.repository.CourseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatService {

    private final CourseRepository courseRepository;
    private final GeminiClient gemini;

    @Value("${app.institute.name:Devansh Edu-Tech Classes}")
    private String instituteName;

    @Value("${app.institute.city:Parbhani}")
    private String city;

    @Value("${app.institute.state:Maharashtra}")
    private String state;

    @Value("${app.institute.phone:}")
    private String phone;

    public ChatService(CourseRepository courseRepository, GeminiClient gemini) {
        this.courseRepository = courseRepository;
        this.gemini = gemini;
    }

    public String getAiResponse(String message, List<Map<String, Object>> history) {
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

        try {
            return gemini.chat(systemInstructionText, history, message);
        } catch (GeminiClient.AiUnavailableException e) {
            // The client has already logged the cause. Nothing from upstream is repeated here,
            // because that text carries the request and therefore the key.
            throw new ChatUnavailableException();
        }
    }

    /** Signals chat failure without carrying any upstream detail into the response body. */
    public static class ChatUnavailableException extends RuntimeException {
        public ChatUnavailableException() {
            super("Chat is temporarily unavailable.");
        }
    }

}
