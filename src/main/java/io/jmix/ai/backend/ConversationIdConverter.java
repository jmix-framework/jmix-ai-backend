package io.jmix.ai.backend;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.slf4j.event.KeyValuePair;

import java.util.List;
import java.util.Objects;

public class ConversationIdConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        List<KeyValuePair> keyValuePairs = event.getKeyValuePairs();
        if (keyValuePairs == null) {
            return "";
        }
        return keyValuePairs.stream()
                .filter(pair -> "cid".equals(pair.key))
                .map(pair -> Objects.toString(pair.value, ""))
                .findFirst()
                .orElse("");
    }
}
