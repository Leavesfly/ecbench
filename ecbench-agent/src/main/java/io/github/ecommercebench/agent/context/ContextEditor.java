package io.github.ecommercebench.agent.context;

import io.github.ecommercebench.domain.config.ContextConfig;
import io.github.ecommercebench.llm.model.ChatMessage;
import io.github.ecommercebench.llm.model.ChatRole;
import java.util.ArrayList;
import java.util.List;

/** 按完整工具调用组清除最旧上下文，同时保护 system、首条 user 和最近工具组。 */
public final class ContextEditor {

  private final TokenCounter tokenCounter;

  public ContextEditor(TokenCounter tokenCounter) {
    this.tokenCounter = tokenCounter;
  }

  public ContextEditResult edit(
      List<ChatMessage> messages, ContextConfig config, int maximumCapacity) {
    List<ChatMessage> edited = new ArrayList<>(messages);
    int current = edited.stream().mapToInt(tokenCounter::count).sum();
    if (current < config.trigger()) {
      return new ContextEditResult(edited, status(current, maximumCapacity), 0, current);
    }

    List<Group> activeGroups =
        clearableGroups(edited).stream()
            .filter(group -> !edited.get(group.assistantIndex()).cleared())
            .toList();
    int clearableCount =
        config.keepToolUse() == 0
            ? activeGroups.size()
            : Math.max(0, activeGroups.size() - config.keepToolUse());
    int target = Math.max(config.clearAtLeast(), current - config.trigger());
    int freed = 0;
    for (int index = 0; index < clearableCount && freed < target; index++) {
      Group group = activeGroups.get(index);
      freed += clear(edited, group.assistantIndex());
      for (int toolIndex : group.toolIndices()) {
        freed += clear(edited, toolIndex);
      }
    }
    int active = current - freed;
    String warning =
        (freed > 0 ? "<system_warning>" + freed + " oldest tokens cleared.</system_warning>\n" : "")
            + status(active, maximumCapacity);
    return new ContextEditResult(edited, warning, freed, active);
  }

  private int clear(List<ChatMessage> messages, int index) {
    ChatMessage message = messages.get(index);
    if (message.cleared()) {
      return 0;
    }
    int tokens = tokenCounter.count(message);
    messages.set(index, message.markCleared());
    return tokens;
  }

  private List<Group> clearableGroups(List<ChatMessage> messages) {
    List<Group> groups = new ArrayList<>();
    int index = 2;
    while (index < messages.size()) {
      ChatMessage message = messages.get(index);
      if (hasToolCalls(message)) {
        List<Integer> toolIndices = new ArrayList<>();
        int next = index + 1;
        while (next < messages.size() && isToolResponse(messages.get(next))) {
          toolIndices.add(next++);
        }
        if (!toolIndices.isEmpty()) {
          groups.add(new Group(index, List.copyOf(toolIndices)));
          index = next;
          continue;
        }
      }
      index++;
    }
    return groups;
  }

  private boolean hasToolCalls(ChatMessage message) {
    return message.role() == ChatRole.ASSISTANT
        && (!message.toolCalls().isEmpty()
            || (message.content() != null && message.content().contains("<tool_call>")));
  }

  private boolean isToolResponse(ChatMessage message) {
    return message.role() == ChatRole.TOOL
        || (message.role() == ChatRole.USER
            && ((message.content() != null && message.content().contains("<tool_response>"))
                || message.toolCallId() != null));
  }

  private String status(int usage, int maximumCapacity) {
    double percentage = maximumCapacity <= 0 ? 0.0 : (double) usage / maximumCapacity;
    return "<system_warning>Token usage: %d/%d tokens (%.0f%%); %d remaining</system_warning>"
        .formatted(usage, maximumCapacity, percentage * 100.0, maximumCapacity - usage);
  }

  private record Group(int assistantIndex, List<Integer> toolIndices) {}
}
