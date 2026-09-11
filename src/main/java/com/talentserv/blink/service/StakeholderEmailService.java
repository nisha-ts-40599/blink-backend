package com.talentserv.blink.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import com.talentserv.blink.config.BlinkProperties;
import com.talentserv.blink.dto.StakeholderQuestionDeliveryResult;
import com.talentserv.blink.dto.StakeholderQuestionSendItem;
import com.talentserv.blink.dto.StakeholderQuestionsSendRequest;
import com.talentserv.blink.dto.StakeholderQuestionsSendResponse;

@Service
public class StakeholderEmailService {

    private static final Logger log = LoggerFactory.getLogger(StakeholderEmailService.class);

    private final BlinkProperties properties;

    public StakeholderEmailService(BlinkProperties properties) {
        this.properties = properties;
    }

    public StakeholderQuestionsSendResponse send(StakeholderQuestionsSendRequest request) {
        List<StakeholderQuestionSendItem> questions =
                request == null || request.questions() == null ? List.of() : request.questions();
        Map<String, List<StakeholderQuestionSendItem>> byRecipient = groupByRecipient(questions);
        boolean smtp = properties.smtpConfigured();
        String mode = smtp ? "smtp" : "outbox";
        Path outbox = resolveOutboxDir();
        List<StakeholderQuestionDeliveryResult> results = new ArrayList<>();

        for (Map.Entry<String, List<StakeholderQuestionSendItem>> entry : byRecipient.entrySet()) {
            String email = entry.getKey();
            List<StakeholderQuestionSendItem> batch = entry.getValue();
            try {
                String subject = buildSubject(batch);
                String body = buildBody(batch);
                String method;
                String message;
                if (smtp) {
                    sendSmtp(email, subject, body);
                    method = "smtp";
                    message = "Sent via SMTP to " + email;
                } else {
                    Path file = writeOutbox(outbox, email, subject, body, batch);
                    method = "outbox";
                    message = "Saved to outbox: " + file.toAbsolutePath();
                }
                for (StakeholderQuestionSendItem item : batch) {
                    results.add(new StakeholderQuestionDeliveryResult(item.questionId(), "sent", method, message));
                }
            } catch (Exception ex) {
                log.warn("Stakeholder email failed for {}: {}", email, ex.toString());
                String err = ex.getMessage() == null ? "Email delivery failed." : ex.getMessage();
                for (StakeholderQuestionSendItem item : batch) {
                    results.add(new StakeholderQuestionDeliveryResult(
                            item.questionId(),
                            "failed",
                            mode,
                            err
                    ));
                }
            }
        }

        return new StakeholderQuestionsSendResponse(
                results,
                mode,
                smtp ? null : outbox.toAbsolutePath().toString()
        );
    }

    /** Visible for unit tests — groups by trimmed lower-case recipient email. */
    static Map<String, List<StakeholderQuestionSendItem>> groupByRecipient(List<StakeholderQuestionSendItem> questions) {
        Map<String, List<StakeholderQuestionSendItem>> grouped = new LinkedHashMap<>();
        for (StakeholderQuestionSendItem item : questions) {
            if (item == null) {
                continue;
            }
            String email = item.recipientEmail() == null ? "" : item.recipientEmail().trim().toLowerCase(Locale.ROOT);
            if (email.isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(email, key -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    private void sendSmtp(String to, String subject, String body) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.getSmtpHost());
        sender.setPort(properties.getSmtpPort());
        if (properties.getSmtpUsername() != null && !properties.getSmtpUsername().isBlank()) {
            sender.setUsername(properties.getSmtpUsername());
            sender.setPassword(properties.getSmtpPassword() == null ? "" : properties.getSmtpPassword());
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", properties.getSmtpUsername() != null && !properties.getSmtpUsername().isBlank());
        props.put("mail.smtp.starttls.enable", properties.isSmtpStartTls());
        props.put("mail.smtp.starttls.required", properties.isSmtpStartTls());

        SimpleMailMessage message = new SimpleMailMessage();
        String from = properties.getSmtpFrom() == null || properties.getSmtpFrom().isBlank()
                ? "blink@localhost"
                : properties.getSmtpFrom();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
    }

    private Path writeOutbox(
            Path outbox,
            String email,
            String subject,
            String body,
            List<StakeholderQuestionSendItem> batch
    ) throws IOException {
        Files.createDirectories(outbox);
        String safeEmail = email.replaceAll("[^a-zA-Z0-9._@-]", "_");
        String stamp = Instant.now().toString().replace(':', '-');
        Path file = outbox.resolve(stamp + "_" + safeEmail + ".txt");
        StringBuilder content = new StringBuilder();
        content.append("To: ").append(email).append('\n');
        content.append("Subject: ").append(subject).append('\n');
        content.append("Date: ").append(Instant.now()).append('\n');
        content.append("Question-Ids: ");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) {
                content.append(',');
            }
            content.append(batch.get(i).questionId());
        }
        content.append("\n\n").append(body);
        Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private Path resolveOutboxDir() {
        String configured = properties.getSmtpOutboxDir();
        if (configured == null || configured.isBlank()) {
            return Path.of(".blink-outbox");
        }
        return Path.of(configured);
    }

    private static String buildSubject(List<StakeholderQuestionSendItem> batch) {
        String project = batch.stream()
                .map(StakeholderQuestionSendItem::projectName)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse("Blink project");
        int n = batch.size();
        return "Blink clarification" + (n == 1 ? "" : "s") + " for " + project + " (" + n + ")";
    }

    private static String buildBody(List<StakeholderQuestionSendItem> batch) {
        StakeholderQuestionSendItem first = batch.get(0);
        String name = first.recipientName() == null || first.recipientName().isBlank()
                ? "there"
                : first.recipientName().trim();
        StringBuilder sb = new StringBuilder();
        sb.append("Hi ").append(name).append(",\n\n");
        sb.append("Blink needs your input on the following clarification");
        sb.append(batch.size() == 1 ? "" : "s").append(":\n\n");
        int index = 1;
        for (StakeholderQuestionSendItem item : batch) {
            sb.append(index++).append(". ");
            if (item.role() != null && !item.role().isBlank()) {
                sb.append('[').append(item.role().trim()).append("] ");
            }
            sb.append(item.question().trim()).append('\n');
            if (item.proposedAnswer() != null && !item.proposedAnswer().isBlank()) {
                sb.append("   Proposed answer: ").append(item.proposedAnswer().trim()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("Please reply with your confirmation or corrections.\n\n");
        sb.append("— Blink\n");
        return sb.toString();
    }
}
