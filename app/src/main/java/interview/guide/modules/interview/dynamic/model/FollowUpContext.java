package interview.guide.modules.interview.dynamic.model;

import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.DecisionContextSnapshot;

/** Immutable current-group input, using the persisted schema rather than a second transcript. */
public record FollowUpContext(DecisionContextSnapshot snapshot) {}
