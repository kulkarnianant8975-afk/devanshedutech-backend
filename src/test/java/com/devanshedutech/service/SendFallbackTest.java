package com.devanshedutech.service;

import com.devanshedutech.channel.ManualWhatsAppChannel;
import com.devanshedutech.channel.WhatsAppChannel;
import com.devanshedutech.channel.WhatsAppSender;
import com.devanshedutech.model.Asset;
import com.devanshedutech.model.Course;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.SendPack;
import com.devanshedutech.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * What happens to a counsellor when the provider will not send.
 *
 * <p>A provider fails for reasons that have nothing to do with the student in front of you: an
 * expired token, a lapsed billing account, an outage. On 2026-08-22 the Meta test token expired
 * — they last twenty-four hours — and the send reported the failure and stopped, leaving somebody
 * mid-conversation with a prepared message, the right files chosen, and no way to send any of
 * it.</p>
 *
 * <p>There has always been a path that works with no provider at all. These describe taking that
 * path when the provider cannot.</p>
 */
class SendFallbackTest {

    private LeadRepository leads;
    private LeadActivityRepository activities;
    private SendPackService service;

    /** A provider that is configured and willing, and fails when actually asked. */
    private static class LapsedProvider implements WhatsAppChannel {
        @Override public String name() { return "Meta Cloud API"; }
        @Override public boolean canSendAutomatically() { return true; }
        @Override public SendResult send(String toPhone, String studentName, String message,
                                         List<Attachment> attachments) {
            return SendResult.failed("The WhatsApp access token has expired.");
        }
    }

    @BeforeEach
    void setUp() {
        SendPackRepository packs = mock(SendPackRepository.class);
        AssetRepository assets = mock(AssetRepository.class);
        leads = mock(LeadRepository.class);
        activities = mock(LeadActivityRepository.class);
        CourseRepository courses = mock(CourseRepository.class);
        BatchRepository batches = mock(BatchRepository.class);

        when(batches.findUpcomingForCourse(any(), any())).thenReturn(List.of());
        when(batches.findUpcoming(any())).thenReturn(List.of());
        when(courses.findAll()).thenReturn(List.of(Course.builder().id("c9").name("Gen AI").build()));
        when(leads.save(any())).thenAnswer(i -> i.getArgument(0));
        when(activities.save(any())).thenAnswer(i -> i.getArgument(0));

        when(packs.findByKey("guidance")).thenReturn(Optional.of(SendPack.builder()
                .id("p1").key("guidance").name("Post-call guidance pack").active(true)
                .coverTemplate("Here is everything for {{course}}.")
                .assetKeys("syllabus").build()));
        when(assets.findByKey("syllabus")).thenReturn(Optional.of(Asset.builder()
                .id("a1").key("syllabus").name("Gen AI — syllabus").type("PDF")
                .url("https://www.devanshedutech.com/api/public/brochure/download")
                .tracked(false).active(true).build()));

        // The provider is the active channel: configured, claiming it can send, and lapsed.
        WhatsAppSender sender = new WhatsAppSender(
                new com.devanshedutech.channel.MetaCloudChannel(
                        new org.springframework.boot.web.client.RestTemplateBuilder()),
                new com.devanshedutech.channel.AiSensyChannel(
                        new org.springframework.boot.web.client.RestTemplateBuilder()),
                new ManualWhatsAppChannel()) {
            private final WhatsAppChannel lapsed = new LapsedProvider();
            @Override public WhatsAppChannel active() { return lapsed; }
            @Override public WhatsAppChannel.SendResult send(String toPhone, String studentName,
                                                             String message, List<WhatsAppChannel.Attachment> a) {
                return lapsed.send(toPhone, studentName, message, a);
            }
        };
        ReflectionTestUtils.setField(sender, "publicBaseUrl", "https://www.devanshedutech.com");

        AssetTrackingService tracking = new AssetTrackingService(
                mock(AssetLinkRepository.class), leads,
                new LeadLifecycleService(leads, activities, TestCalendars.openEveryDay()));
        ReflectionTestUtils.setField(tracking, "publicBaseUrl", "https://www.devanshedutech.com");

        service = new SendPackService(packs, assets, leads, courses, batches,
                new LeadLifecycleService(leads, activities, TestCalendars.openEveryDay()),
                sender, tracking);
    }

    private Lead lead() {
        return Lead.builder().id("l1").fullName("Omkar Bhosale").mobileNumber("+91 88765 43210")
                .courseInterested("Gen AI").courseId("c9").cityName("Selu")
                .lastInboundAt(LocalDateTime.now().minusMinutes(30))
                .callAttempts(0).updatesOnly(false).optedOut(false)
                .createdAt(LocalDateTime.now().minusDays(1)).build();
    }

    @Test
    @DisplayName("a lapsed provider hands the message to the counsellor instead of stopping")
    void aLapsedProviderFallsBackToTheCounsellor() {
        var outcome = service.send(lead(), "guidance", "Here you go, Omkar.", List.of("syllabus"), null);

        assertFalse(outcome.sent(), "nothing reached the student automatically");
        assertNotNull(outcome.handoffUrl(),
                "and the counsellor is handed a way to send it themselves — reporting the "
                + "failure and stopping leaves them with no path at all");
        assertTrue(outcome.handoffUrl().startsWith("https://wa.me/918876543210?text="));
    }

    @Test
    @DisplayName("the fallback carries the message and the chosen file, not just the number")
    void theFallbackCarriesEverything() {
        var outcome = service.send(lead(), "guidance", "Here you go, Omkar.", List.of("syllabus"), null);

        String text = URLDecoder.decode(
                outcome.handoffUrl().substring(outcome.handoffUrl().indexOf("?text=") + 6),
                StandardCharsets.UTF_8);

        assertTrue(text.contains("Here you go, Omkar."), "the edited message survives");
        assertTrue(text.contains("Gen AI — syllabus"), "the file is named");
        assertTrue(text.contains("https://www.devanshedutech.com/api/public/brochure/download"),
                "and reachable — a hand-off without the brochure is not the same send");
    }

    @Test
    @DisplayName("the counsellor is told the automatic send failed, not left to assume it worked")
    void theOutcomeSaysWhatHappened() {
        var outcome = service.send(lead(), "guidance", "Here you go, Omkar.", List.of("syllabus"), null);

        assertEquals("handoff_after_failure", outcome.status(),
                "the client needs to distinguish this from an ordinary manual hand-off");
        assertTrue(outcome.detail().contains("expired"), outcome.detail());
        assertTrue(outcome.detail().contains("your own WhatsApp"), outcome.detail());
    }

    @Test
    @DisplayName("the failure is still written to the timeline")
    void theFailureIsStillRecorded() {
        // A message that did not go and left no trace is how a lead ends up looking contacted
        // when nobody contacted them. Falling back must not quietly erase that.
        service.send(lead(), "guidance", "Here you go, Omkar.", List.of("syllabus"), null);

        verify(activities, atLeastOnce()).save(argThat(a ->
                "failed".equals(a.getDeliveryStatus())));
    }
}
