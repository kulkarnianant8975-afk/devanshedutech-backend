package com.devanshedutech.controller;

import com.devanshedutech.model.Asset;
import com.devanshedutech.repository.AssetRepository;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * The student testimonials shown on the public website.
 *
 * <p>Unauthenticated, because it feeds a marketing page that anybody can open. That makes what it
 * returns the whole question, so the rule is narrow and enforced here rather than left to the
 * caller: an asset appears only if it is a video, active, and explicitly marked for the website.
 * The library also holds fee sheets and internal notes, and a looser filter would publish those
 * the moment somebody uploaded one.</p>
 *
 * <p>Reading from the same library counsellors send from is deliberate. The alternative is
 * uploading every testimonial twice — once to send and once for the site — and the second copy
 * is always the one nobody remembers to replace.</p>
 */
@RestController
@RequestMapping("/api/public/reviews")
public class PublicReviewController {

    private final AssetRepository assets;

    public PublicReviewController(AssetRepository assets) {
        this.assets = assets;
    }

    @Data
    public static class ReviewDTO {
        /** Where the video itself is served from. */
        private final String url;
        /** The student's name, or whatever the video was titled in the library. */
        private final String name;
        private final String sizeLabel;
    }

    @GetMapping
    public List<ReviewDTO> list() {
        return assets.findAll().stream()
                .filter(Asset::isShowOnWebsite)
                .filter(Asset::isActive)
                .filter(a -> "VIDEO".equals(a.getType()))
                .sorted(Comparator.comparing(Asset::getName))
                // The download path, not the tracked one. A tracked link counts opens against a
                // specific lead, and a stranger browsing the website is not a lead — attributing
                // their view to whoever happened to be sent that video would be a lie in the
                // only number a counsellor trusts.
                .map(a -> new ReviewDTO("/api/assets/" + a.getId() + "/download",
                        a.getName(), a.getSizeLabel()))
                .toList();
    }
}
