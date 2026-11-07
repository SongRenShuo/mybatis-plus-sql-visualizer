// T-SyncK2JMaster177 95e107b 自 master sql/RuntimeSlotValues.kt（SlotWarning data class，新增）移植
// （master 单 .kt 文件多顶层类；Java 侧 public 顶层类须独立文件，沿用 k2j 既有拆分布局）
package cn.song.mybatisplus.visualizer.sql;

import java.util.Objects;

/**
 * 一条占位相关的提示（面板展示 / 回填产物诊断共用）。
 *
 * @param slotId  出问题的占位 id；null = 整表层面的提示（如未知 id）
 * @param message 面向用户的中文说明（为什么没填进去、下一步该怎么做）
 */
public final class SlotWarning {

    private final String slotId;
    private final String message;

    public SlotWarning(String slotId, String message) {
        this.slotId = slotId;
        this.message = message;
    }

    public String getSlotId() {
        return slotId;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return slotId == null ? message : slotId + ": " + message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SlotWarning)) return false;
        SlotWarning other = (SlotWarning) o;
        return Objects.equals(slotId, other.slotId) && Objects.equals(message, other.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(slotId, message);
    }
}
