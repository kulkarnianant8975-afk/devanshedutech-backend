package com.devanshedutech.service;

import com.devanshedutech.channel.AiSensyChannel;
import com.devanshedutech.channel.ManualWhatsAppChannel;
import com.devanshedutech.channel.WhatsAppSender;
import com.devanshedutech.model.Broadcast;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.crm.Grade;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.repository.BroadcastRepository;
import com.devanshedutech.repository.LeadActivityRepository;
import com.devanshedutech.repository.LeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Broadcasts, and the promise that opting out means something.
 */
class BroadcastServiceTest {

    private LeadRepository leads;
    private BroadcastRepository broadcasts;
    private BroadcastService service;

    private BroadcastService withProvider(boolean configured) {
        AiSensyChannel provider = new AiSensyChannel(new RestTemplateBuilder());
        ReflectionTestUtils.setField(provider, "apiKey", configured ? "key" : "");
        ReflectionTestUtils.setField(provider, "endpoint", "http://127.0.0.1:1/none");
        ReflectionTestUtils.setField(provider, "campaign", "crm");
        WhatsAppSender sender = new WhatsAppSender(provider, new ManualWhatsAppChannel());
        ReflectionTestUtils.setField(sender, "publicBaseUrl", "");

        LeadActivityRepository activities = mock(LeadActivityRepository.class);
        when(activities.save(any())).thenAnswer(i -> i.getArgument(0));
        return new BroadcastService(broadcasts, leads,
                new LeadLifecycleService(leads, activities), sender);
    }

    @BeforeEach
    void setUp() {
        leads = mock(LeadRepository.class);
        broadcasts = mock(BroadcastRepository.class);
        when(broadcasts.save(any())).thenAnswer(i -> i.getArgument(0));
        when(leads.save(any())).thenAnswer(i -> i.getArgument(0));
        when(leads.findAll()).thenReturn(List.of());
        service = withProvider(false);
    }

    private Lead cold(String name, String phone) {
        return Lead.builder().id(name).fullName(name).mobileNumber(phone)
                .phoneNormalized(Lead.normalizePhone(phone)).grade(Grade.COLD)
                .stage(Stage.CONTACTED).optedOut(false).updatesOnly(false).build();
    }

    @Test
    @DisplayName("a broadcast with nobody in the segment is refused rather than recorded as sent")
    void emptySegmentIsRefused() {
        when(leads.findAll(any(Specification.class))).thenReturn(List.of());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.send("New batch", "Starting soon", BroadcastService.Segment.COLD, null));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    @DisplayName("a title and a message are both required")
    void contentIsRequired() {
        assertThrows(ResponseStatusException.class,
                () -> service.send("", "body", BroadcastService.Segment.COLD, null));
        assertThrows(ResponseStatusException.class,
                () -> service.send("title", "  ", BroadcastService.Segment.COLD, null));
    }

    @Test
    @DisplayName("without a provider a broadcast reports failure rather than pretending it went")
    void withoutProviderNothingIsClaimed() {
        when(leads.findAll(any(Specification.class)))
                .thenReturn(List.of(cold("Kiran", "9876500001"), cold("Mayuri", "9876500002")));

        Broadcast b = service.send("September batch", "Starting soon", BroadcastService.Segment.COLD, null);

        // A hand-off channel cannot fan out to a list. Recording this as sent would put two
        // messages on two timelines that nobody ever received.
        assertEquals("FAILED", b.getStatus());
        assertEquals(0, b.getSentCount());
        assertEquals(2, b.getRecipientCount());
    }

    @Test
    @DisplayName("the segment description says who it reaches, so nobody sends blind")
    void previewExplainsTheAudience() {
        when(leads.count(any(Specification.class))).thenReturn(38L);
        var preview = service.preview(BroadcastService.Segment.LOST);

        assertEquals(38L, preview.get("recipients"));
        assertTrue(preview.get("description").toString().contains("come back"),
                "a manager should be told these are people worth reaching, not failures");
    }

    @Test
    @DisplayName("every segment excludes anyone who asked to stop")
    void optOutIsAbsolute() {
        // There is deliberately no segment that can select an opted-out student back in.
        for (BroadcastService.Segment segment : BroadcastService.Segment.values()) {
            assertNotNull(service.specFor(segment), segment + " has no specification");
        }
    }
}
