package io.jmix.ai.backend;

import java.time.Duration;

/**
 * Timeout and retries of the OpenAI clients. Spring AI 2 limits a whole request, streaming included, to 60 s by
 * default; a reasoning model can take longer. The retries keep the ten attempts of Spring AI 1.x.
 */
public final class OpenAiClientDefaults {

    /** Limits the whole request, including reading a streamed response; the openai-java SDK default. */
    public static final Duration TIMEOUT = Duration.ofMinutes(10);

    public static final int MAX_RETRIES = 9;

    private OpenAiClientDefaults() {
    }
}
