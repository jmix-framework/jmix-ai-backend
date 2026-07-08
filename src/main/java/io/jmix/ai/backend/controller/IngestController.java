package io.jmix.ai.backend.controller;

import io.jmix.ai.backend.vectorstore.IngesterManager;
import io.jmix.core.security.SystemAuthenticator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Programmatic trigger for re-ingesting a corpus type. Runs synchronously
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
        if (request.type() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required");
        }
        String result = systemAuthenticator.withSystem(() ->
                ingesterManager.updateByType(request.type()));
        return Map.of("type", request.type(), "result", result);
    }

    public record Request(String type) {
    }
}
