package interview.guide.modules.interview.planning;

import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Matches only facts present in the resume to categories supplied by the selected Skill. */
public final class ResumeFactGrounding {
  private static final Pattern LATIN_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9.+#-]*");
  private static final Map<String, List<String>> ALIASES = Map.of(
      "PYTHON_BASIC", List.of("python"),
      "DJANGO_FLASK", List.of("django", "flask"),
      "REACT_VUE", List.of("react", "vue"),
      "DATABASE", List.of("postgresql", "postgres", "mysql", "sqlite", "sql", "数据库"),
      "CACHE", List.of("redis", "缓存", "cache"),
      "PROJECT", List.of("项目", "project"));

  public Set<String> grounded(String resumeText, List<SkillCategoryDTO> categories) {
    if (resumeText == null || resumeText.isBlank() || categories == null) {
      return Set.of();
    }
    Set<String> matched = new LinkedHashSet<>();
    for (SkillCategoryDTO category : categories) {
      if (category == null || category.key() == null) {
        continue;
      }
      if (tokens(category).stream().anyMatch(token -> containsFact(resumeText, token))) {
        matched.add(category.key());
      }
    }
    return Set.copyOf(matched);
  }

  private List<String> tokens(SkillCategoryDTO category) {
    List<String> values = new ArrayList<>(ALIASES.getOrDefault(category.key(), List.of()));
    String label = category.label();
    if (label != null) {
      if (label.codePointCount(0, label.length()) >= 2 && !label.contains("/")) {
        values.add(label);
      }
      var latin = LATIN_TOKEN.matcher(label);
      while (latin.find()) {
        if (latin.group().length() >= 3) {
          values.add(latin.group());
        }
      }
    }
    if (category.key().matches("[A-Za-z][A-Za-z0-9]{2,}")) {
      values.add(category.key());
    }
    return values;
  }

  private boolean containsFact(String text, String token) {
    if (token == null || token.isBlank()) {
      return false;
    }
    String lower = text.toLowerCase(Locale.ROOT);
    String candidate = token.toLowerCase(Locale.ROOT);
    if (!candidate.matches("[a-z0-9.+#-]+")) {
      return lower.contains(candidate);
    }
    int from = 0;
    while ((from = lower.indexOf(candidate, from)) >= 0) {
      int end = from + candidate.length();
      boolean startBoundary = from == 0 || !isIdentifierContinuation(lower.charAt(from - 1));
      boolean endBoundary = end == lower.length()
          || !isIdentifierContinuation(lower.charAt(end));
      if (startBoundary && endBoundary) {
        return true;
      }
      from = end;
    }
    return false;
  }

  private boolean isIdentifierContinuation(char character) {
    return Character.isDigit(character) || character == '_'
        || Character.UnicodeScript.of(character) == Character.UnicodeScript.LATIN;
  }
}
