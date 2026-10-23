package io.github.ecommercebench.app.cli;

import picocli.CommandLine.Option;

/**
 * 起 Web 服务所需的三个参数，被根命令与 {@code serve} 子命令共享。
 *
 * <p><b>刻意不含 {@code --log-dir}</b>：{@code CliRunOptions} 已声明同名选项，若两个 mixin 都带它，Picocli 会在命令
 * 注册时因重复声明直接抛异常。会话根目录统一由 {@code CliRunOptions.logDir()} 或 {@code ServeCommand} 自己的
 * {@code --log-dir} 提供——写侧产物根与读侧会话根本来就是同一个目录。
 */
public final class ServeOptions {

    @Option(names = "--port", description = "监听端口，0 表示随机分配", defaultValue = "8080")
    private int port;

    @Option(names = "--host", description = "绑定地址，默认仅本地回环", defaultValue = "127.0.0.1")
    private String host;

    @Option(
            names = "--catalog",
            description = "供应商名册 CSV（用于 chatbox 欺诈标注），默认取资源根 data/suppliers.csv")
    private String catalog;

    public int port() {
        return port;
    }

    public String host() {
        return host;
    }

    public String catalog() {
        return catalog;
    }
}
