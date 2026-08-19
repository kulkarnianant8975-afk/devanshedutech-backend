package com.devanshedutech.service;

import com.devanshedutech.model.AssetLink;
import com.devanshedutech.model.Lead;
import com.devanshedutech.repository.AssetLinkRepository;
import com.devanshedutech.repository.LeadActivityRepository;
import com.devanshedutech.repository.LeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AssetTrackingServiceTest {

    private AssetLinkRepository links;
    private LeadRepository leads;
    private AssetTrackingService tracking;
    private Map<String, AssetLink> stored;

    private static final String BROWSER =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";

    @BeforeEach
    void setUp() {
        links = mock(AssetLinkRepository.class);
        leads = mock(LeadRepository.class);
        LeadActivityRepository activities = mock(LeadActivityRepository.class);
        when(activities.save(any())).thenAnswer(i -> i.getArgument(0));
        when(leads.save(any())).thenAnswer(i -> i.getArgument(0));

        stored = new HashMap<>();
        when(links.save(any())).thenAnswer(i -> {
            AssetLink l = i.getArgument(0);
            stored.put(l.getToken(), l);
            return l;
        });
        when(links.findById(anyString())).thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(0))));
        when(links.findByLeadIdAndAssetKey(anyString(), anyString())).thenAnswer(i ->
                stored.values().stream()
                        .filter(l -> l.getLeadId().equals(i.getArgument(0))
                                && l.getAssetKey().equals(i.getArgument(1)))
                        .findFirst());

        tracking = new AssetTrackingService(links, leads,
                new LeadLifecycleService(leads, activities, TestCalendars.openEveryDay()));
        ReflectionTestUtils.setField(tracking, "publicBaseUrl", "https://devanshedutech.com");
    }

    private Lead lead() {
        Lead l = Lead.builder().id("l1").fullName("Omkar Bhosale").build();
        when(leads.findById("l1")).thenReturn(Optional.of(l));
        return l;
    }

    private String issue() {
        return tracking.trackedUrl(lead(), "syllabus", "Data Analytics syllabus",
                "/api/public/brochure/download", true);
    }

    private String tokenOf(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    @Test
    @DisplayName("a tracked asset is sent as this lead's own link")
    void trackedAssetsGetTheirOwnLink() {
        String url = issue();
        assertTrue(url.startsWith("https://devanshedutech.com/api/public/a/"), url);
        AssetLink link = stored.get(tokenOf(url));
        assertEquals("l1", link.getLeadId());
        // A relative asset path is served by this application, so the redirect has to be absolute.
        assertEquals("https://devanshedutech.com/api/public/brochure/download", link.getTargetUrl());
    }

    @Test
    @DisplayName("the same asset keeps one link, so opens accumulate rather than restart")
    void oneLinkPerLeadAndAsset() {
        assertEquals(tokenOf(issue()), tokenOf(issue()));
        assertEquals(1, stored.size());
    }

    @Test
    @DisplayName("an untracked asset is sent exactly as it is")
    void untrackedAssetsAreUntouched() {
        assertEquals("https://youtu.be/xyz",
                tracking.trackedUrl(lead(), "video", "Project video", "https://youtu.be/xyz", false));
        assertTrue(stored.isEmpty());
    }

    @Test
    @DisplayName("without a public address the plain URL is sent rather than a broken one")
    void noPublicUrlMeansNoTracking() {
        // A tracking link pointing at localhost is not a degraded feature. It is a dead
        // attachment on a student's phone, and the file matters more than the statistic.
        ReflectionTestUtils.setField(tracking, "publicBaseUrl", "");
        assertEquals("/api/public/brochure/download",
                tracking.trackedUrl(lead(), "syllabus", "Syllabus", "/api/public/brochure/download", true));
    }

    @Test
    @DisplayName("a student opening it is counted")
    void aRealOpenCounts() {
        String token = tokenOf(issue());
        AssetTrackingService.Opened opened = tracking.open(token, BROWSER).orElseThrow();

        assertTrue(opened.counted());
        assertEquals("https://devanshedutech.com/api/public/brochure/download", opened.targetUrl());
        assertEquals(1, stored.get(token).opens());
        assertNotNull(stored.get(token).getFirstOpenedAt());
    }

    @ParameterizedTest
    @DisplayName("a link preview fetch is forwarded but never counted")
    @ValueSource(strings = {
            "WhatsApp/2.23.20.0 A",
            "Mozilla/5.0 (compatible; facebookexternalhit/1.1)",
            "TelegramBot (like TwitterBot)",
            "Slackbot-LinkExpanding 1.0",
            "curl/8.4.0",
    })
    void previewFetchesDoNotCount(String agent) {
        // WhatsApp fetches every link in a message to build its preview card, before the student
        // has seen anything. Counting that would mark every asset as opened the instant it was
        // sent — the exact false positive that would make the whole signal worthless.
        String token = tokenOf(issue());
        AssetTrackingService.Opened opened = tracking.open(token, agent).orElseThrow();

        assertFalse(opened.counted(), agent + " should not count as a student opening it");
        assertEquals(0, stored.get(token).opens());
        assertNotNull(opened.targetUrl(), "but it still has to resolve, or the preview breaks");
    }

    @Test
    @DisplayName("a request with no user agent is not counted")
    void missingUserAgentIsNotCounted() {
        String token = tokenOf(issue());
        assertFalse(tracking.open(token, null).orElseThrow().counted());
        assertFalse(tracking.open(token, "  ").orElseThrow().counted());
        assertEquals(0, stored.get(token).opens());
    }

    @Test
    @DisplayName("one tap that fires several requests counts once")
    void rapidRepeatsAreOneVisit() {
        // A tap can produce a redirect plus a range request for the PDF, or a reload when the
        // app switches. Counting each would turn one glance into "opened it four times", which
        // reads as strong interest and is not.
        String token = tokenOf(issue());
        tracking.open(token, BROWSER);
        tracking.open(token, BROWSER);
        tracking.open(token, BROWSER);
        assertEquals(1, stored.get(token).opens());
    }

    @Test
    @DisplayName("coming back later is a separate open")
    void aLaterVisitCountsAgain() {
        String token = tokenOf(issue());
        tracking.open(token, BROWSER);
        stored.get(token).setLastOpenedAt(LocalDateTime.now().minusHours(3));

        assertTrue(tracking.open(token, BROWSER).orElseThrow().counted());
        assertEquals(2, stored.get(token).opens());
    }

    @Test
    @DisplayName("an unknown token resolves to nothing rather than anywhere")
    void unknownTokensGoNowhere() {
        assertTrue(tracking.open("not-a-real-token", BROWSER).isEmpty());
    }

    @Test
    @DisplayName("only assets that were actually opened are reported")
    void unopenedLinksAreNotListed() {
        String token = tokenOf(issue());
        when(links.findByLeadIdOrderByLastOpenedAtDesc("l1"))
                .thenAnswer(i -> stored.values().stream().toList());

        assertTrue(tracking.forLead("l1").isEmpty(), "issued but never opened");
        tracking.open(token, BROWSER);
        assertEquals(1, tracking.forLead("l1").size());
    }
}
