package io.github.ecommercebench.app.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.domain.config.ModelConfig;
import io.github.ecommercebench.domain.config.ModelRegistry;
import io.github.ecommercebench.domain.config.ModelRegistryLoader;
import io.github.ecommercebench.llm.provider.ProviderResolver;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「某个模型键现在能不能跑」的唯一判定实现。
 *
 * <p>{@code JobService} 用它做提交拦截，{@code ModelCatalogService} 用它标注下拉列表，两处共用同一份推断，从而杜绝
 * 「表单说就绪、真跑却缺 key」的漂移。
 *
 * <p>变量名的推断顺序与 {@code ProviderResolver.resolve} 一致：有 {@code apiKeyExpression} 时按 {@code ${VAR}}
 * 展开，否则取 provider 预设名（经 {@link ProviderResolver#keyEnvironmentFor}，不另抄一份对照表）。但<b>就绪与否直接查
 * 合并后的环境变量视图</b>，而不是靠捕获 {@code ConfigurationException}——后者的错误信息对 {@code openai-compatible}
 * 加自定义表达式的模型会误报成 {@code OPENAI_API_KEY}（既有缺陷，本次不修）。
 */
public final class ModelKeyGuard {

    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    private final ModelRegistry registry;
    private final ProviderResolver resolver;
    private final Map<String, String> env;

    public ModelKeyGuard(Path modelsConfigPath, Map<String, String> env, ObjectMapper mapper) {
        this.registry = loadRegistry(modelsConfigPath, mapper);
        this.resolver = new ProviderResolver(env);
        this.env = Map.copyOf(env);
    }

    /** 已加载的模型注册表，供列表端点枚举模型键。 */
    public ModelRegistry registry() {
        return registry;
    }

    /** 该模型键缺失的环境变量名；已就绪返回 null。模型键未知抛 {@code IllegalArgumentException}。 */
    public String missingEnv(String modelKey) {
        return missingEnvFor(requireModel(modelKey));
    }

    /** 供应商 NPC 模型缺失的环境变量名；已就绪返回 null。 */
    public String npcMissingEnv() {
        return missingEnvFor(registry.npcModel());
    }

    /**
     * 校验主模型与 NPC 模型均已就绪，否则抛 {@code IllegalArgumentException}。
     *
     * <p>两个模型都要查：{@code RunComponentFactory} 每个 run 都会额外为 {@code npcModel} 建一个客户端，因此即便
     * 主模型选的是 qwen，缺 {@code OPENAI_API_KEY} 一样跑不起来。
     */
    public void requireReady(String modelKey) {
        ModelConfig config = requireModel(modelKey);
        String missing = missingEnvFor(config);
        if (missing != null) {
            throw new IllegalArgumentException("模型 " + modelKey + " 缺少环境变量 " + missing);
        }
        String npcMissing = missingEnvFor(registry.npcModel());
        if (npcMissing != null) {
            throw new IllegalArgumentException(
                    "供应商模型 npc_tools 缺少环境变量 " + npcMissing + "（每个 run 都需要它渲染对话）");
        }
    }

    /** 路径为 null 时给出空注册表（仅供测试注入）；生产路径总是非 null。 */
    private static ModelRegistry loadRegistry(Path modelsConfigPath, ObjectMapper mapper) {
        if (modelsConfigPath == null) {
            return new ModelRegistry(
                    Map.of(),
                    new ModelConfig("npc_tools", "openai", "none", null, null, null, null, null, null));
        }
        return new ModelRegistryLoader(mapper).load(modelsConfigPath);
    }

    private ModelConfig requireModel(String modelKey) {
        ModelConfig config = registry.models().get(modelKey);
        if (config == null) {
            throw new IllegalArgumentException(
                    "models_config.json 中不存在模型: " + modelKey + "（可用 " + registry.models().size() + " 个）");
        }
        return config;
    }

    private String missingEnvFor(ModelConfig config) {
        String variable = envVarName(config);
        if (variable == null) {
            return null;
        }
        String value = env.get(variable);
        return value == null || value.isBlank() ? variable : null;
    }

    /** 有 ${VAR} 表达式则取其中的变量名；否则取 provider 预设名；都取不到则视为无需密钥。 */
    private String envVarName(ModelConfig config) {
        String expression = config.apiKeyExpression();
        if (expression != null && !expression.isBlank()) {
            Matcher matcher = EXPRESSION.matcher(expression);
            return matcher.find() ? matcher.group(1) : null;
        }
        return resolver.keyEnvironmentFor(config.provider());
    }
}
