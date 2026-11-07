package cn.song.mybatisplus.visualizer.xml;

import org.jetbrains.annotations.Nullable;

/**
 * 一次判定的结果。
 *
 * @param language        判定语言
 * @param evidence        判定证据
 * @param rootIdentifier  HQL 根标识符（也用作 HqlConverter 的 entityAlias 入参）；提不到为 null
 */
public record PagesSourceDetection(
        PagesSourceLanguage language,
        PagesSourceEvidence evidence,
        @Nullable String rootIdentifier) {
}
