package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.evaluation.ChatboxExtractor;
import io.github.ecommercebench.evaluation.io.AnalysisJsonReader;
import io.github.ecommercebench.evaluation.io.BalanceCsvReader;
import io.github.ecommercebench.evaluation.io.RunLayoutResolver;
import io.github.ecommercebench.evaluation.model.BalancePoint;
import io.github.ecommercebench.evaluation.model.ChatboxConversation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 读取单个 run 的下钻产物，供 dashboard 的「单 run 详情」视图使用。
 *
 * <p>复用 evaluation 模块的读取器：{@code analysis.json} 原样透传为 JSON 树；余额 CSV 经
 * {@link BalanceCsvReader} 转成序列（{@code total_balance} 为权威列）；chatbox 经
 * {@link ChatboxExtractor} 按供应商分组后，逐行解析为 {@code role/content}。产物路径由
 * {@link RunLayoutResolver} 解析，缺失文件抛 {@link ArtifactNotFoundException}。
 */
public final class RunArtifactService {

    private final RunLayoutResolver resolver = new RunLayoutResolver();
    private final AnalysisJsonReader analysisReader = new AnalysisJsonReader();
    private final BalanceCsvReader balanceReader = new BalanceCsvReader();
    private final ChatboxExtractor chatboxExtractor = new ChatboxExtractor();
    private final ObjectMapper mapper;

    public RunArtifactService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 原样返回该 run 的 analysis JSON 树。 */
    public JsonNode analysis(Path sessionDir, int run) {
        Path file = resolver.resolve(sessionDir).analysisJson(run);
        require(file);
        return analysisReader.read(file);
    }

    /** 把余额 CSV 转为 {@code {name, run, series:[...]}}，按日序列出。 */
    public JsonNode balance(Path sessionDir, int run) {
        Path file = resolver.resolve(sessionDir).balanceCsv(run);
        require(file);
        ObjectNode root = header(sessionDir, run);
        ArrayNode series = root.putArray("series");
        for (BalancePoint point : balanceReader.read(file)) {
            ObjectNode row = series.addObject();
            row.put("date", point.date());
            row.put("total_balance", point.totalBalance());
            row.put("bank_balance", point.bankBalance());
            row.put("platform_wallet", point.platformWallet());
            row.put("open_stores", point.openStores());
            row.put("warehouse_items", point.warehouseItems());
        }
        return root;
    }

    /** 按供应商分组返回 chatbox 对话（每条消息仅保留 role 与 content 文本）。 */
    public JsonNode chatbox(Path sessionDir, int run) {
        Path file = resolver.resolve(sessionDir).messagesJsonl(run);
        require(file);
        ObjectNode root = header(sessionDir, run);
        ArrayNode suppliers = root.putArray("suppliers");
        Map<String, ChatboxConversation> conversations = chatboxExtractor.extract(file);
        for (ChatboxConversation conversation : conversations.values()) {
            ObjectNode node = suppliers.addObject();
            node.put("supplier", conversation.supplier());
            ArrayNode messages = node.putArray("messages");
            for (String raw : conversation.rawLines()) {
                appendMessage(messages, raw);
            }
        }
        return root;
    }

    private ObjectNode header(Path sessionDir, int run) {
        ObjectNode root = mapper.createObjectNode();
        root.put("name", sessionDir.getFileName().toString());
        root.put("run", run);
        return root;
    }

    private void appendMessage(ArrayNode messages, String raw) {
        JsonNode parsed;
        try {
            parsed = mapper.readTree(raw);
        } catch (IOException exception) {
            return;
        }
        ObjectNode message = messages.addObject();
        message.put("role", parsed.path("role").asText(""));
        message.put("content", parsed.path("content").asText(""));
    }

    private static void require(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new ArtifactNotFoundException("产物不存在: " + file.getFileName());
        }
    }
}
