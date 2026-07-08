package io.jmix.ai.backend.controller;

import io.jmix.ai.backend.checks.CheckRunner;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.security.SystemAuthenticator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Programmatic trigger for a check run against a specific config.
 * Runs synchronously and returns the resulting score/accuracy, so a script can
 * repeat runs and average out sampling noise for an honest baseline-vs-candidate comparison.
 */
@RestController
public class CheckRunController {

    private final DataManager dataManager;
    private final CheckRunner checkRunner;
    private final SystemAuthenticator systemAuthenticator;

    public CheckRunController(DataManager dataManager,
                              CheckRunner checkRunner,
                              SystemAuthenticator systemAuthenticator) {
        this.dataManager = dataManager;
        this.checkRunner = checkRunner;
        this.systemAuthenticator = systemAuthenticator;
    }

    @PostMapping("/api/checks/run")
    public Map<String, Object> run(@RequestBody Request request) {
        return systemAuthenticator.withSystem(() -> {
            List<Parameters> found = dataManager.load(Parameters.class)
                    .query("e.createdBy = :cb")
                    .parameter("cb", request.config())
                    .list();
            if (found.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No parameters with created_by=" + request.config());
            }
            Parameters params = found.get(0);

            CheckRun checkRun = dataManager.create(CheckRun.class);
            checkRun.setParameters(params.getContent());
            // configLabel is derived from the parameters YAML inside runChecks when null
            checkRun = dataManager.save(checkRun);

            checkRunner.runChecks(Id.of(checkRun));

            CheckRun done = dataManager.load(Id.of(checkRun)).one();
            return Map.of(
                    "id", done.getId().toString(),
                    "config", request.config(),
                    "score", done.getScore() != null ? done.getScore() : 0.0,
                    "accuracy", done.getAccuracy() != null ? done.getAccuracy() : 0.0
            );
        });
    }

    public record Request(
            String config) {
    }
}
