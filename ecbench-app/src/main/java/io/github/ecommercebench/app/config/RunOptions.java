package io.github.ecommercebench.app.config;

/**
 * 一次基准运行所需的全部只读参数，是 CLI 与 Web 两条提交路径的共同契约。
 *
 * <p>方法名与 {@code CliRunOptions} 的访问器逐一同名同签名，因此 Picocli 注入的 CLI 参数对象与 Web 侧的 {@code
 * JobRequest} record 都能直接实现本接口，{@link ConfigurationMerger} 得以对两者一视同仁。
 */
public interface RunOptions {

    /** models_config.json 中的模型键。 */
    String model();

    /** 单次 LLM 调用的最大 token 数。 */
    int maxTokens();

    /** 单次 episode 的最大 agent 轮数。 */
    int maxTurns();

    /** 最大模拟天数。 */
    int maxDays();

    /** 初始银行余额。 */
    double initialBalance();

    /** 每日店铺运营费。 */
    double dailyFee();

    /** 上下文窗口 token 容量。 */
    int maxTokenCapacity();

    /** HuggingFace tokenizer 路径覆盖；null 表示走资源根默认。 */
    String tokenizerPath();

    /** 日志输出目录覆盖；null 表示走 {@code ./log}。 */
    String logDir();

    /** 预构建 job JSONL 路径覆盖；null 表示用内置 defaultJob。 */
    String jobFile();

    /** 并行运行次数。 */
    int runs();

    /** 确定性随机种子；null 表示由合并层回退到 42。 */
    Long seed();

    /** 数据目录覆盖；null 表示走资源根 {@code data/}。 */
    String dataDir();
}
