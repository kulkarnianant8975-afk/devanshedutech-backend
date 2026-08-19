package com.devanshedutech.service;

import com.devanshedutech.ai.GeminiClient;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.LeadActivity;
import com.devanshedutech.model.crm.ActivityType;
import com.devanshedutech.model.crm.Grade;
import com.devanshedutech.repository.LeadActivityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LeadAssistantServiceTest {

    private GeminiClient gemini;
    private LeadActivityRepository activities;
    private LeadAssistantService assistant;

    @BeforeEach
    void setUp() {
        gemini = mock(GeminiClient.class);
        activities = mock(LeadActivityRepository.class);
        when(gemini.isConfigured()).thenReturn(true);
        when(activities.findByLeadIdOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
        assistant = new LeadAssistantService(gemini, activities);
    }

    private Lead lead() {
        return Lead.builder().id("l1").fullName("Omkar Bhosale")
                .mobileNumber("+91 98765 43210").email("omkar@example.com")
                .courseInterested("Data Analytics").cityName("Parbhani")
                .callAttempts(2).build();
    }

    private String promptSentToModel() {
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(gemini).complete(anyString(), user.capture());
        return user.getValue();
    }

    @Test
    @DisplayName("a suggestion is read back as a grade and a reason")
    void gradeAndReasoningAreParsed() {
        when(gemini.complete(anyString(), anyString()))
                .thenReturn("GRADE: HOT\nWHY: Asked for the fee and the next batch date on the call.");

        LeadAssistantService.GradeSuggestion s = assistant.suggestGrade(lead());
        assertEquals(Grade.HOT, s.getGrade());
        assertTrue(s.getReasoning().startsWith("Asked for the fee"));
    }

    @Test
    @DisplayName("a suggestion never changes the lead")
    void suggestionsAreNeverApplied() {
        // Grading drives the whole follow-up ladder. A model quietly regrading a student it
        // misread would change how often a real person gets contacted, for reasons nobody could
        // reconstruct afterwards.
        when(gemini.complete(anyString(), anyString())).thenReturn("GRADE: COLD\nWHY: Went quiet.");

        Lead lead = lead();
        lead.setGrade(Grade.HOT);
        LeadAssistantService.GradeSuggestion s = assistant.suggestGrade(lead);

        assertFalse(s.isApplied());
        assertEquals(Grade.HOT, lead.getGrade(), "the lead itself is untouched");
        verify(activities, never()).save(any());
    }

    @ParameterizedTest
    @DisplayName("an answer that is not a grade becomes no suggestion rather than a guess")
    @ValueSource(strings = {
            "GRADE: MAYBE\nWHY: hard to say",
            "GRADE: \nWHY: nothing",
            "I think this lead is quite promising overall.",
            "GRADE: LUKEWARM\nWHY: in between",
    })
    void unrecognisedGradesYieldNothing(String answer) {
        when(gemini.complete(anyString(), anyString())).thenReturn(answer);

        LeadAssistantService.GradeSuggestion s = assistant.suggestGrade(lead());
        assertNull(s.getGrade());
        assertNotNull(s.getReasoning(), "the counsellor still sees what it said and decides");
    }

    @Test
    @DisplayName("the grade is read whatever case and spacing come back")
    void parsingIsForgivingOfFormatting() {
        assertEquals(Grade.WARM, assistant.parseGrade("grade:  warm \nwhy: waiting on results").getGrade());
        assertEquals(Grade.COLD, assistant.parseGrade("GRADE:COLD\nWHY:no reply").getGrade());
    }

    @Test
    @DisplayName("the student's phone number and email are not sent to the model")
    void contactDetailsAreNotShared() {
        // They add nothing to any of these judgements, and there is no reason to hand a
        // student's contact details to a third party to be told that somebody sounds interested.
        when(gemini.complete(anyString(), anyString())).thenReturn("GRADE: WARM\nWHY: engaged");
        assistant.suggestGrade(lead());

        String prompt = promptSentToModel();
        assertFalse(prompt.contains("98765"), prompt);
        assertFalse(prompt.contains("omkar@example.com"), prompt);
        assertTrue(prompt.contains("Omkar Bhosale"), "but the name is needed to write a message");
    }

    @Test
    @DisplayName("the history is what the model actually judges on")
    void theTimelineIsSent() {
        when(activities.findByLeadIdOrderByCreatedAtDesc("l1")).thenReturn(List.of(
                LeadActivity.builder().id("a1").leadId("l1").type(ActivityType.CALL)
                        .summary("Guidance call").detail("Asked about the fee and EMI options")
                        .createdAt(LocalDateTime.of(2026, 8, 12, 15, 30)).build()));
        when(gemini.complete(anyString(), anyString())).thenReturn("GRADE: HOT\nWHY: asked about EMI");

        assistant.suggestGrade(lead());
        String prompt = promptSentToModel();
        assertTrue(prompt.contains("Guidance call"), prompt);
        assertTrue(prompt.contains("EMI options"), prompt);
    }

    @Test
    @DisplayName("an unworked lead is described as such rather than left blank")
    void anEmptyHistorySaysSo() {
        when(gemini.complete(anyString(), anyString())).thenReturn("GRADE: COLD\nWHY: nothing recorded");
        assistant.suggestGrade(lead());
        assertTrue(promptSentToModel().contains("Nobody has worked this lead"));
    }

    @Test
    @DisplayName("with no key configured, it says so instead of failing obscurely")
    void unconfiguredIsReportedPlainly() {
        when(gemini.isConfigured()).thenReturn(false);
        assertFalse(assistant.isAvailable());
    }

    @Test
    @DisplayName("a draft carries the counsellor's name and what it is meant to do")
    void draftsCarryTheirInstructions() {
        when(gemini.complete(anyString(), anyString())).thenReturn("Hi Omkar — ...");
        assistant.draftReply(lead(), "get them to confirm Saturday's demo", "Priya Kulkarni");

        String prompt = promptSentToModel();
        assertTrue(prompt.contains("Priya Kulkarni"));
        assertTrue(prompt.contains("confirm Saturday's demo"));
    }

    @Test
    @DisplayName("a draft with no stated purpose still asks for something useful")
    void draftsWithoutAnIntentStillWork() {
        when(gemini.complete(anyString(), anyString())).thenReturn("Hi Omkar — ...");
        assistant.draftReply(lead(), "   ", "Priya");
        assertTrue(promptSentToModel().contains("move the conversation forward"));
    }
}
