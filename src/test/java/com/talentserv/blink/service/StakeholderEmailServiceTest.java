package com.talentserv.blink.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.talentserv.blink.config.BlinkProperties;
import com.talentserv.blink.dto.StakeholderQuestionDeliveryResult;
import com.talentserv.blink.dto.StakeholderQuestionSendItem;
import com.talentserv.blink.dto.StakeholderQuestionsSendRequest;
import com.talentserv.blink.dto.StakeholderQuestionsSendResponse;

class StakeholderEmailServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void groupsByRecipientEmailCaseInsensitive() {
        List<StakeholderQuestionSendItem> items = List.of(
                item("q1", "alice@Example.com", "Alice"),
                item("q2", "bob@example.com", "Bob"),
                item("q3", "alice@example.com", "Alice")
        );
        Map<String, List<StakeholderQuestionSendItem>> grouped = StakeholderEmailService.groupByRecipient(items);
        assertThat(grouped).hasSize(2);
        assertThat(grouped.get("alice@example.com")).extracting(StakeholderQuestionSendItem::questionId)
                .containsExactly("q1", "q3");
        assertThat(grouped.get("bob@example.com")).extracting(StakeholderQuestionSendItem::questionId)
                .containsExactly("q2");
    }

    @Test
    void outboxModeWritesOneFilePerPerson() throws Exception {
        BlinkProperties props = new BlinkProperties();
        props.setSmtpHost("");
        props.setSmtpOutboxDir(tempDir.toString());
        StakeholderEmailService service = new StakeholderEmailService(props);

        StakeholderQuestionsSendResponse response = service.send(new StakeholderQuestionsSendRequest(List.of(
                item("q1", "alice@example.com", "Alice"),
                item("q2", "alice@example.com", "Alice"),
                item("q3", "bob@example.com", "Bob")
        )));

        assertThat(response.deliveryMode()).isEqualTo("outbox");
        assertThat(response.outboxDir()).isEqualTo(tempDir.toAbsolutePath().toString());
        assertThat(response.results()).hasSize(3);
        assertThat(response.results()).extracting(StakeholderQuestionDeliveryResult::status)
                .containsOnly("sent");
        try (var stream = Files.list(tempDir)) {
            assertThat(stream.filter(Files::isRegularFile).count()).isEqualTo(2);
        }
        String alice = Files.readString(
                Files.list(tempDir).filter(p -> p.getFileName().toString().contains("alice")).findFirst().orElseThrow()
        );
        assertThat(alice).contains("Question-Ids: q1,q2");
        assertThat(alice).contains("Alpha?");
        assertThat(alice).contains("Beta?");
    }

    @Test
    void sendTextWritesOutboxWithoutSmtp() throws Exception {
        BlinkProperties props = new BlinkProperties();
        props.setSmtpHost("");
        props.setSmtpOutboxDir(tempDir.toString());
        StakeholderEmailService service = new StakeholderEmailService(props);

        StakeholderEmailService.TextMailResult result = service.sendText(
                "ada@talentserv.co.in",
                "Your Blink sign-in code",
                "123456"
        );

        assertThat(result.deliveryMode()).isEqualTo("outbox");
        try (var stream = Files.list(tempDir)) {
            assertThat(stream.filter(Files::isRegularFile).count()).isEqualTo(1);
        }
        String saved = Files.readString(
                Files.list(tempDir).filter(Files::isRegularFile).findFirst().orElseThrow()
        );
        assertThat(saved).contains("123456");
        assertThat(saved).contains("To: ada@talentserv.co.in");
    }

    private static StakeholderQuestionSendItem item(String id, String email, String name) {
        return new StakeholderQuestionSendItem(
                id,
                id.equals("q1") ? "Alpha?" : id.equals("q2") ? "Beta?" : "Gamma?",
                email,
                name,
                "Product Owner",
                "Demo",
                null
        );
    }
}
