package interview.guide.modules.resume.messaging;

import java.util.UUID;

public interface ResumeAnalysisTaskPublisher {

    PublishReceipt publish(Long resumeId);

    record PublishReceipt(UUID messageId) {
    }
}
