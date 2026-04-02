package io.jmix.ai.backend.vectorstore.trainings;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TrainingSourceLoader {

    private static final Pattern INCLUDE_PATTERN = Pattern.compile("^\\s*include::([^\\[]+)\\[.*]\\s*$");

    public String load(Path sourceFile) throws IOException {
        return load(sourceFile, new HashSet<>());
    }

    private String load(Path sourceFile, Set<Path> visited) throws IOException {
        Path normalizedPath = sourceFile.toAbsolutePath().normalize();
        if (!visited.add(normalizedPath)) {
            throw new IllegalStateException("Circular include detected for " + normalizedPath);
        }

        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(normalizedPath)) {
            Matcher matcher = INCLUDE_PATTERN.matcher(line);
            if (!matcher.matches()) {
                sb.append(line).append("\n");
                continue;
            }

            String includeTarget = StringUtils.trim(matcher.group(1));
            Path includePath = normalizedPath.getParent().resolve(includeTarget).normalize();
            if (Files.notExists(includePath)) {
                throw new IllegalStateException("Included training file not found: " + includePath);
            }

            sb.append(load(includePath, visited)).append("\n");
        }

        visited.remove(normalizedPath);
        return sb.toString();
    }
}
