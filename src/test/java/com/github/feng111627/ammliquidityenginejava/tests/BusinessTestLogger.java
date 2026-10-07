package com.github.feng111627.ammliquidityenginejava.tests;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试日志增强：按业务分组逐条打印用例名与通过情况（✓/✗）。
 * 通过 ServiceLoader（META-INF/services）被 JUnit Platform 自动加载。
 */
public class BusinessTestLogger implements TestExecutionListener {

    private final AtomicInteger passed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private TestPlan plan;
    private String lastGroup = "";

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        this.plan = testPlan;
        this.lastGroup = "";
        System.out.println();
        System.out.println("================ AMM 业务测试开始 ================");
    }

    @Override
    public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
        if (!identifier.isTest()) {
            return;
        }
        String group = resolveBusinessGroup(identifier);
        if (!group.equals(lastGroup)) {
            System.out.println();
            System.out.println("▶ " + group);
            lastGroup = group;
        }
        switch (result.getStatus()) {
            case SUCCESSFUL -> {
                passed.incrementAndGet();
                System.out.println("    ✓ " + identifier.getDisplayName());
            }
            case FAILED -> {
                failed.incrementAndGet();
                System.out.println("    ✗ " + identifier.getDisplayName() + " -> "
                        + result.getThrowable().map(Throwable::getMessage).orElse("(无异常信息)"));
            }
            case ABORTED -> System.out.println("    ⊙ " + identifier.getDisplayName() + "（中止）");
        }
    }

    /** 沿 parent 链找到以“业务”开头的顶层类显示名，再拼上 @Nested 分组名。 */
    private String resolveBusinessGroup(TestIdentifier identifier) {
        String nestedGroup = null;
        var current = identifier;
        while (current != null) {
            if (current.getDisplayName().startsWith("业务")) {
                return nestedGroup == null
                        ? current.getDisplayName()
                        : current.getDisplayName() + " / " + nestedGroup;
            }
            if (current.isContainer() && !current.getDisplayName().startsWith("JUnit")
                    && current.getParentId().isPresent()) {
                nestedGroup = current.getDisplayName();
            }
            var parentId = current.getParentId();
            current = parentId.map(plan::getTestIdentifier).orElse(null);
        }
        return identifier.getDisplayName();
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        System.out.println();
        System.out.println("================ 汇总：通过 " + passed.get()
                + " / 失败 " + failed.get() + " ================");
        System.out.println();
    }
}
