package io.github.ecommercebench.llm.http;

/**
 * 允许抛出受检异常的返回值函数。
 */
@FunctionalInterface
public interface CheckedSupplier<T> {
    T get() throws Exception;
}
