package com.devanshedutech.service;

import com.devanshedutech.dto.LeadDTOs.LeadRequest;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.crm.LeadSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a chatbot conversation into a lead once the student leaves a number.
 *
 * <p>The chatbot asks for a phone number when someone seems seriously interested, and until now
 * that number went nowhere at all: the student typed it, the bot thanked them, and no counsellor
 * ever heard about it. WEBSITE_CHATBOT existed as a source with nothing able to produce one.</p>
 */
@Slf4j
@Service
public class ChatLeadCapture {

    /**
     * An Indian mobile number, with or without the country code and the usual spacing.
     *
     * <p>Anchored on a 6-9 first digit and bounded so it does not match the middle of a longer
     * string of digits. A fee or a date cannot be mistaken for one: fees here are four or five
     * digits, and no course costs 98,76,543,210 rupees.</p>
     */
    private static final Pattern MOBILE = Pattern.compile(
            "(?<!\\d)(?:\\+?91[\\s-]?)?([6-9]\\d{4})[\\s-]?(\\d{5})(?!\\d)");

    /** "my name is Rohit", "I am Rohit Deshmukh", "this is Priya" — the ways people actually say it. */
    private static final Pattern NAME = Pattern.compile(
            "(?:my name is|i am|i'm|im|this is|name[:\\-]?)\\s+([A-Za-z][A-Za-z.'-]*(?:\\s+[A-Za-z][A-Za-z.'-]*){0,2})",
            Pattern.CASE_INSENSITIVE);

    /** Words that follow those phrases without being anybody's name. */
    private static final List<String> NOT_NAMES = List.of(
            "interested", "looking", "asking", "from", "a", "an", "the", "student", "here",
            "just", "not", "sure", "confused", "planning", "thinking", "in", "doing", "studying");

    private final LeadCaptureService capture;

    public ChatLeadCapture(LeadCaptureService capture) {
        this.capture = capture;
    }

    /**
     * Captures a lead if this message contains a usable phone number.
     *
     * <p>Returns empty when there is no number, which is most messages. Capture is never allowed
     * to break the conversation: a student asking about fees must get their answer whether or not
     * anything could be recorded, so the caller treats a failure here as nothing more than a
     * missed capture.</p>
     */
    public Optional<Lead> capture(String message, List<String> conversation) {
        String phone = findMobile(message).orElse(null);
        if (phone == null) return Optional.empty();

        String name = findName(message)
                .or(() -> conversation.stream().map(this::findName)
                        .filter(Optional::isPresent).map(Optional::get).findFirst())
                // Guessing badly is worse than not guessing. A counsellor ringing an unnamed
                // enquiry asks for the name in the first sentence; one greeting a student by
                // the wrong name has already lost them.
                .orElse("Chatbot enquiry");

        LeadRequest request = LeadRequest.builder()
                .fullName(name)
                .mobileNumber(phone)
                .source(LeadSource.WEBSITE_CHATBOT.name())
                .notes(transcriptNote(conversation, message))
                .build();

        try {
            return Optional.of(capture.capture(request, LeadSource.WEBSITE_CHATBOT).lead());
        } catch (RuntimeException e) {
            log.warn("Chatbot gave a number that could not be captured as a lead: {}", e.getMessage());
            return Optional.empty();
        }
    }

    Optional<String> findMobile(String message) {
        if (message == null) return Optional.empty();
        Matcher m = MOBILE.matcher(message);
        return m.find() ? Optional.of(m.group(1) + m.group(2)) : Optional.empty();
    }

    Optional<String> findName(String message) {
        if (message == null) return Optional.empty();
        Matcher m = NAME.matcher(message);
        while (m.find()) {
            String candidate = m.group(1).trim();
            String firstWord = candidate.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
            if (NOT_NAMES.contains(firstWord)) continue;
            return Optional.of(titleCase(candidate));
        }
        return Optional.empty();
    }

    /**
     * The last few things the student said, so the counsellor opens the call knowing what they
     * asked about rather than starting from nothing.
     */
    private String transcriptNote(List<String> conversation, String latest) {
        StringBuilder sb = new StringBuilder("Came through the website chatbot. They said:\n");
        conversation.stream().skip(Math.max(0, conversation.size() - 4))
                .forEach(line -> sb.append("- ").append(trim(line)).append('\n'));
        sb.append("- ").append(trim(latest));
        return sb.toString();
    }

    private String trim(String s) {
        String t = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        return t.length() <= 200 ? t : t.substring(0, 197) + "…";
    }

    private String titleCase(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String word : raw.split("\\s+")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0)))
              .append(word.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return sb.toString();
    }
}
