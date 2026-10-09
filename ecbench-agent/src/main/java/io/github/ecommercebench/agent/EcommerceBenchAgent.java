package io.github.ecommercebench.agent;

import io.github.ecommercebench.agent.context.ContextEditResult;
import io.github.ecommercebench.agent.context.ContextEditor;
import io.github.ecommercebench.agent.context.TokenCounter;
import io.github.ecommercebench.agent.tool.EcommerceToolManager;
import io.github.ecommercebench.agent.tool.ToolExecutionResult;
import io.github.ecommercebench.domain.config.ContextConfig;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.llm.LlmClient;
import io.github.ecommercebench.llm.model.ChatMessage;
import io.github.ecommercebench.llm.model.LlmRequest;
import io.github.ecommercebench.llm.model.LlmResponse;
import io.github.ecommercebench.simulation.SimulationEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * E-Commerce Bench 的工具调用主循环；端口 Python `agent/ecommerce_agent.py` 的 {@code run()}。
 *
 * <p>每轮：上下文裁剪→（裁剪则通知观察者）→构建仅含未清除消息的请求→模型生成→追加 assistant→无工具调用则计连续空转（达阈值终止，否则 nudge） →有工具调用则顺序执行并追加
 * role=tool→检查引擎终止（破产/跑满天数）与轮数上限。模型调用异常按 Python 行为优雅终止为 {@link
 * TerminationReason#LLM_ERROR}；其余未预期异常经 {@link RunObserver#onFailure} 后抛出，交由上层隔离。
 */
public final class EcommerceBenchAgent {

    private static final int MAX_NO_TOOL_CALLS = 3;
    private static final String SEED_USER_MESSAGE = "You are running an e-commerce business.";
    private static final String TASK = "agent_multiturn/long_horizon/ecommerce_bench";
    private static final String DATA_SOURCE = "ecommerce_bench";
    private static final int MAX_TOOL_RESPONSE_CHARS = 64 * 1024;

    private final LlmClient llm;
    private final String model;
    private final EcommerceToolManager toolManager;
    private final ContextEditor contextEditor;
    private final TokenCounter tokenCounter;
    private final SimulationEngine engine;
    private final RunObserver observer;
    private final RunConfig runConfig;
    private final ContextConfig contextConfig;

    public EcommerceBenchAgent(
            LlmClient llm,
            String model,
            EcommerceToolManager toolManager,
            ContextEditor contextEditor,
            TokenCounter tokenCounter,
            SimulationEngine engine,
            RunObserver observer,
            RunConfig runConfig,
            ContextConfig contextConfig) {
        this.llm = llm;
        this.model = model;
        this.toolManager = toolManager;
        this.contextEditor = contextEditor;
        this.tokenCounter = tokenCounter;
        this.engine = engine;
        this.observer = observer;
        this.runConfig = runConfig;
        this.contextConfig = contextConfig;
    }

    /**
     * 复现 Python `_build_default_job`：渲染系统提示词 + 种子用户消息 + 工具 schema + 各类上限。
     */
    public RunJob defaultJob() {
        String systemPrompt =
                Prompts.renderSystemPrompt(
                        runConfig.maxTokenCapacity(),
                        contextConfig,
                        runConfig.maxDays(),
                        (int) runConfig.initialBalance().amount().doubleValue());
        List<ChatMessage> initialMessages =
                List.of(ChatMessage.system(systemPrompt), ChatMessage.user(SEED_USER_MESSAGE));
        return new RunJob(
                TASK,
                0,
                DATA_SOURCE,
                initialMessages,
                toolManager.definitions(),
                runConfig.maxTurns(),
                runConfig.maxDays(),
                runConfig.maxTokenCapacity(),
                MAX_TOOL_RESPONSE_CHARS);
    }

    /**
     * 运行一次完整 episode；job 为 null 时使用 {@link #defaultJob()}。
     */
    public RunResult run(RunJob job) {
        RunJob effective = job != null ? job : defaultJob();
        observer.onRunStart(effective);
        List<ChatMessage> messages = new ArrayList<>(effective.initialMessages());
        int maxTurns = effective.maxTurns();
        int turn = 0;
        int consecutiveNoTool = 0;
        int contextClearCount = 0;
        int contextTokensFreedTotal = 0;
        TerminationReason reason = null;
        String detail = null;

        try {
            while (turn < maxTurns) {
                turn++;
                observer.onTurnStart(turn);

                ContextEditResult edit =
                        contextEditor.edit(messages, contextConfig, effective.maxTokenCapacity());
                messages = new ArrayList<>(edit.messages());
                if (edit.tokensFreed() > 0) {
                    contextClearCount++;
                    contextTokensFreedTotal += edit.tokensFreed();
                    observer.onContextTruncation(turn, edit.tokensFreed());
                }

                LlmResponse response;
                try {
                    response =
                            llm.generate(
                                    new LlmRequest(
                                            model,
                                            providerMessages(messages, edit),
                                            effective.toolSchemas(),
                                            runConfig.maxTokens(),
                                            null,
                                            null,
                                            null));
                } catch (RuntimeException exception) {
                    reason = TerminationReason.LLM_ERROR;
                    detail = "llm_error: " + exception.getMessage();
                    break;
                }

                ChatMessage assistant = response.toAssistantMessage();
                messages.add(assistant);
                observer.onAssistantMessage(assistant);

                if (response.toolCalls().isEmpty()) {
                    consecutiveNoTool++;
                    if (consecutiveNoTool >= MAX_NO_TOOL_CALLS) {
                        reason = TerminationReason.NO_TOOL_CALLS;
                        detail = "agent_idle: no tool calls for " + consecutiveNoTool + " consecutive turns";
                        break;
                    }
                    messages.add(ChatMessage.user(nudge(consecutiveNoTool)));
                    continue;
                }
                consecutiveNoTool = 0;

                List<ToolExecutionResult> results = toolManager.execute(response.toolCalls());
                String gauge = tokenGauge(messages, effective.maxTokenCapacity());
                for (ToolExecutionResult result : results) {
                    messages.add(
                            ChatMessage.tool(
                                    result.toolCallId(),
                                    truncate(result.content(), effective.maxToolResponseChars()) + gauge));
                }
                observer.onToolResults(results);

                if (engine.state().terminated()) {
                    String engineReason = engine.state().terminationReason();
                    reason = TerminationReason.fromEngineReason(engineReason);
                    detail = engineReason;
                    break;
                }
                if (turn >= maxTurns) {
                    reason = TerminationReason.MAX_TURNS_REACHED;
                    detail = "max_turns_reached";
                    break;
                }
            }
        } catch (RuntimeException exception) {
            observer.onFailure(exception);
            throw exception;
        }

        if (reason == null) {
            reason = TerminationReason.MAX_TURNS_REACHED;
            detail = "max_turns_reached";
        }
        RunResult result =
                new RunResult(
                        reason,
                        detail,
                        turn,
                        messages,
                        engine.state().dayCount(),
                        engine.currentDate().toString(),
                        engine.state().totalAssets().amount().doubleValue(),
                        contextClearCount,
                        contextTokensFreedTotal);
        observer.onRunComplete(result);
        return result;
    }

    private List<ChatMessage> providerMessages(List<ChatMessage> messages, ContextEditResult edit) {
        List<ChatMessage> provider = new ArrayList<>();
        for (ChatMessage message : messages) {
            message.forProvider().ifPresent(provider::add);
        }
        if (edit.tokensFreed() > 0 && !provider.isEmpty()) {
            int last = provider.size() - 1;
            provider.set(last, appendContent(provider.get(last), edit.warning()));
        }
        return provider;
    }

    private String tokenGauge(List<ChatMessage> messages, int capacity) {
        int used = 0;
        for (ChatMessage message : messages) {
            if (!message.cleared()) {
                used += tokenCounter.count(message);
            }
        }
        int percent = capacity > 0 ? used * 100 / capacity : 0;
        return "\n<system_warning>Token usage: "
                + used
                + "/"
                + capacity
                + " tokens ("
                + percent
                + "%); "
                + (capacity - used)
                + " remaining</system_warning>";
    }

    private static ChatMessage appendContent(ChatMessage message, String suffix) {
        String content = message.content() == null ? "" : message.content();
        String separator = content.isEmpty() ? "" : "\n\n";
        return new ChatMessage(
                message.role(),
                content + separator + suffix,
                message.toolCalls(),
                message.toolCallId(),
                message.reasoningContent(),
                message.reasoningItems(),
                message.cleared(),
                message.metadata());
    }

    private static String truncate(String content, int maxChars) {
        if (content == null) {
            return "";
        }
        return content.length() > maxChars
                ? content.substring(0, maxChars) + "\n... [truncated]"
                : content;
    }

    private static String nudge(int consecutive) {
        return "You did not call any tool. You must call a tool to operate the business and advance "
                + "time. Please call a tool now. (warning "
                + consecutive
                + "/"
                + MAX_NO_TOOL_CALLS
                + ": after "
                + MAX_NO_TOOL_CALLS
                + " consecutive turns without a tool call the episode will be terminated as a failure.)";
    }
}
