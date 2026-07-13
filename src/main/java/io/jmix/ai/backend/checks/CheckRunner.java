package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckDef;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.SaveContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CheckRunner {

    private static final Logger log = LoggerFactory.getLogger(CheckRunner.class);
    private static final String DEFINITION_FINGERPRINT_PREFIX = "definitions-v1";
    private static final int SHORT_HASH_LENGTH = 12;
    private static final Pattern LEGACY_COHORT_SUFFIX = Pattern.compile(
            " \\[cohort:(?:[a-z0-9]+-)*([a-f0-9]{12})]$");

    private final DataManager dataManager;
    private final Chat chat;
    private final ExternalEvaluator externalEvaluator;
    private final ExecutorService executor;
    private final int parallelism;
    private final double passThreshold;
    private final String evaluatorConfig;

    public CheckRunner(DataManager dataManager,
                       Chat chat,
                       ExternalEvaluator externalEvaluator,
                       @Value("${answer-checks.parallelism:4}") int parallelism,
                       @Value("${answer-checks.pass-threshold:0.8}") double passThreshold) {
        this.dataManager = dataManager;
        this.chat = chat;
        this.externalEvaluator = externalEvaluator;
        this.parallelism = Math.max(1, parallelism);
        this.executor = Executors.newFixedThreadPool(this.parallelism);
        this.passThreshold = passThreshold;
        this.evaluatorConfig = externalEvaluator.configurationSnapshot()
                + "|passThreshold=" + passThreshold;
    }

    public void runChecks(Id<CheckRun> checkRunId) {
        CheckRun checkRun = dataManager.load(checkRunId).one();
        JmixVersion jmixVersion = checkRun.getJmixVersion() != null ? checkRun.getJmixVersion() : JmixVersion.V2;
        List<Future<Check>> futures = new ArrayList<>();
        try {
            List<CheckDef> checkDefs = loadCheckDefs(jmixVersion);
            if (checkDefs.isEmpty()) {
                throw new IllegalStateException("No active check definitions for " + jmixVersion.getId());
            }

            String humanLabel = stripLegacyCohortSuffix(checkRun.getConfigLabel());
            if (humanLabel == null || humanLabel.isBlank()) {
                humanLabel = extractConfigLabel(checkRun.getParameters());
            }
            checkRun.setEvaluatorConfig(evaluatorConfig);
            checkRun.setDefinitionFingerprint(buildDefinitionFingerprint(checkDefs));
            checkRun.setConfigLabel(humanLabel);

            CompletionService<Check> completionService = new ExecutorCompletionService<>(executor);
            String parameters = checkRun.getParameters();
            Iterator<CheckDef> remaining = checkDefs.iterator();
            while (remaining.hasNext() && futures.size() < parallelism) {
                futures.add(submit(completionService, remaining.next(), parameters, jmixVersion));
            }

            List<Check> checks = new ArrayList<>(checkDefs.size());
            for (int i = 0; i < checkDefs.size(); i++) {
                checks.add(completionService.take().get());
                if (remaining.hasNext()) {
                    futures.add(submit(completionService, remaining.next(), parameters, jmixVersion));
                }
            }

            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Check run was cancelled");
            }
            saveCompletedRun(checkRun, checks);
        } catch (InterruptedException e) {
            cancel(futures);
            deleteFailedRun(checkRunId, e);
            log.info("Check run {} was cancelled", checkRunId);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Check run was cancelled", e);
        } catch (ExecutionException e) {
            cancel(futures);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            deleteFailedRun(checkRunId, cause);
            log.error("Check run {} failed", checkRunId, cause);
            throw propagate(cause);
        } catch (RuntimeException | Error e) {
            cancel(futures);
            deleteFailedRun(checkRunId, e);
            log.error("Check run {} failed", checkRunId, e);
            throw e;
        }
    }

    private Check runCheck(CheckDef checkDef, String parameters, JmixVersion jmixVersion) {
        StringBuilder logStringBuilder = new StringBuilder();
        Consumer<String> logStringConsumer = str ->
                logStringBuilder.append(str).append("\n");

        String actualAnswer = getAnswer(checkDef.getQuestion(), parameters, jmixVersion, logStringConsumer);

        if (!logStringBuilder.isEmpty())
            logStringBuilder.append("\n\n");

        double score = externalEvaluator.evaluateSemantic(
                checkDef.getQuestion(), checkDef.getAnswer(), actualAnswer, logStringBuilder::append);

        return buildCheck(checkDef, actualAnswer, score, logStringBuilder.toString());
    }

    private Future<Check> submit(CompletionService<Check> completionService,
                                 CheckDef checkDef,
                                 String parameters,
                                 JmixVersion jmixVersion) {
        return completionService.submit(() ->
                runCheck(checkDef, parameters, jmixVersion));
    }

    private Check buildCheck(CheckDef checkDef, String actualAnswer, double score, String log) {
        Check check = dataManager.create(Check.class);
        check.setCheckDef(checkDef);
        check.setCategory(checkDef.getCategory());
        check.setQuestion(checkDef.getQuestion());
        check.setReferenceAnswer(checkDef.getAnswer());
        check.setActualAnswer(actualAnswer);
        check.setScore(score);
        check.setLog(log);
        return check;
    }

    private List<CheckDef> loadCheckDefs(JmixVersion jmixVersion) {
        return dataManager.load(CheckDef.class)
                .query("e.active = true and (e.jmixVersion is null or e.jmixVersion = :jmixVersion)")
                .parameter("jmixVersion", jmixVersion.getId())
                .list();
    }

    private void saveCompletedRun(CheckRun checkRun, List<Check> checks) {
        double score = 0.0;
        int passed = 0;
        for (Check check : checks) {
            check.setCheckRun(checkRun);
            score += check.getScore();
            if (check.getScore() >= passThreshold) {
                passed++;
            }
        }
        checkRun.setScore(score / checks.size());
        checkRun.setAccuracy((double) passed / checks.size());

        SaveContext saveContext = new SaveContext().saving(checkRun);
        saveContext.saving(checks.toArray());
        dataManager.save(saveContext);
    }

    private static void cancel(List<? extends Future<?>> futures) {
        futures.forEach(future -> future.cancel(true));
    }

    private void deleteFailedRun(Id<CheckRun> checkRunId, Throwable failure) {
        try {
            dataManager.remove(checkRunId);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            log.error("Failed to delete unsuccessful check run {}", checkRunId, cleanupFailure);
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Check execution failed", failure);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Human-readable config label taken from the {@code description:} line of the parameters YAML.
     */
    static String extractConfigLabel(String parameters) {
        if (parameters == null) {
            return null;
        }
        for (String line : parameters.split("\n", 20)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("description:")) {
                String value = trimmed.substring("description:".length()).trim();
                return value.length() > 200 ? value.substring(0, 200) : value;
            }
        }
        return null;
    }

    static String buildDefinitionFingerprint(List<CheckDef> checkDefs) {
        List<String> definitions = checkDefs.stream()
                .map(CheckRunner::canonicalDefinition)
                .sorted(Comparator.naturalOrder())
                .toList();
        MessageDigest digest = sha256();
        for (String definition : definitions) {
            byte[] bytes = definition.getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(bytes);
        }
        return DEFINITION_FINGERPRINT_PREFIX + "-" + shortHex(digest.digest());
    }

    static String extractLegacyDefinitionFingerprint(String label) {
        if (label == null) {
            return null;
        }
        Matcher matcher = LEGACY_COHORT_SUFFIX.matcher(label);
        return matcher.find() ? DEFINITION_FINGERPRINT_PREFIX + "-" + matcher.group(1) : null;
    }

    static String stripLegacyCohortSuffix(String label) {
        String result = label;
        while (result != null) {
            Matcher matcher = LEGACY_COHORT_SUFFIX.matcher(result);
            if (!matcher.find()) {
                break;
            }
            result = result.substring(0, matcher.start());
        }
        return result;
    }

    static String shortSha256(String value) {
        MessageDigest digest = sha256();
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        return shortHex(digest.digest());
    }

    private static String canonicalDefinition(CheckDef checkDef) {
        // jmixVersion was not part of the legacy digest; run version is a separate cohort dimension.
        return canonicalField(checkDef.getId() != null ? checkDef.getId().toString() : null)
                + canonicalField(checkDef.getCategory())
                + canonicalField(checkDef.getQuestion())
                + canonicalField(checkDef.getAnswer());
    }

    private static String canonicalField(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String shortHex(byte[] hash) {
        return HexFormat.of().formatHex(hash, 0, SHORT_HASH_LENGTH / 2);
    }

    private String getAnswer(String question, String parameters, JmixVersion jmixVersion, Consumer<String> logger) {
        Chat.StructuredResponse response = chat.requestStructured(question, parameters, null, jmixVersion, logger);
        return response.text();
    }
}
