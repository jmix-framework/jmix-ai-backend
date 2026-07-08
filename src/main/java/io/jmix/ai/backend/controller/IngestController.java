package io.jmix.ai.backend.controller;

import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.vectorstore.IngesterManager;
import io.jmix.core.security.SystemAuthenticator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Programmatic trigger for re-ingesting a corpus type for a Jmix version. Runs synchronously
 * under system auth so a script can re-ingest and then re-run checks without the UI.
 */
@RestController
public class IngestController {

    private final IngesterManager ingesterManager;
    private final SystemAuthenticator systemAuthenticator;

    public IngestController(IngesterManager ingesterManager, SystemAuthenticator systemAuthenticator) {
        this.ingesterManager = ingesterManager;
        this.systemAuthenticator = systemAuthenticator;
    }

    @PostMapping("/api/ingest")
    public Map<String, Object> ingest(@RequestBody Request request) {
        JmixVersion version = JmixVersion.fromId(request.version());
        if (request.type() == null || version == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type and version are required");
        }
        String result = systemAuthenticator.withSystem(() ->
                ingesterManager.updateByTypeAndVersion(request.type(), version));
        return Map.of("type", request.type(), "version", version.getId(), "result", result);
    }

    public record Request(String type, String version) {
    }
}
