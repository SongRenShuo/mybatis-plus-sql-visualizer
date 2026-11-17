package cn.song.mybatisplus.visualizer.xml;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * choose 互斥组（T-MbgExamplePreview R4）：同一个 {@code <choose>} 的 when 分支是「只能成立一个」的
 * 语义，但默认启发式把它们全部置真，渲染按短路顺序永远只走第一个——MBG Example 里第一个恰好是
 * 唯一不带 {@code #{}} 的 {@code noValue} 分支。本表记录组内成员与默认分支，
 * {@link XmlPreviewModel#defaultState()} 每组只启用一个。
 *
 * @param branchIds       组内 when 的 test 表达式原文（顺序 = 短路顺序）
 * @param defaultBranchId 默认启用的分支（含 {@code #{}} 参数的分支里最先出现者；全无参数时为首个）
 */
public record XmlChooseGroup(List<String> branchIds, @Nullable String defaultBranchId) {

    public XmlChooseGroup {
        branchIds = List.copyOf(branchIds);
    }
}
