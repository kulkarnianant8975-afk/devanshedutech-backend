package com.devanshedutech.config;

import com.devanshedutech.controller.AssetController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.unit.DataSize;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which limit gets to do the talking when a file is too big.
 *
 * <p>There are two, and only one of them can explain itself. AssetController names the size, the
 * limit, whose limit it is and what to do instead. Spring's multipart limit throws inside the
 * dispatcher before any controller runs, so it can only produce a bare "something went wrong at
 * our end" about a perfectly ordinary file.</p>
 *
 * <p>Whichever limit is lower is the one that answers. So the framework's must stay above every
 * per-type limit — otherwise the useful message is unreachable. On 2026-08-21 it was not: the
 * media library offered 200 MB videos while multipart still allowed 105 MB, and a counsellor
 * uploading a 126 MB file was told nothing at all.</p>
 */
class UploadLimitTest {

    /**
     * Read from the production file rather than through a Spring context on purpose.
     *
     * <p>The test classpath has its own application.yml, and it shadows this one — so a context
     * here would assert against the test configuration and pass no matter what production said.
     * The whole point is what production is configured to accept.</p>
     */
    private static String property(String key) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        Properties props = yaml.getObject();
        assertNotNull(props, "src/main/resources/application.yml could not be read");
        String value = props.getProperty(key);
        assertNotNull(value, key + " is not set in the production application.yml");
        return value;
    }

    private final String maxFileSize = property("spring.servlet.multipart.max-file-size");
    private final String maxRequestSize = property("spring.servlet.multipart.max-request-size");

    @Test
    @DisplayName("no per-type limit is out of reach behind the framework's")
    void theControllerAlwaysGetsToExplain() {
        long file = DataSize.parse(maxFileSize).toBytes();

        AssetController.limits().forEach((type, limit) -> assertTrue(file > limit,
                type + " may be up to " + limit + " bytes, but multipart stops at " + file
                + " — a file between the two is refused with no explanation a counsellor can act on."));
    }

    @Test
    @DisplayName("the request limit leaves room for the file plus its envelope")
    void theRequestLimitClearsTheFileLimit() {
        // A multipart request is the file plus boundaries, headers and the other form fields. A
        // request limit equal to the file limit rejects a file of exactly the maximum size.
        long file = DataSize.parse(maxFileSize).toBytes();
        long request = DataSize.parse(maxRequestSize).toBytes();

        assertTrue(request > file,
                "max-request-size (" + request + ") must exceed max-file-size (" + file + ")");
    }

    @Test
    @DisplayName("a video may be larger than WhatsApp will carry, because it is sent as a link")
    void videosMayExceedTheWhatsAppLimit() {
        // Guards the reason the video limit is large in the first place. If someone ever
        // "simplified" the video limit down to Meta's 16 MB, the media library would stop
        // accepting the very files it exists to host.
        assertTrue(AssetController.limits().get("VIDEO") > AssetController.WHATSAPP_INLINE_VIDEO,
                "a video too big for a chat bubble is still hosted here and sent as a link");
    }
}
