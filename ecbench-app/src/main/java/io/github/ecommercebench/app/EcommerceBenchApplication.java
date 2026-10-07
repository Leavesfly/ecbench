package io.github.ecommercebench.app;

import io.github.ecommercebench.app.cli.BenchmarkCommand;
import io.github.ecommercebench.app.run.RunComponentFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine;

/**
 * E-Commerce Bench 的可执行入口。
 *
 * <p>以 {@link WebApplicationType#NONE} 启动 Spring Boot（不监听任何端口），随后用 Picocli 解析并执行与 Python {@code
 * run.py} 兼容的命令行。 进程退出码由 Picocli 执行结果经 {@link SpringApplication#exit} 归并后交给 {@code System.exit}。
 */
@SpringBootApplication
public class EcommerceBenchApplication {

  public static void main(String[] args) {
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(EcommerceBenchApplication.class)
            .web(WebApplicationType.NONE)
            .run(args);
    int exitCode =
        new CommandLine(new BenchmarkCommand(context.getBean(RunComponentFactory.class)))
            .execute(args);
    System.exit(SpringApplication.exit(context, () -> exitCode));
  }
}
