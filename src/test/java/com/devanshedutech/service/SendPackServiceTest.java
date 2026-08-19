package com.devanshedutech.service;

import com.devanshedutech.channel.AiSensyChannel;
import com.devanshedutech.channel.ManualWhatsAppChannel;
import com.devanshedutech.channel.WhatsAppSender;
import com.devanshedutech.model.Asset;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.SendPack;
import com.devanshedutech.repository.AssetRepository;
import com.devanshedutech.repository.LeadActivityRepository;
import com.devanshedutech.model.Course;
import com.devanshedutech.repository.BatchRepository;
import com.devanshedutech.repository.CourseRepository;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.repository.SendPackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SendPackServiceTest {

    private SendPackRepository packs;
    private AssetRepository assets;
    private LeadRepository leads;
    private CourseRepository courses;
    private SendPackService service;

    @BeforeEach
    void setUp() {
        packs = mock(SendPackRepository.class);
        assets = mock(AssetRepository.class);
        leads = mock(LeadRepository.class);
        courses = mock(CourseRepository.class);
        BatchRepository batches = mock(BatchRepository.class);
        when(batches.findUpcomingForCourse(any(), any())).thenReturn(List.of());
        when(batches.findUpcoming(any())).thenReturn(List.of());
        when(courses.findAll()).thenReturn(List.of(
                Course.builder().id("c9").name("Gen AI").build()));
        LeadActivityRepository activities = mock(LeadActivityRepository.class);
        when(leads.save(any())).thenAnswer(i -> i.getArgument(0));
        when(activities.save(any())).thenAnswer(i -> i.getArgument(0));

        when(packs.findByKey("guidance")).thenReturn(Optional.of(SendPack.builder()
                .id("p1").key("guidance").name("Post-call guidance pack").active(true)
                .coverTemplate("Great speaking with you, {{first_name}}! Here is everything for "
                             + "{{course}}.\n\n— {{counsellor}}")
                .assetKeys("syllabus,demo_link").build()));

        when(assets.findByKey("syllabus")).thenReturn(Optional.of(Asset.builder()
                .id("a1").key("syllabus").name("{{course}} — syllabus and fees").type("PDF")
                .url("/api/public/brochure/download/{{course_id}}").tracked(true).active(true).build()));
        when(assets.findByKey("demo_link")).thenReturn(Optional.of(Asset.builder()
                .id("a2").key("demo_link").name("Book your free demo").type("LINK")
                .url("/contact").tracked(true).active(true).build()));

        // No API key, so the manual hand-off is active — the same path an institute runs on
        // before connecting a provider.
        AiSensyChannel provider = new AiSensyChannel(new org.springframework.boot.web.client.RestTemplateBuilder());
        org.springframework.test.util.ReflectionTestUtils.setField(provider, "apiKey", "");
        WhatsAppSender sender = new WhatsAppSender(provider, new ManualWhatsAppChannel());
        org.springframework.test.util.ReflectionTestUtils.setField(sender, "publicBaseUrl", "");

        service = new SendPackService(packs, assets, leads, courses, batches,
                new LeadLifecycleService(leads, activities, TestCalendars.openEveryDay()), sender);
    }

    private Lead lead(LocalDateTime lastInbound) {
        return Lead.builder().id("l1").fullName("Omkar Bhosale").mobileNumber("+91 88765 43210")
                .courseInterested("Gen AI").courseId("c9").cityName("Selu")
                .lastInboundAt(lastInbound).callAttempts(0).updatesOnly(false).optedOut(false)
                .createdAt(LocalDateTime.now().minusDays(1)).build();
    }

    @Test
    @DisplayName("the message is filled in with the student's own details")
    void placeholdersAreFilled() {
        var p = service.prepare(lead(LocalDateTime.now().minusHours(2)), "guidance", "Aditya Jadhav");

        assertTrue(p.message().contains("Omkar"), "first name only, not the full name");
        assertFalse(p.message().contains("Bhosale"));
        assertTrue(p.message().contains("Gen AI"));
        assertTrue(p.message().contains("Aditya Jadhav"));
        assertFalse(p.message().contains("{{"), "no placeholder may survive into a student's message");
    }

    @Test
    @DisplayName("an asset's course is resolved too, so one row covers every course")
    void assetUrlsAreResolved() {
        var p = service.prepare(lead(LocalDateTime.now().minusHours(1)), "guidance", "Aditya");
        var syllabus = p.assets().get(0);

        assertEquals("Gen AI — syllabus and fees", syllabus.name());
        assertEquals("/api/public/brochure/download/c9", syllabus.url());
        assertFalse(syllabus.url().contains("{{"));
    }

    @Test
    @DisplayName("the reply window is reported while it is open")
    void windowOpen() {
        var p = service.prepare(lead(LocalDateTime.now().minusHours(2)), "guidance", "Aditya");

        assertTrue(p.freeReplyOpen());
        assertNotNull(p.replyWindowMinutesLeft());
        assertTrue(p.replyWindowMinutesLeft() > 21 * 60);
        assertTrue(p.note().toLowerCase().contains("attachment"));
    }

    @Test
    @DisplayName("a student silent for a day is flagged as template-only, not simply refused")
    void windowClosed() {
        var p = service.prepare(lead(LocalDateTime.now().minusHours(30)), "guidance", "Aditya");

        assertFalse(p.freeReplyOpen());
        assertNull(p.replyWindowMinutesLeft());
        assertTrue(p.note().contains("24 hours"),
                "the counsellor must be told why, or they will think the software is broken");
    }

    @Test
    @DisplayName("a student who has never messaged has no open window")
    void neverMessaged() {
        assertFalse(service.prepare(lead(null), "guidance", "Aditya").freeReplyOpen());
    }

    @Test
    @DisplayName("the WhatsApp link carries the country code and the encoded message")
    void whatsappLinkIsUsable() {
        var p = service.prepare(lead(LocalDateTime.now().minusHours(1)), "guidance", "Aditya");

        assertNotNull(p.whatsappUrl());
        assertTrue(p.whatsappUrl().startsWith("https://wa.me/918876543210?text="));
        assertTrue(p.whatsappUrl().contains("Omkar"));
        assertFalse(p.whatsappUrl().contains(" "), "the message must be encoded, not raw");
    }

    @Test
    @DisplayName("recording a send writes it to the timeline as sent, not delivered")
    void sendIsRecordedHonestly() {
        Lead l = lead(LocalDateTime.now().minusHours(1));
        service.recordSent(l, "guidance", List.of("syllabus", "demo_link"),
                new LeadLifecycleService.Actor("u1", "Aditya"));

        assertNotNull(l.getFirstRespondedAt(), "sending is the first response if there was none");
        assertTrue(l.getLastTouchNote().contains("Post-call guidance pack"));
    }

    @Test
    @DisplayName("a course the catalogue does not know falls back to the general brochure")
    void unmatchedCourseDoesNotProduceABrokenLink() {
        // Lead courses are free text. A path ending in an empty id is a download that 404s, and
        // sending a student a broken link is worse than sending the general brochure.
        Lead l = lead(LocalDateTime.now().minusHours(1));
        l.setCourseId(null);
        l.setCourseInterested("Something we do not offer");

        var p = service.prepare(l, "guidance", "Aditya");
        var syllabus = p.assets().get(0);

        assertEquals("/api/public/brochure/download", syllabus.url());
        assertFalse(syllabus.url().endsWith("/"), "an empty course id must never reach the URL");
    }

    @Test
    @DisplayName("a course name that does match the catalogue resolves to that course's brochure")
    void matchedCourseUsesItsOwnBrochure() {
        Lead l = lead(LocalDateTime.now().minusHours(1));
        l.setCourseId(null);          // only the typed name is known
        l.setCourseInterested("gen ai");   // and the casing does not match

        assertEquals("/api/public/brochure/download/c9",
                service.prepare(l, "guidance", "Aditya").assets().get(0).url());
    }

    @Test
    @DisplayName("a template using a placeholder nothing can fill is refused")
    void unknownPlaceholdersAreRefused() {
        // Left in, a student receives literal braces in the middle of a sentence — the sort of
        // thing nobody notices until it has gone to two hundred people.
        when(packs.save(any())).thenAnswer(i -> i.getArgument(0));

        var e = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.update("guidance", null, null,
                        "Hi {{first_name}}, your {{discount_code}} is ready", null, null));

        assertEquals(400, e.getStatusCode().value());
        assertTrue(e.getReason().contains("discount_code"));
        assertTrue(e.getReason().contains("first_name"), "the message should list what is available");
    }

    @Test
    @DisplayName("every placeholder the editor offers is one the sender actually fills in")
    void advertisedPlaceholdersAreReal() {
        // The editor lists these to a person writing a message. Advertising one that is never
        // substituted would be worse than not offering it at all.
        Lead l = lead(LocalDateTime.now().minusHours(1));
        String everyOne = SendPackService.PLACEHOLDERS.keySet().stream()
                .map(k -> "{{" + k + "}}").reduce("", (a, b) -> a + " " + b);

        when(packs.findByKey("all")).thenReturn(Optional.of(
                com.devanshedutech.model.SendPack.builder()
                        .id("p2").key("all").name("All").active(true)
                        .coverTemplate(everyOne).assetKeys("").build()));

        assertFalse(service.prepare(l, "all", "Aditya").message().contains("{{"),
                "a placeholder the editor offers must never survive into a student's message");
    }

    @Test
    @DisplayName("a valid edit saves, including the attachment list")
    void validEditIsAccepted() {
        when(packs.save(any())).thenAnswer(i -> i.getArgument(0));
        var updated = service.update("guidance", "New name", "New situation",
                "Hi {{first_name}}, the {{batch}} starts soon.", List.of("syllabus"), true);

        assertEquals("New name", updated.getName());
        assertEquals(List.of("syllabus"), updated.assets());
    }

    @Test
    @DisplayName("an attachment that does not exist is refused rather than silently dropped")
    void unknownAttachmentIsRefused() {
        var e = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.update("guidance", null, null, null, List.of("no_such_file"), null));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    @DisplayName("an unknown pack is refused rather than sending an empty message")
    void unknownPackIsRefused() {
        when(packs.findByKey(anyString())).thenReturn(Optional.empty());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.prepare(lead(null), "nonsense", "Aditya"));
    }
}
