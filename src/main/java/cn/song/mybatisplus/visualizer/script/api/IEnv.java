package cn.song.mybatisplus.visualizer.script.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * T-RollbackSnapshotPreExec P6：脚本变量环境（D-3 显式语义——设置页变量表与本对象双向）。
 *
 * 用途（照 CoolRequest IEnv 的精华）：后置脚本把执行结果提炼成变量
 * （如 {@code env.setVar("lastCount", "42")}），前置脚本再取用拼条件——串起
 * 「上次结果 → 下次条件」。变量由编排层持久化到项目设置（P7），本接口只管读写与广播。
 *
 * 值恒为字符串；null 值视为 remove。
 */
public interface IEnv {

    /** 取变量；不存在返回 null。 */
    String getVar(String name);

    /** 写变量（null 值等价 removeVar）；写后广播变更。 */
    void setVar(String name, String value);

    /** 删变量；删后广播变更。 */
    void removeVar(String name);

    /** 当前全部变量快照（不可变）。 */
    Map<String, String> vars();

    /** 变更监听器（P7 持久化与变量表刷新用）。 */
    interface Listener {
        void onChange(String name, String value);
    }

    void addListener(Listener listener);

    void removeListener(Listener listener);

    /** 默认实现：线程安全 + CopyOnWriteArrayList 监听器。 */
    class Impl implements IEnv {

        private final Map<String, String> vars = new java.util.concurrent.ConcurrentHashMap<>();
        private final List<Listener> listeners = new CopyOnWriteArrayList<>();

        @Override
        public String getVar(String name) {
            return vars.get(name);
        }

        @Override
        public void setVar(String name, String value) {
            if (name == null) return;
            if (value == null) {
                removeVar(name);
                return;
            }
            vars.put(name, value);
            fire(name, value);
        }

        @Override
        public void removeVar(String name) {
            if (name == null) return;
            vars.remove(name);
            fire(name, null);
        }

        @Override
        public Map<String, String> vars() {
            return Map.copyOf(vars);
        }

        @Override
        public void addListener(Listener listener) {
            if (listener != null) listeners.add(listener);
        }

        @Override
        public void removeListener(Listener listener) {
            listeners.remove(listener);
        }

        private void fire(String name, String value) {
            for (Listener l : listeners) {
                try {
                    l.onChange(name, value);
                } catch (RuntimeException e) {
                    // 监听器异常不反噬脚本线程（P7 的持久化监听器自己兜日志）
                }
            }
        }
    }
}
