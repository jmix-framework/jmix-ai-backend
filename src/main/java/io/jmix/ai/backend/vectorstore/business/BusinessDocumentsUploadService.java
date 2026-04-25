package io.jmix.ai.backend.vectorstore.business;

import io.jmix.ai.backend.entity.KnowledgeSource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class BusinessDocumentsUploadService {

    public String upload(KnowledgeSource source, String originalFileName, byte[] content) {
        if (!BusinessDocumentsSupport.isBusinessDocumentsSource(source)) {
            throw new IllegalArgumentException("Selected source is not a business documents source");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }

        String sanitizedFileName = sanitizeFileName(originalFileName);
        String extension = extensionOf(sanitizedFileName);
        if (!BusinessDocumentsSupport.INCLUDED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported file type: " + extension);
        }

        Path rootPath = Path.of(source.getLocation()).normalize().toAbsolutePath();
        Path uploadsDir = rootPath.resolve(BusinessDocumentsSupport.UPLOADS_DIR);
        Path targetPath = uploadsDir.resolve(sanitizedFileName).normalize().toAbsolutePath();
        if (!targetPath.startsWith(uploadsDir)) {
            throw new IllegalArgumentException("Invalid file name");
        }

        try {
            Files.createDirectories(uploadsDir);
            Files.write(targetPath, content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save uploaded file: " + sanitizedFileName, e);
        }

        return BusinessDocumentsSupport.UPLOADS_DIR + "/" + sanitizedFileName;
    }

    private String sanitizeFileName(String originalFileName) {
        if (StringUtils.isBlank(originalFileName)) {
            throw new IllegalArgumentException("Uploaded file name is required");
        }
        String sanitized = Path.of(originalFileName).getFileName().toString().trim();
        if (StringUtils.isBlank(sanitized)) {
            throw new IllegalArgumentException("Uploaded file name is invalid");
        }
        return sanitized;
    }

    private String extensionOf(String path) {
        int dotIndex = path.lastIndexOf('.');
        return dotIndex >= 0 ? path.substring(dotIndex).toLowerCase() : "";
    }
}
