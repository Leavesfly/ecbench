package io.github.ecommercebench.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.llm.model.ToolDefinition;

import java.io.IOException;
import java.io.InputStream;

/**
 * 从 classpath 资源 `tool-schema/<name>.json` 加载工具定义。
 *
 * <p>这些 JSON 由 Python `EcommerceBaseTool.get_info()` 逐一导出，是工具名称、描述与输入 Schema 的唯一真相， 从而保证暴露给模型的函数定义与
 * Python 逐字节一致，避免在 Java 中重新誊写长描述造成漂移。
 */
public final class ToolSchemas {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolSchemas() {
    }

    public static ToolDefinition load(String name) {
        String resource = "/tool-schema/" + name + ".json";
        try (InputStream in = ToolSchemas.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少工具 schema 资源: " + resource);
            }
            JsonNode function = MAPPER.readTree(in).path("function");
            return new ToolDefinition(
                    function.path("name").asText(),
                    function.path("description").asText(),
                    function.path("parameters"));
        } catch (IOException e) {
            throw new IllegalStateException("工具 schema 解析失败: " + resource, e);
        }
    }
}
