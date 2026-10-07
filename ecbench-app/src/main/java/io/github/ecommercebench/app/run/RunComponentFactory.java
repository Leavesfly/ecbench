package io.github.ecommercebench.app.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.agent.EcommerceBenchAgent;
import io.github.ecommercebench.agent.RunJob;
import io.github.ecommercebench.agent.chat.ChatboxCoordinator;
import io.github.ecommercebench.agent.chat.LlmSupplierReplyRenderer;
import io.github.ecommercebench.agent.chat.LlmVipConsentClassifier;
import io.github.ecommercebench.agent.chat.SimulationOrderExecutionAdapter;
import io.github.ecommercebench.agent.chat.SupplierReplyRenderer;
import io.github.ecommercebench.agent.context.ContextEditor;
import io.github.ecommercebench.agent.context.HuggingFaceTokenCounter;
import io.github.ecommercebench.agent.context.TokenCounter;
import io.github.ecommercebench.agent.memory.InMemoryMemoryStore;
import io.github.ecommercebench.agent.memory.MemoryStore;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.EcommerceToolManager;
import io.github.ecommercebench.agent.tool.ToolRegistry;
import io.github.ecommercebench.agent.tool.impl.ChatboxTool;
import io.github.ecommercebench.agent.tool.impl.CheckBalanceTool;
import io.github.ecommercebench.agent.tool.impl.CheckStoreStatusTool;
import io.github.ecommercebench.agent.tool.impl.CheckWarehouseTool;
import io.github.ecommercebench.agent.tool.impl.CloseStoreTool;
import io.github.ecommercebench.agent.tool.impl.JoinPromotionTool;
import io.github.ecommercebench.agent.tool.impl.ListProductsTool;
import io.github.ecommercebench.agent.tool.impl.MarketSearchTool;
import io.github.ecommercebench.agent.tool.impl.OpenStoreTool;
import io.github.ecommercebench.agent.tool.impl.OperateMemoryTool;
import io.github.ecommercebench.agent.tool.impl.ReturnToWarehouseTool;
import io.github.ecommercebench.agent.tool.impl.SetPricesTool;
import io.github.ecommercebench.agent.tool.impl.ShipOrdersTool;
import io.github.ecommercebench.agent.tool.impl.StockStoreTool;
import io.github.ecommercebench.agent.tool.impl.SupplierSearchTool;
import io.github.ecommercebench.agent.tool.impl.TraceReturnSourcesTool;
import io.github.ecommercebench.agent.tool.impl.WaitForNextDayTool;
import io.github.ecommercebench.agent.tool.impl.WithdrawTool;
import io.github.ecommercebench.app.config.BenchmarkOptions;
import io.github.ecommercebench.app.config.LlmClientProvider;
import io.github.ecommercebench.app.log.CompositeRunObserver;
import io.github.ecommercebench.app.log.RunDirectory;
import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CsvCatalogLoader;
import io.github.ecommercebench.domain.catalog.MarketGuidance;
import io.github.ecommercebench.domain.catalog.StorePlaybookLoader;
import io.github.ecommercebench.domain.config.ModelConfig;
import io.github.ecommercebench.domain.config.ModelRegistry;
import io.github.ecommercebench.domain.config.ModelRegistryLoader;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.llm.LlmClient;
import io.github.ecommercebench.opponent.chat.ConversationStore;
import io.github.ecommercebench.opponent.config.SupplierPolicy;
import io.github.ecommercebench.opponent.kernel.KernelManager;
import io.github.ecommercebench.opponent.metrics.NegotiationTracker;
import io.github.ecommercebench.opponent.order.OrderProcessor;
import io.github.ecommercebench.opponent.parser.NegotiationBlockParser;
import io.github.ecommercebench.opponent.scam.VipConsentClassifier;
import io.github.ecommercebench.simulation.SimulationEngine;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 为每个 run 装配一整套隔离组件的工厂。
 *
 * <p>只读单例（CatalogData/MarketGuidance/ModelRegistry/TokenCounter/RunDirectory）在同一 options 下惰性初始化并跨
 * run 共享；
 * 可变状态（SimulationEngine、KernelManager、MemoryStore、ConversationStore、LlmClient、ContextEditor、18 个工具、
 * ToolManager、Agent、Observer）每次 {@link #create} 全部新建，从而并行 run 之间完全隔离。随机种子按 {@code seed + index} 派生，
 * 保证同 run 可重现且各 run 不同。
 */
public final class RunComponentFactory {

  private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

  private final ObjectMapper objectMapper;
  private final LlmClientProvider llmClientProvider;

  private CatalogData catalog;
  private MarketGuidance guidance;
  private ModelRegistry modelRegistry;
  private TokenCounter tokenCounter;
  private RunDirectory runDirectory;
  private List<RunJob> fileJobs;

  public RunComponentFactory(ObjectMapper objectMapper, LlmClientProvider llmClientProvider) {
    this.objectMapper = objectMapper;
    this.llmClientProvider = llmClientProvider;
  }

  public RunComponents create(int index, BenchmarkOptions options) {
    CatalogData catalog = sharedCatalog(options);
    MarketGuidance guidance = sharedGuidance(options);
    ModelRegistry registry = sharedRegistry(options);
    TokenCounter tokenCounter = sharedTokenCounter(options);
    RunDirectory directory = sharedRunDirectory(options);

    RandomStreams random = new RandomStreams(options.runConfig().seed() + index);
    SimulationEngine engine = new SimulationEngine(catalog, options.runConfig(), random);

    SupplierPolicy policy = new SupplierPolicy();
    NegotiationTracker tracker = new NegotiationTracker(random);
    KernelManager kernelManager = new KernelManager(catalog, policy, random, tracker);
    OrderProcessor orderProcessor = new OrderProcessor(catalog, policy, random);
    ConversationStore conversations = new ConversationStore();

    ModelConfig mainConfig =
        withEffort(registry.resolve(options.runConfig().modelKey()), options.effort());
    LlmClient mainClient = llmClientProvider.create(mainConfig);
    ModelConfig npcConfig = registry.npcModel();
    LlmClient npcClient = llmClientProvider.create(npcConfig);

    MemoryStore memoryStore = new InMemoryMemoryStore();
    ContextEditor contextEditor = new ContextEditor(tokenCounter);

    SupplierReplyRenderer renderer =
        new LlmSupplierReplyRenderer(catalog, conversations, npcClient, npcConfig.modelName());
    VipConsentClassifier vipClassifier =
        new LlmVipConsentClassifier(npcClient, npcConfig.modelName());
    ChatboxCoordinator coordinator =
        new ChatboxCoordinator(
            catalog,
            engine,
            conversations,
            new NegotiationBlockParser(objectMapper),
            kernelManager,
            orderProcessor,
            new SimulationOrderExecutionAdapter(engine),
            renderer,
            vipClassifier,
            objectMapper);

    ToolRegistry toolRegistry = new ToolRegistry(buildTools(guidance, memoryStore, coordinator));
    EcommerceToolManager toolManager = new EcommerceToolManager(toolRegistry, engine, objectMapper);

    CompositeRunObserver observer =
        new CompositeRunObserver(directory, index, engine, objectMapper, tracker::aggregate);
    EcommerceBenchAgent agent =
        new EcommerceBenchAgent(
            mainClient,
            mainConfig.modelName(),
            toolManager,
            contextEditor,
            tokenCounter,
            engine,
            observer,
            options.runConfig(),
            options.contextConfig());

    return new RunComponents(engine, agent, observer, resolveJob(options));
  }

  private List<EcommerceTool> buildTools(
      MarketGuidance guidance, MemoryStore memoryStore, ChatboxCoordinator coordinator) {
    return List.of(
        new ChatboxTool(coordinator),
        new CheckBalanceTool(),
        new CheckStoreStatusTool(),
        new CheckWarehouseTool(),
        new CloseStoreTool(),
        new JoinPromotionTool(),
        new ListProductsTool(),
        new MarketSearchTool(guidance),
        new OpenStoreTool(),
        new OperateMemoryTool(memoryStore),
        new ReturnToWarehouseTool(),
        new SetPricesTool(),
        new ShipOrdersTool(),
        new StockStoreTool(),
        new SupplierSearchTool(),
        new TraceReturnSourcesTool(),
        new WaitForNextDayTool(),
        new WithdrawTool());
  }

  private RunJob resolveJob(BenchmarkOptions options) {
    Path jobFile = options.runConfig().jobFile();
    if (jobFile == null) {
      return null;
    }
    List<RunJob> jobs = sharedFileJobs(options, jobFile);
    return jobs.isEmpty() ? null : jobs.get(0);
  }

  private synchronized CatalogData sharedCatalog(BenchmarkOptions options) {
    if (catalog == null) {
      catalog = new CsvCatalogLoader().load(options.runConfig().dataDir());
    }
    return catalog;
  }

  private synchronized MarketGuidance sharedGuidance(BenchmarkOptions options) {
    if (guidance == null) {
      guidance =
          new StorePlaybookLoader()
              .load(options.runConfig().dataDir().resolve("store_playbook.json"));
    }
    return guidance;
  }

  private synchronized ModelRegistry sharedRegistry(BenchmarkOptions options) {
    if (modelRegistry == null) {
      modelRegistry = new ModelRegistryLoader(objectMapper).load(options.modelsConfigPath());
    }
    return modelRegistry;
  }

  private synchronized TokenCounter sharedTokenCounter(BenchmarkOptions options) {
    if (tokenCounter == null) {
      tokenCounter = HuggingFaceTokenCounter.load(resolveTokenizerJson(options));
    }
    return tokenCounter;
  }

  private synchronized RunDirectory sharedRunDirectory(BenchmarkOptions options) {
    if (runDirectory == null) {
      Path logDir =
          options.runConfig().logDir() != null
              ? options.runConfig().logDir()
              : Path.of(System.getProperty("user.dir")).resolve("log");
      runDirectory =
          RunDirectory.create(
              logDir, TIMESTAMP.format(LocalDateTime.now()), options.runConfig().modelKey());
    }
    return runDirectory;
  }

  private synchronized List<RunJob> sharedFileJobs(BenchmarkOptions options, Path jobFile) {
    if (fileJobs == null) {
      fileJobs = new JobFileLoader(objectMapper, options.runConfig()).load(jobFile);
    }
    return fileJobs;
  }

  private static Path resolveTokenizerJson(BenchmarkOptions options) {
    Path tokenizerPath = options.runConfig().tokenizerPath();
    if (tokenizerPath == null) {
      throw new IllegalArgumentException("未配置 tokenizer 路径");
    }
    String fileName = tokenizerPath.getFileName().toString();
    return fileName.endsWith(".json") ? tokenizerPath : tokenizerPath.resolve("tokenizer.json");
  }

  private static ModelConfig withEffort(ModelConfig config, String effort) {
    if (effort == null || effort.isBlank() || effort.equals(config.effort())) {
      return config;
    }
    return new ModelConfig(
        config.key(),
        config.provider(),
        config.modelName(),
        config.apiStyle(),
        effort,
        config.baseUrl(),
        config.apiKeyExpression(),
        config.thinkingEnv(),
        config.extraBody());
  }
}
