package cn.song.mybatisplus.visualizer.xml;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * MBG Example 的 Criterion 模拟值（T-MbgExamplePreview M2）。
 *
 * <p>MBG 生成器的 {@code andXxxYyy(...)} 方法体固定是一句
 * {@code addCriterion("cust_id =", value, "custId")}——condition 是字符串常量、分支语义写在
 * 方法名后缀里。因此从调用点反推 Criterion 不需要猜列名，只需读那个常量。
 */
public enum MbgCriterionKind {
    NO_VALUE, SINGLE_VALUE, BETWEEN_VALUE, LIST_VALUE
}
