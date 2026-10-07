package com.github.feng111627.ammliquidityenginejava.amm;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.util.logging.Logger;

/**
 * 在测试日志中打印每个用例覆盖的业务点和通过/失败结果，
 * 方便从 mvn test 输出直接看出覆盖范围。
 */
public class BusinessCaseLogger implements BeforeEachCallback, AfterEachCallback, TestWatcher {

    private static final Logger LOG = Logger.getLogger("BusinessCase");

    @Override
    public void beforeEach(ExtensionContext context) {
        LOG.info(() -> "[RUN ] " + context.getDisplayName());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        // 结果由 TestWatcher 回调打印
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        LOG.info(() -> "[PASS] " + context.getDisplayName());
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        LOG.info(() -> "[FAIL] " + context.getDisplayName() + " -> " + cause);
    }
}
