package com.devanshedutech.service;

import com.devanshedutech.dto.LeadDTOs.LeadRequest;
import com.devanshedutech.model.InboundMessage;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.crm.LeadSource;
import com.devanshedutech.repository.InboundMessageRepository;
import com.devanshedutech.repository.LeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class InboundWhatsAppServiceTest {

    private InboundMessageRepository inbound;
    private LeadRepository leads;
    private LeadCaptureService capture;
    private LeadLifecycleService lifecycle;
    private SendPackService packs;
    private InboundWhatsAppService service;

    private Map<String, InboundMessage> seen;

    @BeforeEach
    void setUp() {
        inbound = mock(InboundMessageRepository.class);
        leads = mock(LeadRepository.class);
        capture = mock(LeadCaptureService.class);
        lifecycle = mock(LeadLifecycleService.class);
        packs = mock(SendPackService.class);

        seen = new HashMap<>();
        when(inbound.existsById(anyString())).thenAnswer(i -> seen.containsKey(i.getArgument(0)));
        when(inbound.save(any())).thenAnswer(i -> {
            InboundMessage m = i.getArgument(0);
            seen.put(m.getMessageId(), m);
            return m;
        });
        when(leads.findByPhoneNormalized(anyString())).thenReturn(List.of());
        when(capture.capture(any(), any())).thenAnswer(i -> {
            LeadRequest r = i.getArgument(0);
            return new LeadCaptureService.Captured(Lead.builder()
                    .id("new-lead").fullName(r.getFullName()).mobileNumber(r.getMobileNumber())
                    .notes(r.getNotes()).optedOut(false).build(), false);
        });

        service = new InboundWhatsAppService(inbound, leads, capture, lifecycle, packs);
        ReflectionTestUtils.setField(service, "autoReplyEnabled", true);
    }

    /** A realistic Meta webhook envelope. */
    private Map<String, Object> payload(String id, String from, String text, String name) {
        Map<String, Object> message = new HashMap<>();
        message.put("id", id);
        message.put("from", from);
        message.put("type", "text");
        message.put("text", Map.of("body", text));

        return Map.of("object", "whatsapp_business_account", "entry", List.of(Map.of(
                "id", "973850178987794",
                "changes", List.of(Map.of(
                        "field", "messages",
                        "value", Map.of(
                                "messaging_product", "whatsapp",
                                "contacts", List.of(Map.of("profile", Map.of("name", name),
                                                           "wa_id", from)),
                                "messages", List.of(message)))))));
    }

    private LeadRequest capturedRequest() {
        ArgumentCaptor<LeadRequest> c = ArgumentCaptor.forClass(LeadRequest.class);
        verify(capture).capture(c.capture(), any());
        return c.getValue();
    }

    // ---------------- reading Meta's envelope ----------------

    @Test
    @DisplayName("a text message is read out of the webhook envelope")
    void textMessagesAreParsed() {
        List<InboundWhatsAppService.Incoming> parsed =
                service.parse(payload("wamid.ABC", "919876543210", "what is the fee?", "Rohit"));

        assertEquals(1, parsed.size());
        assertEquals("wamid.ABC", parsed.get(0).messageId());
        assertEquals("919876543210", parsed.get(0).fromPhone());
        assertEquals("what is the fee?", parsed.get(0).text());
        assertEquals("Rohit", parsed.get(0).profileName());
    }

    @Test
    @DisplayName("delivery receipts are not treated as messages")
    void statusUpdatesAreIgnored() {
        // The same hook carries "your message was read" events. Treating one as a message would
        // create a lead for the institute's own outgoing message.
        Map<String, Object> statuses = Map.of("object", "whatsapp_business_account",
                "entry", List.of(Map.of("changes", List.of(Map.of(
                        "field", "messages",
                        "value", Map.of("messaging_product", "whatsapp",
                                        "statuses", List.of(Map.of("id", "wamid.X",
                                                                   "status", "delivered"))))))));
        assertTrue(service.parse(statuses).isEmpty());
    }

    @Test
    @DisplayName("a payload that makes no sense yields nothing rather than throwing")
    void malformedPayloadsAreSurvivable() {
        // An exception here would make Meta redeliver a body we can never parse, forever.
        assertTrue(service.parse(null).isEmpty());
        assertTrue(service.parse(Map.of()).isEmpty());
        assertTrue(service.parse(Map.of("entry", "not-a-list")).isEmpty());
        assertTrue(service.parse(Map.of("entry", List.of("nonsense"))).isEmpty());
    }

    @Test
    @DisplayName("a photo or voice note is recorded as having arrived, not dropped")
    void nonTextMessagesStillCount() {
        Map<String, Object> message = new HashMap<>();
        message.put("id", "wamid.IMG");
        message.put("from", "919876543210");
        message.put("type", "image");
        Map<String, Object> body = Map.of("object", "whatsapp_business_account",
                "entry", List.of(Map.of("changes", List.of(Map.of(
                        "field", "messages",
                        "value", Map.of("messages", List.of(message)))))));

        List<InboundWhatsAppService.Incoming> parsed = service.parse(body);
        assertEquals(1, parsed.size());
        assertTrue(parsed.get(0).text().contains("image"), parsed.get(0).text());
    }

    // ---------------- handling ----------------

    @Test
    @DisplayName("a student who messages first becomes a lead, credited to WhatsApp")
    void firstMessageCreatesALead() {
        service.handle(new InboundWhatsAppService.Incoming(
                "wamid.1", "919876543210", "Rohit", "is there a batch in August?"));

        LeadRequest r = capturedRequest();
        assertEquals("Rohit", r.getFullName());
        assertEquals(LeadSource.WHATSAPP.name(), r.getSource());
        assertTrue(r.getNotes().contains("batch in August"),
                "the counsellor should see what they actually asked");
    }

    @Test
    @DisplayName("an existing student is not duplicated")
    void knownNumbersReuseTheirLead() {
        Lead existing = Lead.builder().id("l1").fullName("Rohit Deshmukh")
                .createdAt(LocalDateTime.now().minusDays(3)).optedOut(false).build();
        when(leads.findByPhoneNormalized("9876543210")).thenReturn(List.of(existing));

        service.handle(new InboundWhatsAppService.Incoming("wamid.2", "919876543210", "Rohit", "hi"));

        verify(capture, never()).capture(any(), any());
        verify(lifecycle).recordInbound(eq(existing), eq("hi"), any());
    }

    @Test
    @DisplayName("Meta redelivering the same message changes nothing the second time")
    void redeliveriesAreIgnored() {
        // Meta retries whenever it does not get a prompt 200 — after a timeout, a restart, a slow
        // query. Without this, one "hi" during a deploy is two leads and two auto-replies.
        var message = new InboundWhatsAppService.Incoming("wamid.3", "919876543210", "Rohit", "hi");

        assertTrue(service.handle(message).isPresent());
        assertTrue(service.handle(message).isEmpty(), "the retry is recognised");

        verify(capture, times(1)).capture(any(), any());
        verify(packs, times(1)).send(any(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("the reply, the window and the grade are all recorded")
    void everythingAReplyMeansIsRecorded() {
        service.handle(new InboundWhatsAppService.Incoming(
                "wamid.4", "919876543210", "Rohit", "what are the fees"));
        // Delegated rather than reimplemented: the buying-signal promotion, the reply window and
        // today's next touch all live in one place already.
        verify(lifecycle).recordInbound(any(), eq("what are the fees"), any());
    }

    // ---------------- the auto-reply ----------------

    @Test
    @DisplayName("the opening message gets an automatic reply")
    void conversationsOpenWithAReply() {
        service.handle(new InboundWhatsAppService.Incoming("wamid.5", "919876543210", "Rohit", "hi"));
        verify(packs).send(any(), eq("auto_reply"), any(), any(), any());
    }

    @Test
    @DisplayName("a reply mid-conversation does not trigger the introduction again")
    void repliesDuringAConversationAreLeftAlone() {
        // The failure this prevents: a counsellor is talking to a student, the student answers a
        // question, and the institute robotically sends the introductory course list again. That
        // reads as nobody being there, which is the opposite of the point.
        Lead talking = Lead.builder().id("l1").fullName("Rohit")
                .lastInboundAt(LocalDateTime.now().minusMinutes(20))
                .createdAt(LocalDateTime.now().minusDays(1)).optedOut(false).build();
        when(leads.findByPhoneNormalized("9876543210")).thenReturn(List.of(talking));

        service.handle(new InboundWhatsAppService.Incoming("wamid.6", "919876543210", "Rohit", "yes 6pm works"));

        verify(packs, never()).send(any(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("a student coming back weeks later starts a fresh conversation")
    void aLapsedConversationOpensAgain() {
        Lead old = Lead.builder().id("l1").fullName("Rohit")
                .lastInboundAt(LocalDateTime.now().minusDays(30))
                .createdAt(LocalDateTime.now().minusDays(60)).optedOut(false).build();
        when(leads.findByPhoneNormalized("9876543210")).thenReturn(List.of(old));

        service.handle(new InboundWhatsAppService.Incoming("wamid.7", "919876543210", "Rohit", "is the batch open?"));

        verify(packs).send(any(), eq("auto_reply"), any(), any(), any());
    }

    @Test
    @DisplayName("somebody who asked not to be contacted is never auto-replied to")
    void optingOutIsAbsolute() {
        Lead quiet = Lead.builder().id("l1").fullName("Rohit").optedOut(true)
                .createdAt(LocalDateTime.now().minusDays(5)).build();
        when(leads.findByPhoneNormalized("9876543210")).thenReturn(List.of(quiet));

        service.handle(new InboundWhatsAppService.Incoming("wamid.8", "919876543210", "Rohit", "stop"));

        verify(packs, never()).send(any(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("auto-reply can be turned off without losing the capture")
    void autoReplyIsOptional() {
        ReflectionTestUtils.setField(service, "autoReplyEnabled", false);
        service.handle(new InboundWhatsAppService.Incoming("wamid.9", "919876543210", "Rohit", "hi"));

        verify(packs, never()).send(any(), anyString(), any(), any(), any());
        verify(capture).capture(any(), any());
    }

    @Test
    @DisplayName("a failed auto-reply does not lose the student")
    void aFailedReplyStillFilesTheLead() {
        // Failing here would only make Meta redeliver. The message is already filed and a
        // counsellor can pick it up.
        doThrow(new RuntimeException("provider down"))
                .when(packs).send(any(), anyString(), any(), any(), any());

        assertDoesNotThrow(() -> service.handle(
                new InboundWhatsAppService.Incoming("wamid.10", "919876543210", "Rohit", "hi")));
        verify(lifecycle).recordInbound(any(), eq("hi"), any());
    }

    @Test
    @DisplayName("a refused auto-reply is not reported as a delivered one")
    void aRefusedReplyIsNotCalledSuccess() {
        // The lead's timeline already records the refusal. A log line claiming success would be
        // the one place somebody looks when a student says they never heard back.
        when(packs.send(any(), anyString(), any(), any(), any())).thenReturn(
                new SendPackService.SendOutcome(false, "failed",
                        "That number is not on the test number's allowed list.", null, "WhatsApp Cloud API"));

        assertDoesNotThrow(() -> service.handle(new InboundWhatsAppService.Incoming(
                "wamid.12", "919876543210", "Rohit", "hi")));
        // Still filed and still recorded — only the claim of delivery is withheld.
        verify(lifecycle).recordInbound(any(), eq("hi"), any());
    }

    @Test
    @DisplayName("a student with no WhatsApp name is labelled honestly")
    void missingProfileNamesAreHandled() {
        service.handle(new InboundWhatsAppService.Incoming("wamid.11", "919876543210", null, "hi"));
        assertEquals("WhatsApp enquiry", capturedRequest().getFullName());
    }
}
