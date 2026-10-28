package cn.song.mybatisplus.visualizer.execute.fixture;

import java.util.Collections;
import java.util.List;

/**
 * 模拟平台 {@code DatabaseRunners$3} / {@code $4$1} 的访问级别：类本身与构造器均为包私有，
 * 仅 {@code getDisplayName()} / {@code getSubRunners()} 为 public。
 *
 * <p>用于验证生产反射必须显式 {@code isAccessible = true}：否则从插件包调用
 * {@code Method.invoke} 会抛 {@code IllegalAccessException}，组项被当成普通项保留，
 * 而它的 runnable 是空操作，最终表现为准备门 60 秒超时。
 */
final class HiddenGroupRunner {
    private final String displayName;
    private final List<Object> subRunners;

    HiddenGroupRunner(String displayName, List<Object> subRunners) {
        this.displayName = displayName;
        this.subRunners = subRunners;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<Object> getSubRunners() {
        return subRunners;
    }
}

/** 自引用组项：展开不可能收敛，用于验证超过深度上限后剔除。 */
final class HiddenCycleRunner {
    private final String displayName;

    HiddenCycleRunner(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<Object> getSubRunners() {
        return Collections.<Object>singletonList(this);
    }
}

/** 带 runnable 字段的 Runner 桩：验证空操作识别（字段声明在平台 MyRunner 父类）。 */
final class HiddenRunnableRunner {
    private final Runnable runnable;

    HiddenRunnableRunner(Runnable runnable) {
        this.runnable = runnable;
    }

    public String getDisplayName() {
        return "platform-group";
    }

    public void run() {
        runnable.run();
    }
}

/** 包外唯一入口：返回类型统一为 Object，避免包私有类型泄漏到测试包。 */
public final class HiddenRunnerFixture {
    private HiddenRunnerFixture() {
    }

    public static Object group(String name, List<Object> subs) {
        return new HiddenGroupRunner(name, subs);
    }

    public static Object cycle(String name) {
        return new HiddenCycleRunner(name);
    }

    public static Object withRunnable(Runnable runnable) {
        return new HiddenRunnableRunner(runnable);
    }
}
