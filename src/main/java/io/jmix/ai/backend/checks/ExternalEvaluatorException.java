package io.jmix.ai.backend.checks;

public class ExternalEvaluatorException extends RuntimeException {

    public ExternalEvaluatorException(String message) {
        super(message);
    }

    public ExternalEvaluatorException(String message, Throwable cause) {
        super(message, cause);
    }
}
