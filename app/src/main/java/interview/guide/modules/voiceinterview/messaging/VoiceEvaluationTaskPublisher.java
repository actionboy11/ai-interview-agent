package interview.guide.modules.voiceinterview.messaging;

import java.util.UUID;

public interface VoiceEvaluationTaskPublisher {
    PublishReceipt publish(Long sessionId);

    record PublishReceipt(UUID messageId) {
    }
}
