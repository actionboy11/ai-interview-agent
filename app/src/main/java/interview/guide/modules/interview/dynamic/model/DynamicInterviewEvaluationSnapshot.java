package interview.guide.modules.interview.dynamic.model;

import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.FormalAnswer;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.FrozenSnapshot;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Formal, published scope only. Never passes the future main-question pool to the evaluator. */
public record DynamicInterviewEvaluationSnapshot(int schemaVersion, String sessionId, UUID ownerId,
    long frozenVersion, List<Group> groups) {
  public DynamicInterviewEvaluationSnapshot { groups = List.copyOf(groups); }

  public static DynamicInterviewEvaluationSnapshot fromFrozen(FrozenSnapshot frozen) {
    if (frozen == null || frozen.schemaVersion() != 1) {
      throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "冻结快照版本无效");
    }
    var published = new LinkedHashMap<String, List<InterviewQuestionDTO>>();
    for (var question : frozen.questions()) {
      published.computeIfAbsent(question.slotId(), ignored -> new ArrayList<>())
          .add(question);
    }
    var pool = new LinkedHashMap<String, DynamicMainQuestion>();
    for (var main : frozen.mainQuestions()) {
      if (pool.putIfAbsent(main.slotId(), main) != null) {
        throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "冻结槽位重复");
      }
    }
    var indexes = new HashSet<Integer>();
    frozen.questions().forEach(question -> indexes.add(question.questionIndex()));
    if (frozen.answers().stream().anyMatch(answer -> !indexes.contains(answer.questionIndex()))) {
      throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "正式答案来源不属于已发布题目");
    }
    var groups = published.entrySet().stream().map(entry -> {
      var main = pool.get(entry.getKey());
      if (main == null) {
        throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "已发布题目缺少可信槽位");
      }
      var sourceIndexes = entry.getValue().stream().map(InterviewQuestionDTO::questionIndex).toList();
      return new Group(main, entry.getValue(), frozen.answers().stream()
          .filter(answer -> sourceIndexes.contains(answer.questionIndex())).toList());
    }).toList();
    return new DynamicInterviewEvaluationSnapshot(frozen.schemaVersion(), frozen.sessionId(),
        frozen.ownerId(), frozen.frozenVersion(), groups);
  }

  public record Group(DynamicMainQuestion mainQuestion, List<InterviewQuestionDTO> questions,
      List<FormalAnswer> answers) {
    public Group {
      questions = List.copyOf(questions);
      answers = List.copyOf(answers);
    }
  }
}
