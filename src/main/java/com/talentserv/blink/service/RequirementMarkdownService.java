package com.talentserv.blink.service;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class RequirementMarkdownService {

    public String toMarkdown(String projectName, MultipartFile file, String pastedText) {
        String title = projectName == null || projectName.isBlank() ? "Project" : projectName.trim();
        if (pastedText != null && !pastedText.isBlank()) {
            return withTitle(title, pastedText.strip());
        }
        if (file != null && !file.isEmpty()) {
            String extracted = extractText(file);
            if (extracted != null && !extracted.isBlank()) {
                return withTitle(title, extracted.strip());
            }
            return """
                    # %s

                    Requirement document uploaded: `%s`

                    Blink could not extract text from this file. Replace this page with the full
                    requirements before running the SDLC workflow.
                    """.formatted(title, file.getOriginalFilename());
        }
        throw new IllegalArgumentException("Upload a document or paste requirements.");
    }

    private static String withTitle(String title, String body) {
        if (body.startsWith("#")) {
            return body;
        }
        return "# " + title + "\n\n" + body + "\n";
    }

    private static String extractText(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            if (!looksLikeText(bytes)) {
                return null;
            }
            Charset charset = detectCharset(bytes);
            return new String(bytes, charset);
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean looksLikeText(byte[] bytes) {
        int limit = Math.min(bytes.length, 4096);
        int suspicious = 0;
        for (int i = 0; i < limit; i++) {
            byte b = bytes[i];
            if (b == 0) {
                return false;
            }
            if (b < 0x09) {
                suspicious++;
            }
        }
        return suspicious < limit / 10;
    }

    private static Charset detectCharset(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            return StandardCharsets.UTF_8;
        }
        return StandardCharsets.UTF_8;
    }
}
