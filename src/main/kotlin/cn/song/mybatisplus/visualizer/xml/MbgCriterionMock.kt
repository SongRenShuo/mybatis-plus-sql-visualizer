package cn.song.mybatisplus.visualizer.xml

/**
 * MBG Example 的 Criterion 模拟值（T-MbgExamplePreview M2）。
 *
 * MBG 生成器的 `andXxxYyy(...)` 方法体固定是一句
 * `addCriterion("cust_id =", value, "custId")`——condition 是字符串常量、分支语义写在方法名后缀里。
 * 因此从调用点反推 Criterion 不需要猜列名，只需读那个常量。
 */
enum class MbgCriterionKind { NO_VALUE, SINGLE_VALUE, BETWEEN_VALUE, LIST_VALUE }

/**
 * 一个 Criterion 的模拟值。
 *
 * @param condition `addCriterion` 的第一个实参常量（如 `cust_id =` / `cust_id in`），已含操作符
 * @param values    调用点实参原文（`IsNull` 类为空；`Between` 两个；其余一个）
 * @param kind      由方法名后缀判定（见 [kindOf]）
 */
data class MbgCriterionMock(
    val condition: String,
    val values: List<String> = emptyList(),
    val kind: MbgCriterionKind = MbgCriterionKind.SINGLE_VALUE,
) {

    /** 转成 OGNL 可读的模拟对象（`${criterion.condition}` / `criterion.value` / 四个布尔分支）。 */
    fun toMockObject(): MockObject = MockObject(
        mapOf(
            "condition" to condition,
            "value" to valueForKind(),
            "secondValue" to values.getOrNull(1),
            "noValue" to (kind == MbgCriterionKind.NO_VALUE),
            "singleValue" to (kind == MbgCriterionKind.SINGLE_VALUE),
            "betweenValue" to (kind == MbgCriterionKind.BETWEEN_VALUE),
            "listValue" to (kind == MbgCriterionKind.LIST_VALUE),
        ),
    )

    private fun valueForKind(): Any? = when (kind) {
        MbgCriterionKind.NO_VALUE -> null
        MbgCriterionKind.LIST_VALUE -> values
        else -> values.firstOrNull() ?: "1"
    }

    companion object {

        /**
         * 方法名 → 分支类别。MBG 的方法名后缀就是语义，比数 `addCriterion` 参数个数可靠：
         * `...IsNull` / `...IsNotNull` → 无值；`...In` / `...NotIn` → 列表；`...Between` → 区间；
         * 其余（`EqualTo` / `Like` / `GreaterThan` …）→ 单值。
         */
        fun kindOf(methodName: String): MbgCriterionKind = when {
            methodName.endsWith("IsNull") || methodName.endsWith("IsNotNull") -> MbgCriterionKind.NO_VALUE
            methodName.endsWith("NotIn") || methodName.endsWith("In") -> MbgCriterionKind.LIST_VALUE
            methodName.endsWith("NotBetween") || methodName.endsWith("Between") -> MbgCriterionKind.BETWEEN_VALUE
            else -> MbgCriterionKind.SINGLE_VALUE
        }

        /** Criteria 元素（`Example_Where_Clause` 里 `criteria.valid` 与 `criteria.criteria` 的载体）。 */
        fun criteriaOf(criteria: List<MbgCriterionMock>): MockObject = MockObject(
            mapOf(
                "valid" to true,
                "criteria" to criteria.map { it.toMockObject() },
            ),
        )
    }
}
