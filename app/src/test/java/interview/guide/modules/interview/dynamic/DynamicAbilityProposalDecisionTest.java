package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.memory.model.AbilityLevel;
import interview.guide.modules.memory.model.AbilityProposalStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("动态证据复用既有画像建议确认流程")
class DynamicAbilityProposalDecisionTest extends DynamicAbilityTestSupport {
  @Test
  @DisplayName("接受、编辑接受和拒绝均保留支持来源，追问不增加确认场次")
  void acceptEditRejectRetainSupportingSources() {
    for (int action = 0; action < 3; action++) {
      var f = fixture(new GroupSpec(3, 90, SubmissionIntent.ANSWER),
          new GroupSpec(1, 50, SubmissionIntent.ANSWER));
      derivation.derive(f.sessionId(), UUID.randomUUID());
      var proposal = proposals.findByOwnerIdAndInterviewSessionId(f.ownerId(), f.sessionDbId()).getFirst();
      var before = responses(f);
      if (action == 0) {
        var profile = commands.accept(f.ownerId(), proposal.getId(), proposal.getVersion());
        assertThat(profile.score()).isEqualTo(70);
        assertThat(profile.confirmedEvidenceCount()).isEqualTo(2);
        assertThat(profile.confidence()).isEqualTo(0.5);
        assertThat(evidence.countDistinctAcceptedSessionsByCategory(f.ownerId(), "java-backend",
            List.of(AbilityProposalStatus.ACCEPTED, AbilityProposalStatus.ACCEPTED_WITH_EDIT)))
            .singleElement().satisfies(count -> {
              assertThat(count.getCategoryKey()).isEqualTo("JAVA");
              assertThat(count.getSessionCount()).isOne();
            });
      } else if (action == 1) {
        var profile = commands.acceptWithEdit(f.ownerId(), proposal.getId(), proposal.getVersion(),
            AbilityLevel.STRONG, "人工确认具体观察");
        assertThat(profile.level()).isEqualTo(AbilityLevel.STRONG);
        assertThat(profile.score()).isEqualTo(70);
        assertThat(profile.summary()).isEqualTo("人工确认具体观察");
      } else {
        commands.reject(f.ownerId(), proposal.getId(), proposal.getVersion());
        assertThat(profiles.findByOwnerIdAndSkillIdAndCategoryKey(f.ownerId(), "java-backend", "JAVA"))
            .isEmpty();
      }
      var after = responses(f);
      assertThat(after).hasSize(2);
      assertThat(after.getFirst().get("supportingAnswerIds"))
          .isEqualTo(f.answerIds().subList(0, 3)).isEqualTo(before.getFirst().get("supportingAnswerIds"));
      assertThat(after.getLast().get("supportingAnswerIds")).isEqualTo(f.answerIds().subList(3, 4));
      assertThat(after.getFirst().get("proposalStatus")).isEqualTo((action == 0
          ? AbilityProposalStatus.ACCEPTED : action == 1 ? AbilityProposalStatus.ACCEPTED_WITH_EDIT
              : AbilityProposalStatus.REJECTED).name());
    }
  }

  @Test
  @DisplayName("实际删除入口按既有顺序原子清理组支持来源并重放画像")
  void deletionClearsSupportingSources() {
    var f = fixture(new GroupSpec(3, 80, SubmissionIntent.ANSWER));
    derivation.derive(f.sessionId(), UUID.randomUUID());
    var proposal = proposals.findByOwnerIdAndInterviewSessionId(f.ownerId(), f.sessionDbId()).getFirst();
    commands.accept(f.ownerId(), proposal.getId(), proposal.getVersion());
    deletion.deleteOwnedSessions(f.ownerId(), List.of(f.sessionDbId()));
    assertThat(items(f)).isEmpty();
    assertThat(profiles.findByOwnerIdAndSkillIdAndCategoryKey(f.ownerId(), "java-backend", "JAVA"))
        .get().satisfies(profile -> assertThat(profile.getConfirmedEvidenceCount()).isZero());
  }
}
