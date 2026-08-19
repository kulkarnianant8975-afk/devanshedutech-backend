package com.devanshedutech.service;

import com.devanshedutech.dto.LeadDTOs.LeadRequest;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.crm.LeadSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatLeadCaptureTest {

    private LeadCaptureService capture;
    private ChatLeadCapture chat;

    @BeforeEach
    void setUp() {
        capture = mock(LeadCaptureService.class);
        when(capture.capture(any(), any())).thenAnswer(i -> {
            LeadRequest r = i.getArgument(0);
            return new LeadCaptureService.Captured(
                    Lead.builder().id("l1").fullName(r.getFullName())
                            .mobileNumber(r.getMobileNumber()).notes(r.getNotes()).build(), false);
        });
        chat = new ChatLeadCapture(capture);
    }

    private LeadRequest captured() {
        ArgumentCaptor<LeadRequest> c = ArgumentCaptor.forClass(LeadRequest.class);
        verify(capture).capture(c.capture(), any());
        return c.getValue();
    }

    @ParameterizedTest
    @DisplayName("a number is recognised however the student types it")
    @CsvSource({
            "'my number is 9876543210',                9876543210",
            "'call me on +91 98765 43210',             9876543210",
            "'98765-43210',                            9876543210",
            "'+919876543210 please',                   9876543210",
            "'Contact: 91 98765 43210',                9876543210",
            "'8669880738 is my whatsapp',              8669880738",
    })
    void mobileNumbersAreFound(String message, String expected) {
        assertEquals(Optional.of(expected), chat.findMobile(message));
    }

    @ParameterizedTest
    @DisplayName("things that are not phone numbers are left alone")
    @ValueSource(strings = {
            "the fee is 45000 rupees",
            "I am in 12th standard",
            "my roll number is 123456789012345",
            "is the course 6 months or 9 months",
            "call me",
            "1234567890",
    })
    void nonNumbersAreIgnored(String message) {
        // A false capture creates a lead nobody can call and a counsellor's wasted morning.
        assertTrue(chat.findMobile(message).isEmpty(), message);
    }

    @Test
    @DisplayName("a number in the chat becomes a lead the counsellor can see")
    void aNumberBecomesALead() {
        assertTrue(chat.capture("I'm Rohit Deshmukh, my number is 9876543210",
                List.of("what is the fee for data analytics")).isPresent());

        LeadRequest r = captured();
        assertEquals("Rohit Deshmukh", r.getFullName());
        assertEquals("9876543210", r.getMobileNumber());
        assertEquals(LeadSource.WEBSITE_CHATBOT.name(), r.getSource());
        assertTrue(r.getNotes().contains("data analytics"),
                "the counsellor should open the call knowing what they asked about");
    }

    @ParameterizedTest
    @DisplayName("a name is picked up from the way people actually write it")
    @CsvSource({
            "'my name is priya kulkarni',        Priya Kulkarni",
            "'I am Omkar',                       Omkar",
            "'this is Sneha here',               Sneha Here",
            "'Name: Amit Deshpande',             Amit Deshpande",
    })
    void namesAreFound(String message, String expected) {
        assertEquals(Optional.of(expected), chat.findName(message));
    }

    @ParameterizedTest
    @DisplayName("a phrase that only looks like a name is not treated as one")
    @ValueSource(strings = {
            "I am interested in the course",
            "I am looking for a job",
            "this is the fee right",
            "I am from Parbhani",
    })
    void falseNamesAreRejected(String message) {
        assertTrue(chat.findName(message).isEmpty(), message);
    }

    @Test
    @DisplayName("with no name given, the lead says so rather than inventing one")
    void anUnnamedEnquiryIsLabelledHonestly() {
        // A counsellor ringing an unnamed enquiry asks for the name in the first sentence. One
        // greeting a student by the wrong name has already lost them.
        chat.capture("9876543210", List.of());
        assertEquals("Chatbot enquiry", captured().getFullName());
    }

    @Test
    @DisplayName("a name given earlier in the conversation is still used")
    void namesAreRememberedAcrossTheConversation() {
        chat.capture("ok my number is 9876543210",
                List.of("hi", "my name is Sneha Patil", "what are the timings"));
        assertEquals("Sneha Patil", captured().getFullName());
    }

    @Test
    @DisplayName("a message with no number captures nothing")
    void ordinaryMessagesCaptureNothing() {
        assertTrue(chat.capture("what are the batch timings", List.of()).isEmpty());
        verify(capture, never()).capture(any(), any());
    }

    @Test
    @DisplayName("a capture that fails does not become the student's problem")
    void captureFailureIsSwallowed() {
        // The student asked a question. They get their answer whether or not we could file them.
        // doThrow, not when(...).thenThrow: the latter would run the existing stub with nulls.
        doThrow(new RuntimeException("database is down")).when(capture).capture(any(), any());
        assertTrue(chat.capture("9876543210", List.of()).isEmpty());
    }
}
