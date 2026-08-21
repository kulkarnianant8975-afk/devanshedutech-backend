package com.devanshedutech.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which attachments WhatsApp will carry, and which have to become links.
 *
 * <p>The number here is Meta's, not ours, and getting it wrong is expensive in a specific way: a
 * video that exceeds it is accepted by the upload, chosen by a counsellor, reported as sent, and
 * never arrives.</p>
 */
class VideoDeliveryTest {

    private static final long MB = 1024 * 1024;

    @ParameterizedTest
    @DisplayName("a video is carried inside the message only up to WhatsApp's limit")
    @CsvSource({
            "1,   true",
            "15,  true",
            "16,  true",
            "17,  false",
            "200, false",
    })
    void videoSizeDecidesDelivery(int megabytes, boolean inline) {
        assertEquals(inline, SendPackService.fitsInsideWhatsApp("VIDEO", megabytes * MB),
                megabytes + " MB");
    }

    @Test
    @DisplayName("the boundary is exactly sixteen megabytes, not approximately")
    void theBoundaryIsExact() {
        assertTrue(SendPackService.fitsInsideWhatsApp("VIDEO", 16 * MB), "16 MB exactly is allowed");
        assertFalse(SendPackService.fitsInsideWhatsApp("VIDEO", 16 * MB + 1), "one byte over is not");
    }

    @Test
    @DisplayName("nothing else is size-limited by this rule")
    void onlyVideoIsAffected() {
        // Documents go to 100 MB and images to 5 MB, but those are enforced at upload. This rule
        // exists only because a video is the one thing that can be stored and still not fit.
        assertTrue(SendPackService.fitsInsideWhatsApp("PDF", 90 * MB));
        assertTrue(SendPackService.fitsInsideWhatsApp("IMAGE", 4 * MB));
        assertTrue(SendPackService.fitsInsideWhatsApp("LINK", null));
    }

    @Test
    @DisplayName("a video of unknown size is assumed to fit rather than blocked")
    void unknownSizesAreNotBlocked() {
        // Assets added before sizes were recorded have none. Treating that as too-large would
        // silently turn every existing video into a link; treating it as sendable means the
        // worst case is one refusal from WhatsApp, which is reported honestly.
        assertTrue(SendPackService.fitsInsideWhatsApp("VIDEO", null));
    }
}
