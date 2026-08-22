package com.devanshedutech.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a counsellor's own WhatsApp opens with.
 *
 * <p>A deep link carries text and nothing else — no file can be pre-attached to one. The channel
 * used to list the attachments by name and ask the counsellor to attach them, which sounds
 * reasonable and is not: those files are on the server, so following that instruction means
 * downloading each one to a phone first, in the middle of a conversation. In practice the
 * brochure never arrived.</p>
 *
 * <p>So every attachment goes into the text as a link. These tests describe what the student
 * ends up reading.</p>
 */
class ManualWhatsAppChannelTest {

    private final ManualWhatsAppChannel channel = new ManualWhatsAppChannel();

    private static final String BROCHURE = "https://www.devanshedutech.com/api/public/a/abc123";
    private static final String VIDEO = "https://www.devanshedutech.com/api/public/a/xyz789";

    /** The message as WhatsApp will show it, read back out of the deep link. */
    private String textOf(WhatsAppChannel.SendResult result) {
        String url = result.handoffUrl();
        assertNotNull(url, "expected a hand-off link");
        return URLDecoder.decode(url.substring(url.indexOf("?text=") + 6), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the brochure and the video travel as links inside the message")
    void attachmentsBecomeLinks() {
        var result = channel.send("9876543210", "Rohit", "Here is what you asked about.", List.of(
                new WhatsAppChannel.Attachment("Institute brochure", "PDF", BROCHURE),
                new WhatsAppChannel.Attachment("Student review", "VIDEO", VIDEO)));

        String text = textOf(result);
        assertTrue(text.contains("Here is what you asked about."), "the covering note survives");
        assertTrue(text.contains("Institute brochure"), "the brochure is named");
        assertTrue(text.contains(BROCHURE), "the brochure is reachable");
        assertTrue(text.contains("Student review"), "the video is named");
        assertTrue(text.contains(VIDEO), "the video is reachable");
    }

    @Test
    @DisplayName("a file already named in the message is not repeated")
    void alreadyPresentLinksAreLeftAlone() {
        // Link-type assets are appended upstream when the pack is prepared. The same URL twice
        // in one message reads to a student as a mistake.
        String message = "Have a look:\n\n" + BROCHURE;
        var result = channel.send("9876543210", "Rohit", message,
                List.of(new WhatsAppChannel.Attachment("Institute brochure", "PDF", BROCHURE)));

        String text = textOf(result);
        assertEquals(text.indexOf(BROCHURE), text.lastIndexOf(BROCHURE), "the link appears once");
    }

    @Test
    @DisplayName("an attachment with no usable URL is skipped rather than sent as a stray name")
    void unusableAttachmentsAreSkipped() {
        var result = channel.send("9876543210", "Rohit", "Here you go.",
                List.of(new WhatsAppChannel.Attachment("Broken", "PDF", null),
                        new WhatsAppChannel.Attachment("Blank", "PDF", "  ")));

        assertEquals("Here you go.", textOf(result),
                "a name with no link behind it tells the student nothing");
    }

    @Test
    @DisplayName("the counsellor is told what is going, not asked to attach it")
    void theNoteDescribesLinksNotAttaching() {
        var result = channel.send("9876543210", "Rohit", "Hello", List.of(
                new WhatsAppChannel.Attachment("Institute brochure", "PDF", BROCHURE),
                new WhatsAppChannel.Attachment("Student review", "VIDEO", VIDEO)));

        assertTrue(result.detail().contains("2 links"), result.detail());
        assertFalse(result.detail().toLowerCase().contains("attach:"),
                "asking for a manual attachment is the instruction nobody could follow");
    }

    @Test
    @DisplayName("one link is described as one, not as 1 links")
    void theNoteCountsProperly() {
        var result = channel.send("9876543210", "Rohit", "Hello",
                List.of(new WhatsAppChannel.Attachment("Institute brochure", "PDF", BROCHURE)));

        assertTrue(result.detail().contains("1 link ready"), result.detail());
    }

    @Test
    @DisplayName("a message with nothing chosen opens exactly as written")
    void plainMessagesAreUntouched() {
        var result = channel.send("9876543210", "Rohit", "Just checking in.", List.of());

        assertEquals("Just checking in.", textOf(result));
        assertTrue(result.detail().contains("ready to send"), result.detail());
    }

    @Test
    @DisplayName("a lead with no usable number fails rather than opening an empty chat")
    void unusableNumbersFail() {
        var result = channel.send("not a phone number", "Rohit", "Hello", List.of());

        assertFalse(result.sent());
        assertNull(result.handoffUrl());
        assertTrue(result.detail().toLowerCase().contains("phone number"), result.detail());
    }
}
