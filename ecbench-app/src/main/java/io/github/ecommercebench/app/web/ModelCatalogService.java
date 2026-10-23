package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.app.job.ModelKeyGuard;
import io.github.ecommercebench.domain.config.ModelConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 产出 {@code GET /api/models} 的 JSON：全部主模型键 + 各自的密钥就绪状态，外加独立的 NPC 段。
 *
 * <p>就绪判定完全委托 {@link ModelKeyGuard}，本类只做序列化，不另写一套推断规则。
 *
 * <p>NPC 段必须独立返回：{@code RunComponentFactory} 每个 run 都会额外为 {@code npcModel} 建客户端，因此即便主模型
 * 选的是 qwen，缺 {@code OPENAI_API_KEY} 一样跑不起来——前端据此单独告警。
 */
public final class ModelCatalogService {

    private final ModelKeyGuard keys;
    private final ObjectMapper mapper;

    public ModelCatalogService(ModelKeyGuard keys, ObjectMapper mapper) {
        this.keys = keys;
        this.mapper = mapper;
    }

    /** {@code {models:[…], npc:{…}}}，models 按 key 升序以便前端下拉稳定。 */
    public ObjectNode node() {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode models = root.putArray("models");
        List<ModelConfig> configs = new ArrayList<>(keys.registry().models().values());
        configs.sort(Comparator.comparing(ModelConfig::key));
        for (ModelConfig config : configs) {
            models.add(modelNode(config, keys.missingEnv(config.key())));
        }
        root.set("npc", modelNode(keys.registry().npcModel(), keys.npcMissingEnv()));
        return root;
    }

    private ObjectNode modelNode(ModelConfig config, String missingEnv) {
        ObjectNode node = mapper.createObjectNode();
        node.put("key", config.key());
        node.put("provider", config.provider());
        node.put("modelName", config.modelName());
        if (config.effort() == null) {
            node.putNull("effort");
        } else {
            node.put("effort", config.effort());
        }
        node.put("keyReady", missingEnv == null);
        if (missingEnv == null) {
            node.putNull("missingEnv");
        } else {
            node.put("missingEnv", missingEnv);
        }
        return node;
    }
}
