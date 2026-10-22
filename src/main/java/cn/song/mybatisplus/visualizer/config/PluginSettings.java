package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.log.SqlListDisplayMode;
import cn.song.mybatisplus.visualizer.log.SqlListTypeColor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 插件配置状态（T-M5-01，F-16 配置项持久化）。
 *
 * application-level 轻服务（@Service(Service.Level.APP)，跨项目共享），经 XmlSerializer 持久化
 * 到 `mp-sql-visualizer.xml`。修改后由 SectionConfigurable.apply（设置子页基类）写回即生效。
 *
 * T-M16.3（语言切换保存失效）：
 * 官方规范（IntelliJ Platform SDK——Services）要求**应用级轻服务 + PersistentStateComponent 必须
 * 禁用 roaming**（`roamingType = RoamingType.DISABLED`）——默认 RoamingType.DEFAULT 使状态进入
 * 设置同步（Settings Sync）管理通道，与轻服务持久化不兼容，导致「确定后重开/重启设置回滚」。
 */
@State(
        name = "MpSqlVisualizerSettings",
        storages = @Storage(value = "mp-sql-visualizer.xml", roamingType = RoamingType.DISABLED)
)
@Service(Service.Level.APP)
public final class PluginSettings implements PersistentStateComponent<PluginSettings.State> {

    private State state = new State();

    @NotNull
    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(State state) {
        this.state = state;
        // T-EnhancedCaptureDefaultOn（2026-10-19）：旧配置兼容归一——确认记忆与拒绝冷却不再
        // 参与任何判定，加载时幂等清零，避免 XML/JSON 继续携带误导值。**只碰这两个字段**：
        // 用户显式关闭的 enhancedCaptureEnabled 必须原样保留（关闭增强 > 一切默认）。
        this.state.setEnhancedCaptureConfirmed(true);
        this.state.setEnhancedCaptureRejectedAtMs(0L);
        // F47（T-PerfQuick5）：注入器喂入税消解的快照刷新漏斗——设置页 apply（面板末尾恒
        // loadState(s.copy())）与 JSON 导入（fromJson）均汇入本点，XmlSynHiGate 每元素
        // 服务查找（85ns/元素，V38 基准）由此降为快照直读（14ns/元素）。
        cn.song.mybatisplus.visualizer.ui.inject.XmlSynHiGate
                .refreshXmlSynHiEnabledSnapshot(state.xmlSynHiEnabled);
    }

    /** 当前 SQL 列表显示模式（T-ListMode；旧配置残留非法值时回退默认关键字换行）。 */
    @NotNull
    public SqlListDisplayMode sqlListDisplayMode() {
        try {
            return SqlListDisplayMode.valueOf(state.getLogListSqlDisplayMode());
        } catch (Throwable e) {
            return SqlListDisplayMode.KEYWORD_WRAP;
        }
    }

    /**
     * F47（V38 基准计数缝）：非空时每次调用 {@link #getInstance()} 回调一次——注入器喂入税基准
     * 测试用它数「每高亮 pass 的 app 级服务查找次数」（喂入面=每 XmlText/XmlAttributeValue 一次）。
     * null（常态）零开销。（Kotlin internal var → public 静态字段，Kotlin 测试直读属性语法。）
     */
    public static volatile Runnable getInstanceProbe = null;

    /** 获取全局配置实例。 */
    @NotNull
    public static PluginSettings getInstance() {
        Runnable probe = getInstanceProbe;
        if (probe != null) probe.run();
        return ApplicationManager.getApplication().getService(PluginSettings.class);
    }

    /** 序列化为 JSON 字符串（O-5：设置导入导出）；委托 SettingsIO.exportToJson。 */
    public static String toJson() {
        return SettingsIO.exportToJson(getInstance().getState());
    }

    /**
     * 从 JSON 字符串导入并覆盖当前全局配置（O-5：设置导入导出）；委托 {@link SettingsIO#importFromJson}。
     *
     * 以当前状态为底合并（T-Fix-SettingsScope）：文件只承载 {@link SettingsIO#exportToJson} 的
     * 23 个可覆盖项，State 其余 58 个字段（参数历史 / 增强捕获授权记忆 / 反馈身份 /
     * 日志自动保存目录 / 全局项等）不在文件里，必须保留现值而非回落出厂默认——
     * 全局项（language 等）由此自然保留，无需再单独抄回。
     *
     * @throws SettingsIO.ImportException JSON 格式错误或版本不兼容
     */
    public static void fromJson(String json) throws SettingsIO.ImportException {
        PluginSettings instance = getInstance();
        instance.loadState(SettingsIO.importFromJson(json, instance.getState().copy()));
    }

    /**
     * 可持久化状态（XmlSerializer 序列化；默认值即出厂配置）。
     */
    public static final class State {

        /**
         * T-DialectAutoInfer：方言模式——true=智能推断（默认，跟随 agent 捕获连接 &gt; IDE 数据源，
         * 未命中回退 MySQL）；false=按 {@code dialect} 手动指定。
         * 旧配置无此字段 → XmlSerializer 缺省 true（语义变化：从不动方言设置的老用户自动进入
         * 推断模式，CHANGELOG 已明示）。
         */
        private boolean dialectAuto = true;

        /** 数据库方言（对应 DialectRegistry 的名称）；仅 {@code dialectAuto}=false 时生效（手动指定值）。 */
        private String dialect = DEFAULT_DIALECT;
        private boolean replaceParams = true;
        private boolean formatSql = true;

        /**
         * 空参数填充策略（T-PT-04 / O-79 三态化）：参数值为空时的回填形态——
         * {@link #EMPTY_PARAM_FILL_PLACEHOLDER}（默认，保留 {@code ?} 待填提示）/
         * {@link #EMPTY_PARAM_FILL_NULL}（渲染 {@code NULL}）/{@link #EMPTY_PARAM_FILL_EMPTY_STRING}（渲染 {@code ''}）。
         * 消费点 = SqlPreviewPanel.renderParamValue 的空值分支，且仅对<b>带 mapper 签名类型信息</b>的
         * 参数行生效（无类型信息路径恒 {@code ?}，宁缺勿错）。
         */
        private String emptyParamFill = DEFAULT_EMPTY_PARAM_FILL;
        /**
         * 竖向排列 VALUES 行尾配对列名标注（T-VPairAnno）：true=显示（默认，T-VStack 以来行为不变），
         * false=仅竖排不追加标注注释（用户自带注释不受影响）。
         * 仅作用于预览面板竖向排列显示模式（SqlFormatter.formatVertical 的唯一生产调用点 applyDisplayMode）。
         */
        private boolean verticalPairAnnotation = true;

        /**
         * 字段较多时自动折叠（T-AutoFold）：true = 各处 SQL 预览编辑器渲染后自动折叠超长字段列表
         * （SELECT/INSERT 列清单、VALUES 组、UPDATE SET），默认关——关闭时写入管线零折叠操作、
         * 行为逐字节不变；开启后的下一次程序性写入生效（与 formatSql 同构，不即时刷新已开窗体）。
         */
        private boolean autoFoldEnabled = false;

        /**
         * 自动折叠触发的顶层字段数阈值（T-AutoFold）：字段列表元素数 >= 该值且区域跨多行才折叠，
         * 默认 10。
         */
        private int autoFoldFieldThreshold = DEFAULT_AUTO_FOLD_FIELD_THRESHOLD;
        private boolean showGutterIcon = true;

        /**
         * 通用 XML 内嵌 SQL 检测开关（O-72 T-XmlInlineSql，默认开）：
         * 叶子标签文本首词命中 SQL 关键字即认领（不限根元素/标签名）；
         * 关闭后 gutter 与 Alt+Shift+Q 兜底均不生效（mapper/pages/hbm 三套白名单不受影响）。
         */
        private boolean xmlInlineSqlEnabled = true;

        /**
         * R5（T-PerfSweep910 同期小件）：快捷模板「抽样」的 LIMIT 行数（默认 10，现值硬编码于
         * SampleSelectedTableAction）。范围钳制 1..10000（防误输入全表扫）；仅影响抽样语句。
         */
        private int sampleLimit = 10;

        /**
         * T-XmlSynHi（D4，默认开）：mapper XML 语法高亮注入（关键字着色 + #{} / ${} 参数占位底色
         * + test/value 属性表达式着色）。与 Ultimate 平台注入或 CodeHelperPro 共存时自动让位
         * （决策 1-A 双让位）；关闭后本插件对 mapper XML 零注入（反向验证）。
         */
        private boolean xmlSynHiEnabled = true;

        // R2（gutter 按来源分组显隐）：各来源组独立开关（默认全开=现行为不变）。
        // 组口径与 15 个 provider 对应：mapperXml / annotation / jpaHql / jdbc / customDao / other。
        private boolean gutterGroupMapperXml = true;
        private boolean gutterGroupAnnotation = true;
        private boolean gutterGroupJpaHql = true;
        private boolean gutterGroupJdbc = true;
        private boolean gutterGroupOther = true;
        private boolean gutterGroupInlineFallback = true;

        /**
         * JOIN 图渲染方式（T-Dgm-04）：true=IDE 原生图框架（Ultimate 的 Diagrams 插件，类图观感，
         * 缩放/布局/导出全框架托管）；false=经典自绘渲染器。
         * Community / Diagrams 插件缺席时无论此值均自动降级自绘。
         */
        private boolean joinDiagramNative = true;

        /**
         * 空白符可视化开关（T-PreviewWhitespaceToggle O-44a，<b>默认关</b>）：true 时插件自建的
         * 全部编辑器（预览面板 SQL 区 / 日志转 SQL 输入与结果区 / 点击预览 / 回滚 SQL 弹窗 /
         * SQL 输入编辑器 / 参数导入编辑器 / SQL→Java 结果区与 Java 预览弹窗）显示空白符
         * （空格 ·、Tab →、行尾换行标记），便于排查动态拼接 SQL 里的多余空格/Tab。
         *
         * <p><b>默认关的语义是「不设置该属性」而非「设 false」</b>——插件自建编辑器本就不设置
         * whitespace 属性、继承 IDE 全局设置，故关闭态<b>绝不调用</b> {@code setWhitespacesShown}，
         * 用户「设置 → 编辑器 → 常规 → 外观 → 显示空白符」的全局偏好原样生效。</p>
         *
         * <p>单向开关（本设计的真实边界，计划 §决策定案 D1 的「场景②」不可达）：开启 = 对这些
         * 编辑器强制显示；关闭 = 继承全局。因此<b>无法</b>做到「全局开着时只让插件编辑器干净」——
         * 关闭态的语义就是继承，想干净只能关掉全局开关。相对 IDE 全局开关的差异化点收敛为
         * 「不改用户全局偏好即可只在插件编辑器里看空白符」（场景①）。</p>
         *
         * <p>纯显示层：不改 SQL 文本，也不改任何渲染结果（逐字节不变）。</p>
         */
        private boolean editorShowWhitespaces = false;

        private String language = DEFAULT_LANGUAGE;
        private boolean injectLogicDelete = true;
        private String logicDeleteValue = DEFAULT_LOGIC_DELETE_VALUE;
        private boolean injectTenantLine = false;
        private String tenantColumnName = DEFAULT_TENANT_COLUMN_NAME;
        private String tenantColumnDefault = DEFAULT_TENANT_COLUMN_DEFAULT;
        private boolean injectTenantOnInsert = true;
        private String tablePrefix = "";
        private String tableSuffix = "";
        private String tableNameInference = INFERENCE_SNAKE;
        private String ignoredFields = "";
        private String removeTablePrefix = "";
        private String removeTableSuffix = "";

        /**
         * 列名按数据源校正（O-58，<b>默认开</b>）：实体字段名与库真实列名不一致时（{@code orderID} →
         * {@code order_i_d} 而库里是 {@code order_id}），用数据源的真实列集合纠正预览 SQL 里的列名。
         *
         * <p>只在归一化（小写 + 去 {@code _}/{@code -}）后能<b>唯一</b>确定是哪一列时改写，命中 0 列或 ≥2 列保持原样
         * （语义无关的改名不猜）。数据源不可用（社区版 / 未配置 / 未连接）、表看不见、查不了时
         * 行为与关闭本项<b>逐字段一致</b>。
         *
         * <p>默认开的依据：只在能唯一确定真值时改变结果，且改变方向是纠错；代价是「预览产物会随
         * 数据源可用性变化」，故校正发生时预览侧会给出一次可发现的信号。
         */
        private boolean columnCalibrationEnabled = true;

        private boolean autoCheckUpdate = false;
        private String ignoredUpdateVersions = "";
        private String reverseWrapperFormat = REVERSE_AUTO;

        /**
         * 嵌入列（@Embedded/@Embeddable 展平列）的命名约定（T-EmbeddedColPrefix）：
         * {@link #EMBEDDED_NAMING_AUTO} 自动（按工程内 Hibernate 命名配置判定，判不出按无前缀）/
         * {@link #EMBEDDED_NAMING_NONE} 无前缀（JPA 默认）/ {@link #EMBEDDED_NAMING_COMPONENT_PATH} 组件路径前缀。
         * 解析见 {@code cn.song.mybatisplus.visualizer.psi.jpa.EmbeddedColumnNamingResolver}。
         */
        private String embeddedColumnNaming = EMBEDDED_NAMING_AUTO;
        private boolean reverseManualEntitySelection = true;
        /** 自动反查未命中时允许后台扫描依赖库实体。 */
        private boolean libraryEntitySweepEnabled = true;
        private String logSqlPrefix = DEFAULT_LOG_SQL_PREFIX;
        private String logParamPrefix = DEFAULT_LOG_PARAM_PREFIX;
        private boolean logMixedPrefix = true;
        private boolean logAutoCaptureOnStart = false;
        private boolean logAutoCaptureSuppressed = false;
        private boolean logStopOnToolWindowClose = false;
        /** 发现 SQL 时自动展开日志工具窗口（M37.3 默认开——竞品「每次手动开面板」是普遍抱怨；60s 防抖，show 不抢焦点）。 */
        private boolean logAutoOpenOnCapture = true;
        private boolean logTimestampEnabled = true;
        private String logTimestampFormat = DEFAULT_LOG_TIMESTAMP_FORMAT;
        /** 时间戳显示模式：log=日志时间（默认），capture=捕获时间。 */
        private String logTimestampSource = DEFAULT_LOG_TIMESTAMP_SOURCE;
        private boolean logFormatSql = true;
        /** 日志与历史共用的一次性预览交互迁移提示。 */
        private boolean sqlListPreviewHintSeen = false;
        /**
         * 字面量参数恢复（T-HistParam F4，默认开）：日志 SQL 无 `?` 占位符（裸 SQL 捕获 /
         * MyBatis `${}` / 上游日志已内联参数值）时，把单引号字符串字面量识别为可编辑参数行。
         * 作用于 toPreviewResult 的统一识别通道。
         */
        private boolean literalParamRecovery = true;
        private String logFilterKeywords = "";
        private long logDedupeWindowMs = DEFAULT_LOG_DEDUPE_WINDOW_MS;
        private int logMaxParseLength = DEFAULT_LOG_MAX_PARSE_LENGTH;
        private int logMaxCount = DEFAULT_LOG_MAX_COUNT;
        private boolean logAutoSaveEnabled = false;
        private String logAutoSavePath = "";
        private String logAutoSaveFileNamePattern = DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN;

        /**
         * 历史 SQL 文件保留上限（T-HistoryFileRetention）：保存目录下历史文件（按文件名基名归并
         * 滚动块）超过该数量时，在后台写批之后自动删除最旧文件，防止磁盘空间爆炸。
         * 0 或负数 = 不限制（全量保留）。默认 {@link #DEFAULT_LOG_AUTO_SAVE_MAX_FILES}。
         */
        private int logAutoSaveMaxFiles = DEFAULT_LOG_AUTO_SAVE_MAX_FILES;
        private String logTimePrefix = DEFAULT_LOG_TIME_PREFIX;
        private long slowQueryThresholdMs = DEFAULT_SLOW_QUERY_THRESHOLD_MS;
        /**
         * 是否在 SQL 日志列表中显示耗时列（默认开启）。
         * false 时隐藏耗时，节省列表宽度。
         */
        private boolean showDurationInLogList = true;

        /**
         * 数据源角标显示完整上下文（T-DataSourceBadgeMode O-133，<b>默认关</b>＝仅显示数据库类型）。
         * false 时日志/历史两页的目标库角标只取「方言显示名」段（如 {@code [MySQL]}）；true 时显示
         * 全量「方言 · 连接事实」（如 {@code [MySQL · jpa_demo@localhost:3306]}）。
         *
         * <p><b>默认克制的口径</b>：角标生产链（agent 捕获→落盘→历史读回→MCP 检索）恒写全量串，
         * 本开关只做<b>显示侧门控</b>（唯一消费点 formatDatabaseFragment）——切换即时生效（含已
         * 读回的历史条目），落盘与检索语义零变化，后开开关不丢任何信息。
         * 边界：无 {@code  ·  } 分隔符的串（方言解析失败仅存连接事实 / MCP 数据源自定义名）关闭态
         * 原样保留——辨不出方言时保留最后上下文优于显示空白。</p>
         */
        private boolean dataSourceBadgeDetail = false;

        // ---------- T-CrudColor：SQL 列表 CRUD 类型颜色标记 ----------
        /**
         * CRUD 颜色标记形态：`bar`=左侧色条（默认）/ `background`=整行淡背景（选中态走描边）/
         * `off`=关闭。未知值按 `bar` 兜底（见 SqlListTypeColor.styleOf）。
         * 默认值常量在 SqlListTypeColor，State 侧只引用，避免双份字面量。
         */
        private String crudColorStyle = CRUD_COLOR_STYLE_BAR;

        /** SELECT 标记色（`#RRGGBB`；空串/非法 → 默认色）。 */
        private String crudColorSelect = CRUD_COLOR_DEFAULT_SELECT;

        /** INSERT 标记色（`#RRGGBB`）。 */
        private String crudColorInsert = CRUD_COLOR_DEFAULT_INSERT;

        /** UPDATE 标记色（`#RRGGBB`）。 */
        private String crudColorUpdate = CRUD_COLOR_DEFAULT_UPDATE;

        /** DELETE 标记色（`#RRGGBB`）。 */
        private String crudColorDelete = CRUD_COLOR_DEFAULT_DELETE;

        /**
         * SQL 列表显示模式（T-ListMode）：SQL 日志与 SQL 历史列表条目中 SQL 行的展示形态
         * （枚举 SqlListDisplayMode 的 name，读取走 sqlListDisplayMode() 容错）。默认关键字换行。
         */
        private String logListSqlDisplayMode = DEFAULT_LOG_LIST_DISPLAY_MODE;

        private String paramHistoryJson = "";

        /**
         * 参数命名方案（R22 T-04）：{@code {"方案名":[{"key":{...params}}], ...}}——每个方案的条目复用
         * {@link #paramHistoryJson} 的旧数组格式；默认方案不进本字段（仍走 {@link #paramHistoryJson}）。
         * 空字符串表示无命名方案。消费方 = {@code cn.song.mybatisplus.visualizer.ui.ParamHistoryCache}。
         */
        private String paramSchemesJson = "";

        /** R22 T-04：激活方案名（空串 = 默认方案；面板下拉切换后随持久化落盘）。
         *  T-DevAcceptFix2 ①：预设已按方法/SQL 隔离，本字段降级为「未记录键的回落值」（迁移兼容）。 */
        private String paramActiveScheme = "";

        /**
         * T-DevAcceptFix2 ①（20261006 第二轮验收）：<b>按键激活指针</b> {@code {"缓存键":"方案名", ...}}
         * ——预设按方法/SQL 隔离（每个方法各自记住自己激活的预设；快照本就按缓存键分桶）。
         * 空字符串 = 无按键记录（回落 {@link #paramActiveScheme}）。
         */
        private String paramActiveSchemeByKey = "";

        /**
         * T-PresetMethodBinding：<b>方案 × 方法 → 最近一次保存的完整缓存键</b>，形态
         * {@code {"方案名":{"<scope>":"<缓存键>"}, ...}}（嵌套 JSON，避免方案名与 scope 拼串）。
         *
         * <p>用途：同一方法里改了 SQL（键的 SQL 指纹段变了）后，精确键未命中时按本表把该预设
         * 在该方法下最近一次保存的快照找回来——「预设永久绑定方法、改 SQL 也不丢」。
         * 消费方 = {@code cn.song.mybatisplus.visualizer.ui.ParamHistoryCache.recallRecent}。</p>
         */
        private String paramSchemeRecentKey = "";

        private boolean logHibernateEnabled = true;
        private String logHibernateSqlPrefix = DEFAULT_LOG_HIBERNATE_SQL_PREFIX;
        private String logHibernateParamPrefix = DEFAULT_LOG_HIBERNATE_PARAM_PREFIX;
        private boolean logJdbcEnabled = true;

        // ---------- ShardingSphere 双段日志捕获（R10 T-ShardingSphereCompat） ----------

        /**
         * 启用 ShardingSphere 日志解析（R10 D3=A 默认开，与 logHibernateEnabled 同款口径）：
         * {@code Logic SQL:} 主条目 + {@code Actual SQL:} 折叠附注（5.x sql-show 形态）。
         * 骨架按公开文档形态实现，真实语料未采样（V36）——语料驱动的行为细节见
         * ShardingSphereLogParser TODO 与计划 §实施记录登记。
         */
        private boolean logShardingSphereEnabled = true;

        // ---------- JDBC 模板 SQL 字符串预览 ----------

        /** 启用 JdbcTemplate / NamedParameterJdbcTemplate SQL 字符串预览（默认开）。 */
        private boolean jdbcSqlPreviewEnabled = true;

        // ---------- T-ExecTimeoutGuard：执行超时护栏 ----------

        /**
         * 执行超时（秒，每语句基础值）：MCP explain 直连链 setQueryTimeout 驱动级建议值；
         * 多语句脚本预算按语句数线性放大（封顶 ×8，见 ExecutionTimeoutGuard）。0=不限。默认 30。
         */
        private int executionTimeoutSeconds = DEFAULT_EXECUTION_TIMEOUT_SECONDS;

        private List<TableNameRule> dynamicTableNameRules = new ArrayList<>();
        private boolean dataPermissionHint = false;
        private String dataPermissionMockCondition = "";

        /**
         * 分页拦截器模拟（R12 T-PaginationSim）：mapper 方法/XML/注解语句路径的 Page/IPage 参数
         * 按 MP PaginationInnerInterceptor 语义模拟——COUNT 包裹预览（D1=A 第二条语句；
         * searchCount=false 跳过，D3=A）。默认开（对齐 injectLogicDelete 预览保真增强默认开口径；
         * MP 运行时 Page 默认 searchCount=true 恒执行 COUNT，反编译实证）。
         */
        private boolean paginationSimEnabled = true;

        /**
         * 分页 COUNT 查询预览子开关（T-CountSimToggle，D129）：分页拦截器模拟产出中的 COUNT 包裹
         * 预览（{@code SELECT COUNT(*) FROM (原SQL) TOTAL}）独立控制——关=分页模拟只追加 LIMIT，
         * 不再生成 COUNT 语句。<b>仅在 {@link #paginationSimEnabled} 开启时生效（父开关优先）</b>：
         * 父开关关闭时 LIMIT 与 COUNT 全无，本项不生效。默认 true 保持既有行为（老用户零感知）。
         */
        private boolean paginationCountEnabled = true;

        /**
         * R33：EXPLAIN 结构化渲染开关（面板执行计划按钮的双通道增量，核实报告 V41 定案的
         * 自建执行链路线）——开=MySQL 家族表格行/PG 家族 JSON 计划在面板内结构化渲染＋索引
         * 建议条；其余方言（TEXT/XML 不硬解析）与判定/执行/渲染任一失败路径一律 fail-closed
         * 回退原生控制台直送通道（双通道并存不替换）。
         *
         * <p>T-DevAcceptFix ②（20261006 验收，计划 D67 采 B 案）：<b>默认关</b>——EXPLAIN 默认走原生
         * 数据库控制台执行与呈现（用户实测表格形态交互不如原生控制台）；结构化通道整体保留
         * 一个版本周期（设置项显式开启即恢复），下版再定摘除。存量用户已显式开启的值不受影响。</p>
         */
        private boolean explainStructuredRender = false;

        /** R90：原始计划跨会话保留需独立同意；旧渲染开关不代表落盘授权，缺字段默认关闭。 */
        private boolean explainPlanRetentionEnabled = false;

        private int historyMaxSize = DEFAULT_HISTORY_MAX_SIZE;

        // ---------- F-44-7 SqlSourceInterceptor 自动注入 ----------
        // T-SettingsTruth P1：原 autoInjectSqlSourceInterceptor 字段已删除——该开关为纯动作
        // （勾选立即注入一次），不保存状态，终态恒未勾选；旧 XML 残留属性由 XmlSerializer 自动忽略。

        // ---------- T-ErrReport 错误一键上报官网需求池 ----------

        /**
         * 需求池报告昵称（选填，报告对话框填写后记忆，下次带出；空 = 匿名提交）。
         */
        private String reportSubmitterName = "";

        /**
         * 需求池报告邮箱（选填，仅用于官方回复推送通知，不公开）。
         */
        private String reportSubmitterEmail = "";

        // ---------- T-McpToolset2 MCP 工具门控 ----------

        /**
         * 插件 MCP 功能总开关（T-MTS2-00）：false = 全部工具在平台 MCP Server 不可见
         * （McpToolsetSupport.isEnabled 恒 false——平台 getEnabledToolsets 每次
         * getTools 重新求值，262 mcpserver.jar 字节码实证，切换即时生效无需重启）。
         */
        private boolean mcpToolsEnabled = true;

        /**
         * 工具级禁用清单（T-MTS2-00）：工具名（sqlv_render_sql 等）→ 不出现在工具面。
         * 清单外默认启用——新增工具向后兼容（用户不配置即全部可用）。
         */
        private List<String> mcpDisabledTools = new ArrayList<>();

        // ---------- M56 增强捕获（agent 字节码通道；对用户不出现 agent 字样） ----------

        /**
         * M56 T-M56-08：增强捕获开关（**默认开**——2026-09-16 用户定案，增强捕获为主通道）。
         * 开关打开即授权：agent 直接接管捕获源（参数实参直取/精确耗时/影响行数/报错同列/
         * 精确跳转）；attach 不可用/连接丢失自动回落现有控制台捕获，零劣化。
         * T-EnhancedCaptureDefaultOn（2026-10-19）：注入确认弹窗已整体移除——开关打开即生效，
         * 不再有「默认开但需同意」的中间态。
         */
        private boolean enhancedCaptureEnabled = true;

        /**
         * M56 T-M56-08：注入确认记忆。
         *
         * <p>T-EnhancedCaptureDefaultOn（2026-10-19）：运行期语义已废弃——授权只看
         * {@code enhancedCaptureEnabled}。本字段保留仅为 XML/JSON 兼容与旧版本回退（旧版读到 true
         * 不再弹框）；{@code loadState} 会把它归一为 true。
         */
        @Deprecated
        private boolean enhancedCaptureConfirmed = true;

        /**
         * T-PS4-01：注入确认「拒绝时刻」毫秒时间戳（持久化，随本 XML 跨 IDE 重启存活）；0 = 无拒绝记忆。
         *
         * <p>T-EnhancedCaptureDefaultOn（2026-10-19）：拒绝冷却随确认流整体移除，本字段不再被读取；
         * 保留仅为 XML/JSON 兼容，{@code loadState} 会把它归一为 0。
         */
        @Deprecated
        private long enhancedCaptureRejectedAtMs = 0L;

        /**
         * M56 T-M56-08：脱敏开关（**默认关**——2026-09-16 用户定案，默认采集真实参数值）。
         * true 时对疑似敏感形态打码；脱敏在 agent 采集层执行，数据不出应用 JVM 前已处理。
         */
        private boolean enhancedCaptureDesensitize = false;

        /**
         * R60（T-LaneB D96）：展示/导出链脱敏开关（**默认关**——不改变既有用户观感）。
         * true 时日志列表 / 单击预览浮层 / 导出文件三类<b>展示层文本</b>经 DisplayMasker 双条件打码
         * （规则对齐采集侧 Desensitizer）；数据层（条目/参数预设/历史文件）不动。与采集侧
         * {@code enhancedCaptureDesensitize} 解耦——采集开关管「数据怎么进来」，本开关管「文本怎么出去」。
         */
        private boolean displayMaskEnabled = false;

        /**
         * R60（T-LaneB D96/D97）：MCP 输出面脱敏开关（sqlv_render_logs 还原语句打码；
         * <b>默认关</b>——脱敏降低 AI 诊断价值，承审计册口径「默认不脱并在工具描述里声明」）。
         * 与 {@code displayMaskEnabled} 分离：MCP 消费方是 agent（可另选口径），UI 展示面是本机用户。
         */
        private boolean mcpMaskEnabled = false;

        /**
         * T-StackHoverUi：调用栈快照总帧数（用户可配，面板侧钳位 [12,64]，默认 24）。agent 侧
         * StackDigest 两端采样——栈顶取 B-8 帧（引擎/门面侧）、栈底固定保留 8 帧（业务/Controller
         * 侧，JdbcTemplate 深封装链下旧 16 帧顶格预算够不着 controller）。随 attach 参数与
         * CONFIG 帧（sendConfig 热下发）双通道到达 agent，设置改动无需重附。
         */
        private int enhancedCaptureStackMaxFrames = DEFAULT_ENHANCED_CAPTURE_STACK_MAX_FRAMES;

        // ---------- T-UxOnboard T-UX-01 首用锚点引导 ----------

        /**
         * 首用锚点引导通知开关（默认开）：项目内首次打开含可预览 SQL（Wrapper 链/XML mapper/
         * 注解 SQL 任一）的文件时，经预览组弹一次「Alt+Shift+Q 预览」指路通知；关闭后全程静默。
         */
        private boolean onboardingAnchorTipEnabled = true;

        /**
         * 首用锚点引导通知已展示（一次性持久化记忆）：展示过一次即置 true，
         * 重开项目/重启 IDE 不再出现——用户全生命期至多一条。
         */
        private boolean onboardingAnchorTipShown = false;

        /**
         * T-SourceJumpFunnel P1-3：源码捕获帧过滤前缀（用户登记的自研框架包名，一行一个，
         * 如 {@code com.corp.engine.}）。agent 侧栈帧判定并入（CONFIG 热下发），IDE 侧候选解析同步过滤。
         * 空 = 未登记。
         */
        private String sourceFrameSkipPrefixes = "";

        /**
         * T-ToolSiteBizFrame：JAR 业务帧反向名单（一行一个，如 {@code com.corp.shared.util.}）。
         * 语义与 {@link #sourceFrameSkipPrefixes} 相反——名单内包名的帧即使以依赖 JAR 形态运行，
         * agent 侧来源判定也按业务帧对待（不再上顶到调用方）。CONFIG 热下发。空 = 未登记。
         */
        private String sourceFrameBizJarPrefixes = "";

        public boolean getDialectAuto() { return dialectAuto; }
        public void setDialectAuto(boolean dialectAuto) { this.dialectAuto = dialectAuto; }

        public String getDialect() { return dialect; }
        public void setDialect(String dialect) { this.dialect = dialect; }

        public boolean getReplaceParams() { return replaceParams; }
        public void setReplaceParams(boolean replaceParams) { this.replaceParams = replaceParams; }

        public boolean getFormatSql() { return formatSql; }
        public void setFormatSql(boolean formatSql) { this.formatSql = formatSql; }

        public String getEmptyParamFill() { return emptyParamFill; }
        public void setEmptyParamFill(String emptyParamFill) { this.emptyParamFill = emptyParamFill; }

        public boolean getVerticalPairAnnotation() { return verticalPairAnnotation; }
        public void setVerticalPairAnnotation(boolean verticalPairAnnotation) { this.verticalPairAnnotation = verticalPairAnnotation; }

        public boolean getAutoFoldEnabled() { return autoFoldEnabled; }
        public void setAutoFoldEnabled(boolean autoFoldEnabled) { this.autoFoldEnabled = autoFoldEnabled; }

        public int getAutoFoldFieldThreshold() { return autoFoldFieldThreshold; }
        public void setAutoFoldFieldThreshold(int autoFoldFieldThreshold) { this.autoFoldFieldThreshold = autoFoldFieldThreshold; }

        public boolean getShowGutterIcon() { return showGutterIcon; }
        public void setShowGutterIcon(boolean showGutterIcon) { this.showGutterIcon = showGutterIcon; }

        /** 通用 XML 内嵌 SQL 检测开关（O-72 T-XmlInlineSql，默认开）。 */
        public boolean getXmlInlineSqlEnabled() { return xmlInlineSqlEnabled; }
        public void setXmlInlineSqlEnabled(boolean xmlInlineSqlEnabled) { this.xmlInlineSqlEnabled = xmlInlineSqlEnabled; }

        /** 快捷模板「抽样」的 LIMIT 行数（R5，默认 10；范围钳制 1..10000）。 */
        public int getSampleLimit() { return sampleLimit; }
        public void setSampleLimit(int sampleLimit) { this.sampleLimit = sampleLimit; }

        /** mapper XML 语法高亮注入开关（T-XmlSynHi，默认开）。 */
        public boolean getXmlSynHiEnabled() { return xmlSynHiEnabled; }
        public void setXmlSynHiEnabled(boolean xmlSynHiEnabled) { this.xmlSynHiEnabled = xmlSynHiEnabled; }

        public boolean getGutterGroupMapperXml() { return gutterGroupMapperXml; }
        public void setGutterGroupMapperXml(boolean gutterGroupMapperXml) { this.gutterGroupMapperXml = gutterGroupMapperXml; }

        public boolean getGutterGroupAnnotation() { return gutterGroupAnnotation; }
        public void setGutterGroupAnnotation(boolean gutterGroupAnnotation) { this.gutterGroupAnnotation = gutterGroupAnnotation; }

        public boolean getGutterGroupJpaHql() { return gutterGroupJpaHql; }
        public void setGutterGroupJpaHql(boolean gutterGroupJpaHql) { this.gutterGroupJpaHql = gutterGroupJpaHql; }

        public boolean getGutterGroupJdbc() { return gutterGroupJdbc; }
        public void setGutterGroupJdbc(boolean gutterGroupJdbc) { this.gutterGroupJdbc = gutterGroupJdbc; }

        public boolean getGutterGroupOther() { return gutterGroupOther; }
        public void setGutterGroupOther(boolean gutterGroupOther) { this.gutterGroupOther = gutterGroupOther; }

        public boolean getGutterGroupInlineFallback() { return gutterGroupInlineFallback; }
        public void setGutterGroupInlineFallback(boolean gutterGroupInlineFallback) { this.gutterGroupInlineFallback = gutterGroupInlineFallback; }

        public boolean getJoinDiagramNative() { return joinDiagramNative; }
        public void setJoinDiagramNative(boolean joinDiagramNative) { this.joinDiagramNative = joinDiagramNative; }

        /** 空白符可视化开关（T-PreviewWhitespaceToggle O-44a，默认关＝不设置该属性、继承 IDE 全局）。 */
        public boolean getEditorShowWhitespaces() { return editorShowWhitespaces; }
        public void setEditorShowWhitespaces(boolean editorShowWhitespaces) { this.editorShowWhitespaces = editorShowWhitespaces; }

        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }

        public boolean getInjectLogicDelete() { return injectLogicDelete; }
        public void setInjectLogicDelete(boolean injectLogicDelete) { this.injectLogicDelete = injectLogicDelete; }

        public String getLogicDeleteValue() { return logicDeleteValue; }
        public void setLogicDeleteValue(String logicDeleteValue) { this.logicDeleteValue = logicDeleteValue; }

        public boolean getInjectTenantLine() { return injectTenantLine; }
        public void setInjectTenantLine(boolean injectTenantLine) { this.injectTenantLine = injectTenantLine; }

        public String getTenantColumnName() { return tenantColumnName; }
        public void setTenantColumnName(String tenantColumnName) { this.tenantColumnName = tenantColumnName; }

        public String getTenantColumnDefault() { return tenantColumnDefault; }
        public void setTenantColumnDefault(String tenantColumnDefault) { this.tenantColumnDefault = tenantColumnDefault; }

        public boolean getInjectTenantOnInsert() { return injectTenantOnInsert; }
        public void setInjectTenantOnInsert(boolean injectTenantOnInsert) { this.injectTenantOnInsert = injectTenantOnInsert; }

        public String getTablePrefix() { return tablePrefix; }
        public void setTablePrefix(String tablePrefix) { this.tablePrefix = tablePrefix; }

        public String getTableSuffix() { return tableSuffix; }
        public void setTableSuffix(String tableSuffix) { this.tableSuffix = tableSuffix; }

        public String getTableNameInference() { return tableNameInference; }
        public void setTableNameInference(String tableNameInference) { this.tableNameInference = tableNameInference; }

        public String getIgnoredFields() { return ignoredFields; }
        public void setIgnoredFields(String ignoredFields) { this.ignoredFields = ignoredFields; }

        public String getRemoveTablePrefix() { return removeTablePrefix; }
        public void setRemoveTablePrefix(String removeTablePrefix) { this.removeTablePrefix = removeTablePrefix; }

        public String getRemoveTableSuffix() { return removeTableSuffix; }
        public void setRemoveTableSuffix(String removeTableSuffix) { this.removeTableSuffix = removeTableSuffix; }

        public boolean getColumnCalibrationEnabled() { return columnCalibrationEnabled; }
        public void setColumnCalibrationEnabled(boolean columnCalibrationEnabled) { this.columnCalibrationEnabled = columnCalibrationEnabled; }

        public boolean getAutoCheckUpdate() { return autoCheckUpdate; }
        public void setAutoCheckUpdate(boolean autoCheckUpdate) { this.autoCheckUpdate = autoCheckUpdate; }

        public String getIgnoredUpdateVersions() { return ignoredUpdateVersions; }
        public void setIgnoredUpdateVersions(String ignoredUpdateVersions) { this.ignoredUpdateVersions = ignoredUpdateVersions; }

        public String getReverseWrapperFormat() { return reverseWrapperFormat; }
        public void setReverseWrapperFormat(String reverseWrapperFormat) { this.reverseWrapperFormat = reverseWrapperFormat; }

        public String getEmbeddedColumnNaming() { return embeddedColumnNaming; }
        public void setEmbeddedColumnNaming(String embeddedColumnNaming) { this.embeddedColumnNaming = embeddedColumnNaming; }

        public boolean getReverseManualEntitySelection() { return reverseManualEntitySelection; }
        public void setReverseManualEntitySelection(boolean reverseManualEntitySelection) { this.reverseManualEntitySelection = reverseManualEntitySelection; }

        public boolean getLibraryEntitySweepEnabled() { return libraryEntitySweepEnabled; }
        public void setLibraryEntitySweepEnabled(boolean libraryEntitySweepEnabled) { this.libraryEntitySweepEnabled = libraryEntitySweepEnabled; }

        public boolean getSqlListPreviewHintSeen() { return sqlListPreviewHintSeen; }
        public void setSqlListPreviewHintSeen(boolean sqlListPreviewHintSeen) { this.sqlListPreviewHintSeen = sqlListPreviewHintSeen; }

        public String getLogSqlPrefix() { return logSqlPrefix; }
        public void setLogSqlPrefix(String logSqlPrefix) { this.logSqlPrefix = logSqlPrefix; }

        public String getLogParamPrefix() { return logParamPrefix; }
        public void setLogParamPrefix(String logParamPrefix) { this.logParamPrefix = logParamPrefix; }

        public boolean getLogMixedPrefix() { return logMixedPrefix; }
        public void setLogMixedPrefix(boolean logMixedPrefix) { this.logMixedPrefix = logMixedPrefix; }

        public boolean getLogAutoCaptureOnStart() { return logAutoCaptureOnStart; }
        public void setLogAutoCaptureOnStart(boolean logAutoCaptureOnStart) { this.logAutoCaptureOnStart = logAutoCaptureOnStart; }

        public boolean getLogAutoCaptureSuppressed() { return logAutoCaptureSuppressed; }
        public void setLogAutoCaptureSuppressed(boolean logAutoCaptureSuppressed) { this.logAutoCaptureSuppressed = logAutoCaptureSuppressed; }

        public boolean getLogStopOnToolWindowClose() { return logStopOnToolWindowClose; }
        public void setLogStopOnToolWindowClose(boolean logStopOnToolWindowClose) { this.logStopOnToolWindowClose = logStopOnToolWindowClose; }

        public boolean getLogAutoOpenOnCapture() { return logAutoOpenOnCapture; }
        public void setLogAutoOpenOnCapture(boolean logAutoOpenOnCapture) { this.logAutoOpenOnCapture = logAutoOpenOnCapture; }

        public boolean getLogTimestampEnabled() { return logTimestampEnabled; }
        public void setLogTimestampEnabled(boolean logTimestampEnabled) { this.logTimestampEnabled = logTimestampEnabled; }

        public String getLogTimestampFormat() { return logTimestampFormat; }
        public void setLogTimestampFormat(String logTimestampFormat) { this.logTimestampFormat = logTimestampFormat; }

        public String getLogTimestampSource() { return logTimestampSource; }
        public void setLogTimestampSource(String logTimestampSource) { this.logTimestampSource = logTimestampSource; }

        public boolean getLogFormatSql() { return logFormatSql; }
        public void setLogFormatSql(boolean logFormatSql) { this.logFormatSql = logFormatSql; }

        public boolean getLiteralParamRecovery() { return literalParamRecovery; }
        public void setLiteralParamRecovery(boolean literalParamRecovery) { this.literalParamRecovery = literalParamRecovery; }

        public String getLogFilterKeywords() { return logFilterKeywords; }
        public void setLogFilterKeywords(String logFilterKeywords) { this.logFilterKeywords = logFilterKeywords; }

        public long getLogDedupeWindowMs() { return logDedupeWindowMs; }
        public void setLogDedupeWindowMs(long logDedupeWindowMs) { this.logDedupeWindowMs = logDedupeWindowMs; }

        public int getLogMaxParseLength() { return logMaxParseLength; }
        public void setLogMaxParseLength(int logMaxParseLength) { this.logMaxParseLength = logMaxParseLength; }

        public int getLogMaxCount() { return logMaxCount; }
        public void setLogMaxCount(int logMaxCount) { this.logMaxCount = logMaxCount; }

        public boolean getLogAutoSaveEnabled() { return logAutoSaveEnabled; }
        public void setLogAutoSaveEnabled(boolean logAutoSaveEnabled) { this.logAutoSaveEnabled = logAutoSaveEnabled; }

        public String getLogAutoSavePath() { return logAutoSavePath; }
        public void setLogAutoSavePath(String logAutoSavePath) { this.logAutoSavePath = logAutoSavePath; }

        public String getLogAutoSaveFileNamePattern() { return logAutoSaveFileNamePattern; }
        public void setLogAutoSaveFileNamePattern(String logAutoSaveFileNamePattern) { this.logAutoSaveFileNamePattern = logAutoSaveFileNamePattern; }

        public int getLogAutoSaveMaxFiles() { return logAutoSaveMaxFiles; }
        public void setLogAutoSaveMaxFiles(int logAutoSaveMaxFiles) { this.logAutoSaveMaxFiles = logAutoSaveMaxFiles; }

        public String getLogTimePrefix() { return logTimePrefix; }
        public void setLogTimePrefix(String logTimePrefix) { this.logTimePrefix = logTimePrefix; }

        public long getSlowQueryThresholdMs() { return slowQueryThresholdMs; }
        public void setSlowQueryThresholdMs(long slowQueryThresholdMs) { this.slowQueryThresholdMs = slowQueryThresholdMs; }

        public boolean getShowDurationInLogList() { return showDurationInLogList; }
        public void setShowDurationInLogList(boolean showDurationInLogList) { this.showDurationInLogList = showDurationInLogList; }

        /** 数据源角标显示完整上下文（T-DataSourceBadgeMode O-133，默认关＝仅显示数据库类型）。 */
        public boolean getDataSourceBadgeDetail() { return dataSourceBadgeDetail; }
        public void setDataSourceBadgeDetail(boolean dataSourceBadgeDetail) { this.dataSourceBadgeDetail = dataSourceBadgeDetail; }

        public String getCrudColorStyle() { return crudColorStyle; }
        public void setCrudColorStyle(String crudColorStyle) { this.crudColorStyle = crudColorStyle; }

        public String getCrudColorSelect() { return crudColorSelect; }
        public void setCrudColorSelect(String crudColorSelect) { this.crudColorSelect = crudColorSelect; }

        public String getCrudColorInsert() { return crudColorInsert; }
        public void setCrudColorInsert(String crudColorInsert) { this.crudColorInsert = crudColorInsert; }

        public String getCrudColorUpdate() { return crudColorUpdate; }
        public void setCrudColorUpdate(String crudColorUpdate) { this.crudColorUpdate = crudColorUpdate; }

        public String getCrudColorDelete() { return crudColorDelete; }
        public void setCrudColorDelete(String crudColorDelete) { this.crudColorDelete = crudColorDelete; }

        public String getLogListSqlDisplayMode() { return logListSqlDisplayMode; }
        public void setLogListSqlDisplayMode(String logListSqlDisplayMode) { this.logListSqlDisplayMode = logListSqlDisplayMode; }

        public String getParamHistoryJson() { return paramHistoryJson; }
        public void setParamHistoryJson(String paramHistoryJson) { this.paramHistoryJson = paramHistoryJson; }

        public String getParamSchemesJson() { return paramSchemesJson; }
        public void setParamSchemesJson(String paramSchemesJson) { this.paramSchemesJson = paramSchemesJson; }

        public String getParamActiveScheme() { return paramActiveScheme; }
        public void setParamActiveScheme(String paramActiveScheme) { this.paramActiveScheme = paramActiveScheme; }

        public String getParamActiveSchemeByKey() { return paramActiveSchemeByKey; }
        public void setParamActiveSchemeByKey(String paramActiveSchemeByKey) { this.paramActiveSchemeByKey = paramActiveSchemeByKey; }

        public String getParamSchemeRecentKey() { return paramSchemeRecentKey; }
        public void setParamSchemeRecentKey(String paramSchemeRecentKey) { this.paramSchemeRecentKey = paramSchemeRecentKey; }

        public boolean getLogHibernateEnabled() { return logHibernateEnabled; }
        public void setLogHibernateEnabled(boolean logHibernateEnabled) { this.logHibernateEnabled = logHibernateEnabled; }

        public String getLogHibernateSqlPrefix() { return logHibernateSqlPrefix; }
        public void setLogHibernateSqlPrefix(String logHibernateSqlPrefix) { this.logHibernateSqlPrefix = logHibernateSqlPrefix; }

        public String getLogHibernateParamPrefix() { return logHibernateParamPrefix; }
        public void setLogHibernateParamPrefix(String logHibernateParamPrefix) { this.logHibernateParamPrefix = logHibernateParamPrefix; }

        public boolean getLogJdbcEnabled() { return logJdbcEnabled; }
        public void setLogJdbcEnabled(boolean logJdbcEnabled) { this.logJdbcEnabled = logJdbcEnabled; }

        /** ShardingSphere 双段日志捕获开关（R10 T-ShardingSphereCompat，默认开）。 */
        public boolean getLogShardingSphereEnabled() { return logShardingSphereEnabled; }
        public void setLogShardingSphereEnabled(boolean logShardingSphereEnabled) { this.logShardingSphereEnabled = logShardingSphereEnabled; }

        public boolean getJdbcSqlPreviewEnabled() { return jdbcSqlPreviewEnabled; }
        public void setJdbcSqlPreviewEnabled(boolean jdbcSqlPreviewEnabled) { this.jdbcSqlPreviewEnabled = jdbcSqlPreviewEnabled; }

        public int getExecutionTimeoutSeconds() { return executionTimeoutSeconds; }
        public void setExecutionTimeoutSeconds(int executionTimeoutSeconds) { this.executionTimeoutSeconds = executionTimeoutSeconds; }

        public List<TableNameRule> getDynamicTableNameRules() { return dynamicTableNameRules; }
        public void setDynamicTableNameRules(List<TableNameRule> dynamicTableNameRules) { this.dynamicTableNameRules = dynamicTableNameRules; }

        public boolean getDataPermissionHint() { return dataPermissionHint; }
        public void setDataPermissionHint(boolean dataPermissionHint) { this.dataPermissionHint = dataPermissionHint; }

        public String getDataPermissionMockCondition() { return dataPermissionMockCondition; }
        public void setDataPermissionMockCondition(String dataPermissionMockCondition) { this.dataPermissionMockCondition = dataPermissionMockCondition; }

        public boolean getPaginationSimEnabled() { return paginationSimEnabled; }
        public void setPaginationSimEnabled(boolean paginationSimEnabled) { this.paginationSimEnabled = paginationSimEnabled; }

        public boolean getPaginationCountEnabled() { return paginationCountEnabled; }
        public void setPaginationCountEnabled(boolean paginationCountEnabled) { this.paginationCountEnabled = paginationCountEnabled; }

        public boolean getExplainStructuredRender() { return explainStructuredRender; }
        public void setExplainStructuredRender(boolean explainStructuredRender) { this.explainStructuredRender = explainStructuredRender; }

        public boolean getExplainPlanRetentionEnabled() { return explainPlanRetentionEnabled; }
        public void setExplainPlanRetentionEnabled(boolean explainPlanRetentionEnabled) { this.explainPlanRetentionEnabled = explainPlanRetentionEnabled; }

        public int getHistoryMaxSize() { return historyMaxSize; }
        public void setHistoryMaxSize(int historyMaxSize) { this.historyMaxSize = historyMaxSize; }

        public String getReportSubmitterName() { return reportSubmitterName; }
        public void setReportSubmitterName(String reportSubmitterName) { this.reportSubmitterName = reportSubmitterName; }

        public String getReportSubmitterEmail() { return reportSubmitterEmail; }
        public void setReportSubmitterEmail(String reportSubmitterEmail) { this.reportSubmitterEmail = reportSubmitterEmail; }

        public boolean getMcpToolsEnabled() { return mcpToolsEnabled; }
        public void setMcpToolsEnabled(boolean mcpToolsEnabled) { this.mcpToolsEnabled = mcpToolsEnabled; }

        public List<String> getMcpDisabledTools() { return mcpDisabledTools; }
        public void setMcpDisabledTools(List<String> mcpDisabledTools) { this.mcpDisabledTools = mcpDisabledTools; }

        public boolean getEnhancedCaptureEnabled() { return enhancedCaptureEnabled; }
        public void setEnhancedCaptureEnabled(boolean enhancedCaptureEnabled) { this.enhancedCaptureEnabled = enhancedCaptureEnabled; }

        public boolean getEnhancedCaptureConfirmed() { return enhancedCaptureConfirmed; }
        public void setEnhancedCaptureConfirmed(boolean enhancedCaptureConfirmed) { this.enhancedCaptureConfirmed = enhancedCaptureConfirmed; }

        public long getEnhancedCaptureRejectedAtMs() { return enhancedCaptureRejectedAtMs; }
        public void setEnhancedCaptureRejectedAtMs(long enhancedCaptureRejectedAtMs) { this.enhancedCaptureRejectedAtMs = enhancedCaptureRejectedAtMs; }

        public boolean getEnhancedCaptureDesensitize() { return enhancedCaptureDesensitize; }
        public void setEnhancedCaptureDesensitize(boolean enhancedCaptureDesensitize) { this.enhancedCaptureDesensitize = enhancedCaptureDesensitize; }

        public boolean getDisplayMaskEnabled() { return displayMaskEnabled; }
        public void setDisplayMaskEnabled(boolean displayMaskEnabled) { this.displayMaskEnabled = displayMaskEnabled; }

        public boolean getMcpMaskEnabled() { return mcpMaskEnabled; }
        public void setMcpMaskEnabled(boolean mcpMaskEnabled) { this.mcpMaskEnabled = mcpMaskEnabled; }

        /** 调用栈快照总帧数（T-StackHoverUi，默认 24；面板侧钳位 [12,64]）。 */
        public int getEnhancedCaptureStackMaxFrames() { return enhancedCaptureStackMaxFrames; }
        public void setEnhancedCaptureStackMaxFrames(int enhancedCaptureStackMaxFrames) { this.enhancedCaptureStackMaxFrames = enhancedCaptureStackMaxFrames; }

        public boolean getOnboardingAnchorTipEnabled() { return onboardingAnchorTipEnabled; }
        public void setOnboardingAnchorTipEnabled(boolean onboardingAnchorTipEnabled) { this.onboardingAnchorTipEnabled = onboardingAnchorTipEnabled; }

        public boolean getOnboardingAnchorTipShown() { return onboardingAnchorTipShown; }
        public void setOnboardingAnchorTipShown(boolean onboardingAnchorTipShown) { this.onboardingAnchorTipShown = onboardingAnchorTipShown; }

        public String getSourceFrameSkipPrefixes() { return sourceFrameSkipPrefixes; }
        public void setSourceFrameSkipPrefixes(String sourceFrameSkipPrefixes) { this.sourceFrameSkipPrefixes = sourceFrameSkipPrefixes; }

        public String getSourceFrameBizJarPrefixes() { return sourceFrameBizJarPrefixes; }
        public void setSourceFrameBizJarPrefixes(String sourceFrameBizJarPrefixes) { this.sourceFrameBizJarPrefixes = sourceFrameBizJarPrefixes; }

        /** 浅拷贝（仅复制字段引用/值；供持久化 loadState 触发引用变化）。 */
        @NotNull
        public State copy() {
            State c = new State();
            c.dialectAuto = dialectAuto;
            c.dialect = dialect;
            c.replaceParams = replaceParams;
            c.formatSql = formatSql;
            c.emptyParamFill = emptyParamFill;
            c.verticalPairAnnotation = verticalPairAnnotation;
            c.autoFoldEnabled = autoFoldEnabled;
            c.autoFoldFieldThreshold = autoFoldFieldThreshold;
            c.showGutterIcon = showGutterIcon;
            c.xmlInlineSqlEnabled = xmlInlineSqlEnabled;
            c.sampleLimit = sampleLimit;
            c.xmlSynHiEnabled = xmlSynHiEnabled;
            c.gutterGroupMapperXml = gutterGroupMapperXml;
            c.gutterGroupAnnotation = gutterGroupAnnotation;
            c.gutterGroupJpaHql = gutterGroupJpaHql;
            c.gutterGroupJdbc = gutterGroupJdbc;
            c.gutterGroupOther = gutterGroupOther;
            c.gutterGroupInlineFallback = gutterGroupInlineFallback;
            c.editorShowWhitespaces = editorShowWhitespaces;
            c.language = language;
            c.injectLogicDelete = injectLogicDelete;
            c.logicDeleteValue = logicDeleteValue;
            c.injectTenantLine = injectTenantLine;
            c.tenantColumnName = tenantColumnName;
            c.tenantColumnDefault = tenantColumnDefault;
            c.injectTenantOnInsert = injectTenantOnInsert;
            c.tablePrefix = tablePrefix;
            c.tableSuffix = tableSuffix;
            c.tableNameInference = tableNameInference;
            c.ignoredFields = ignoredFields;
            c.removeTablePrefix = removeTablePrefix;
            c.removeTableSuffix = removeTableSuffix;
            c.columnCalibrationEnabled = columnCalibrationEnabled;
            c.autoCheckUpdate = autoCheckUpdate;
            c.ignoredUpdateVersions = ignoredUpdateVersions;
            c.reverseWrapperFormat = reverseWrapperFormat;
            c.embeddedColumnNaming = embeddedColumnNaming;
            c.reverseManualEntitySelection = reverseManualEntitySelection;
            c.libraryEntitySweepEnabled = libraryEntitySweepEnabled;
            c.sqlListPreviewHintSeen = sqlListPreviewHintSeen;
            c.logSqlPrefix = logSqlPrefix;
            c.logParamPrefix = logParamPrefix;
            c.logMixedPrefix = logMixedPrefix;
            c.logAutoCaptureOnStart = logAutoCaptureOnStart;
            c.logAutoCaptureSuppressed = logAutoCaptureSuppressed;
            c.logStopOnToolWindowClose = logStopOnToolWindowClose;
            c.logAutoOpenOnCapture = logAutoOpenOnCapture;
            c.logTimestampEnabled = logTimestampEnabled;
            c.logTimestampFormat = logTimestampFormat;
            c.logTimestampSource = logTimestampSource;
            c.logFormatSql = logFormatSql;
            c.literalParamRecovery = literalParamRecovery;
            c.logFilterKeywords = logFilterKeywords;
            c.logDedupeWindowMs = logDedupeWindowMs;
            c.logMaxParseLength = logMaxParseLength;
            c.logMaxCount = logMaxCount;
            c.logAutoSaveEnabled = logAutoSaveEnabled;
            c.logAutoSavePath = logAutoSavePath;
            c.logAutoSaveFileNamePattern = logAutoSaveFileNamePattern;
            c.logAutoSaveMaxFiles = logAutoSaveMaxFiles;
            c.logTimePrefix = logTimePrefix;
            c.slowQueryThresholdMs = slowQueryThresholdMs;
            c.showDurationInLogList = showDurationInLogList;
            c.dataSourceBadgeDetail = dataSourceBadgeDetail;
            c.crudColorStyle = crudColorStyle;
            c.crudColorSelect = crudColorSelect;
            c.crudColorInsert = crudColorInsert;
            c.crudColorUpdate = crudColorUpdate;
            c.crudColorDelete = crudColorDelete;
            c.logListSqlDisplayMode = logListSqlDisplayMode;
            c.paramHistoryJson = paramHistoryJson;
            c.paramSchemesJson = paramSchemesJson;
            c.paramActiveScheme = paramActiveScheme;
            c.paramActiveSchemeByKey = paramActiveSchemeByKey;
            c.paramSchemeRecentKey = paramSchemeRecentKey;
            c.logHibernateEnabled = logHibernateEnabled;
            c.logHibernateSqlPrefix = logHibernateSqlPrefix;
            c.logHibernateParamPrefix = logHibernateParamPrefix;
            c.logJdbcEnabled = logJdbcEnabled;
            c.logShardingSphereEnabled = logShardingSphereEnabled;
            c.jdbcSqlPreviewEnabled = jdbcSqlPreviewEnabled;
            c.executionTimeoutSeconds = executionTimeoutSeconds;
            c.dynamicTableNameRules = dynamicTableNameRules;
            c.dataPermissionHint = dataPermissionHint;
            c.dataPermissionMockCondition = dataPermissionMockCondition;
            c.paginationSimEnabled = paginationSimEnabled;
            c.paginationCountEnabled = paginationCountEnabled;
            c.explainStructuredRender = explainStructuredRender;
            c.explainPlanRetentionEnabled = explainPlanRetentionEnabled;
            c.historyMaxSize = historyMaxSize;
            c.reportSubmitterName = reportSubmitterName;
            c.reportSubmitterEmail = reportSubmitterEmail;
            c.mcpToolsEnabled = mcpToolsEnabled;
            c.mcpDisabledTools = mcpDisabledTools;
            c.enhancedCaptureEnabled = enhancedCaptureEnabled;
            c.enhancedCaptureConfirmed = enhancedCaptureConfirmed;
            c.enhancedCaptureRejectedAtMs = enhancedCaptureRejectedAtMs;
            c.enhancedCaptureDesensitize = enhancedCaptureDesensitize;
            c.displayMaskEnabled = displayMaskEnabled;
            c.mcpMaskEnabled = mcpMaskEnabled;
            c.enhancedCaptureStackMaxFrames = enhancedCaptureStackMaxFrames;
            c.onboardingAnchorTipEnabled = onboardingAnchorTipEnabled;
            c.onboardingAnchorTipShown = onboardingAnchorTipShown;
            c.sourceFrameSkipPrefixes = sourceFrameSkipPrefixes;
            c.sourceFrameBizJarPrefixes = sourceFrameBizJarPrefixes;
            return c;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof State)) return false;
            State that = (State) o;
            return dialectAuto == that.dialectAuto
                    && replaceParams == that.replaceParams
                    && formatSql == that.formatSql
                    && verticalPairAnnotation == that.verticalPairAnnotation
                    && autoFoldEnabled == that.autoFoldEnabled
                    && showGutterIcon == that.showGutterIcon
                    && xmlInlineSqlEnabled == that.xmlInlineSqlEnabled
                    && sampleLimit == that.sampleLimit
                    && xmlSynHiEnabled == that.xmlSynHiEnabled
                    && gutterGroupMapperXml == that.gutterGroupMapperXml
                    && gutterGroupAnnotation == that.gutterGroupAnnotation
                    && gutterGroupJpaHql == that.gutterGroupJpaHql
                    && gutterGroupJdbc == that.gutterGroupJdbc
                    && gutterGroupOther == that.gutterGroupOther
                    && gutterGroupInlineFallback == that.gutterGroupInlineFallback
                    && editorShowWhitespaces == that.editorShowWhitespaces
                    && injectLogicDelete == that.injectLogicDelete
                    && injectTenantLine == that.injectTenantLine
                    && injectTenantOnInsert == that.injectTenantOnInsert
                    && autoCheckUpdate == that.autoCheckUpdate
                    && logMixedPrefix == that.logMixedPrefix
                    && logAutoCaptureOnStart == that.logAutoCaptureOnStart
                    && logAutoCaptureSuppressed == that.logAutoCaptureSuppressed
                    && logStopOnToolWindowClose == that.logStopOnToolWindowClose
                    && logAutoOpenOnCapture == that.logAutoOpenOnCapture
                    && logTimestampEnabled == that.logTimestampEnabled
                    && logFormatSql == that.logFormatSql
                    && literalParamRecovery == that.literalParamRecovery
                    && logAutoSaveEnabled == that.logAutoSaveEnabled
                    && logHibernateEnabled == that.logHibernateEnabled
                    && logJdbcEnabled == that.logJdbcEnabled
                    && logShardingSphereEnabled == that.logShardingSphereEnabled
                    && jdbcSqlPreviewEnabled == that.jdbcSqlPreviewEnabled
                    && executionTimeoutSeconds == that.executionTimeoutSeconds
                    && dataPermissionHint == that.dataPermissionHint
                    && logDedupeWindowMs == that.logDedupeWindowMs
                    && logMaxParseLength == that.logMaxParseLength
                    && logMaxCount == that.logMaxCount
                    && slowQueryThresholdMs == that.slowQueryThresholdMs
                    && logAutoSaveMaxFiles == that.logAutoSaveMaxFiles
                    && showDurationInLogList == that.showDurationInLogList
                    && dataSourceBadgeDetail == that.dataSourceBadgeDetail
                    && historyMaxSize == that.historyMaxSize
                    && autoFoldFieldThreshold == that.autoFoldFieldThreshold
                    && paginationSimEnabled == that.paginationSimEnabled
                    && paginationCountEnabled == that.paginationCountEnabled
                    && explainStructuredRender == that.explainStructuredRender
                    && explainPlanRetentionEnabled == that.explainPlanRetentionEnabled
                    && mcpToolsEnabled == that.mcpToolsEnabled
                    && onboardingAnchorTipEnabled == that.onboardingAnchorTipEnabled
                    && onboardingAnchorTipShown == that.onboardingAnchorTipShown
                    && enhancedCaptureEnabled == that.enhancedCaptureEnabled
                    && enhancedCaptureConfirmed == that.enhancedCaptureConfirmed
                    && enhancedCaptureRejectedAtMs == that.enhancedCaptureRejectedAtMs
                    && enhancedCaptureDesensitize == that.enhancedCaptureDesensitize
                    && displayMaskEnabled == that.displayMaskEnabled
                    && mcpMaskEnabled == that.mcpMaskEnabled
                    && enhancedCaptureStackMaxFrames == that.enhancedCaptureStackMaxFrames
                    && Objects.equals(dialect, that.dialect)
                    && Objects.equals(emptyParamFill, that.emptyParamFill)
                    && Objects.equals(language, that.language)
                    && Objects.equals(logicDeleteValue, that.logicDeleteValue)
                    && Objects.equals(tenantColumnName, that.tenantColumnName)
                    && Objects.equals(tenantColumnDefault, that.tenantColumnDefault)
                    && Objects.equals(tablePrefix, that.tablePrefix)
                    && Objects.equals(tableSuffix, that.tableSuffix)
                    && Objects.equals(tableNameInference, that.tableNameInference)
                    && Objects.equals(ignoredFields, that.ignoredFields)
                    && Objects.equals(removeTablePrefix, that.removeTablePrefix)
                    && Objects.equals(removeTableSuffix, that.removeTableSuffix)
                    && columnCalibrationEnabled == that.columnCalibrationEnabled
                    && Objects.equals(ignoredUpdateVersions, that.ignoredUpdateVersions)
                    && Objects.equals(reverseWrapperFormat, that.reverseWrapperFormat)
                    && Objects.equals(embeddedColumnNaming, that.embeddedColumnNaming)
                    && Objects.equals(reverseManualEntitySelection, that.reverseManualEntitySelection)
                    && libraryEntitySweepEnabled == that.libraryEntitySweepEnabled
                    && sqlListPreviewHintSeen == that.sqlListPreviewHintSeen
                    && Objects.equals(logSqlPrefix, that.logSqlPrefix)
                    && Objects.equals(logParamPrefix, that.logParamPrefix)
                    && Objects.equals(logTimestampFormat, that.logTimestampFormat)
                    && Objects.equals(logTimestampSource, that.logTimestampSource)
                    && Objects.equals(logFilterKeywords, that.logFilterKeywords)
                    && Objects.equals(logAutoSavePath, that.logAutoSavePath)
                    && Objects.equals(logAutoSaveFileNamePattern, that.logAutoSaveFileNamePattern)
                    && Objects.equals(logTimePrefix, that.logTimePrefix)
                    && Objects.equals(paramHistoryJson, that.paramHistoryJson)
                    && Objects.equals(paramSchemesJson, that.paramSchemesJson)
                    && Objects.equals(paramActiveScheme, that.paramActiveScheme)
                    && Objects.equals(paramActiveSchemeByKey, that.paramActiveSchemeByKey)
                    && Objects.equals(paramSchemeRecentKey, that.paramSchemeRecentKey)
                    && Objects.equals(crudColorStyle, that.crudColorStyle)
                    && Objects.equals(crudColorSelect, that.crudColorSelect)
                    && Objects.equals(crudColorInsert, that.crudColorInsert)
                    && Objects.equals(crudColorUpdate, that.crudColorUpdate)
                    && Objects.equals(crudColorDelete, that.crudColorDelete)
                    && Objects.equals(logListSqlDisplayMode, that.logListSqlDisplayMode)
                    && Objects.equals(reportSubmitterName, that.reportSubmitterName)
                    && Objects.equals(reportSubmitterEmail, that.reportSubmitterEmail)
                    && Objects.equals(mcpDisabledTools, that.mcpDisabledTools)
                    && Objects.equals(sourceFrameSkipPrefixes, that.sourceFrameSkipPrefixes)
                    && Objects.equals(sourceFrameBizJarPrefixes, that.sourceFrameBizJarPrefixes)
                    && Objects.equals(logHibernateSqlPrefix, that.logHibernateSqlPrefix)
                    && Objects.equals(logHibernateParamPrefix, that.logHibernateParamPrefix)
                    && Objects.equals(dynamicTableNameRules, that.dynamicTableNameRules)
                    && Objects.equals(dataPermissionMockCondition, that.dataPermissionMockCondition);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    dialectAuto, dialect, replaceParams, formatSql, emptyParamFill, verticalPairAnnotation, autoFoldEnabled, autoFoldFieldThreshold,
                    showGutterIcon, xmlInlineSqlEnabled, editorShowWhitespaces, language, injectLogicDelete,
                    sampleLimit, xmlSynHiEnabled,
                    gutterGroupMapperXml, gutterGroupAnnotation, gutterGroupJpaHql, gutterGroupJdbc,
                    gutterGroupOther, gutterGroupInlineFallback,
                    logicDeleteValue, injectTenantLine, tenantColumnName, tenantColumnDefault, injectTenantOnInsert,
                    tablePrefix, tableSuffix, tableNameInference, ignoredFields, removeTablePrefix, removeTableSuffix,
                    columnCalibrationEnabled,
                    autoCheckUpdate, ignoredUpdateVersions, reverseWrapperFormat, embeddedColumnNaming, reverseManualEntitySelection,
                    libraryEntitySweepEnabled, sqlListPreviewHintSeen,
                    logSqlPrefix, logParamPrefix, logMixedPrefix, logAutoCaptureOnStart, logAutoCaptureSuppressed,
                    logStopOnToolWindowClose, logAutoOpenOnCapture, logTimestampEnabled, logTimestampFormat, logTimestampSource,
                    logFormatSql, literalParamRecovery, logFilterKeywords, logDedupeWindowMs, logMaxParseLength, logMaxCount,
                    logAutoSaveEnabled, logAutoSavePath, logAutoSaveFileNamePattern, logAutoSaveMaxFiles, logTimePrefix, slowQueryThresholdMs, showDurationInLogList,
                    dataSourceBadgeDetail,
                    crudColorStyle, crudColorSelect, crudColorInsert, crudColorUpdate, crudColorDelete, logListSqlDisplayMode,
                    paramHistoryJson, paramSchemesJson, paramActiveScheme, paramActiveSchemeByKey, paramSchemeRecentKey,
                    logHibernateEnabled, logHibernateSqlPrefix, logHibernateParamPrefix, logJdbcEnabled, logShardingSphereEnabled,
                    jdbcSqlPreviewEnabled, executionTimeoutSeconds,
                    dynamicTableNameRules, dataPermissionHint, dataPermissionMockCondition, historyMaxSize,
                    paginationSimEnabled, paginationCountEnabled, explainStructuredRender, explainPlanRetentionEnabled,
                    reportSubmitterName, reportSubmitterEmail, mcpToolsEnabled, mcpDisabledTools,
                    enhancedCaptureEnabled, enhancedCaptureConfirmed, enhancedCaptureRejectedAtMs, enhancedCaptureDesensitize,
                    displayMaskEnabled, mcpMaskEnabled,
                    enhancedCaptureStackMaxFrames,
                    onboardingAnchorTipEnabled, onboardingAnchorTipShown, sourceFrameSkipPrefixes, sourceFrameBizJarPrefixes);
        }

        @Override
        public String toString() {
            return "State(dialect=" + dialect + ", libraryEntitySweepEnabled=" + libraryEntitySweepEnabled
                    + ", sqlListPreviewHintSeen=" + sqlListPreviewHintSeen + ")";
        }
    }

    /** 设置变更通知 Topic（订阅方据此即时刷新 UI）。 */
    public static final Topic<SettingsChangeListener> SETTINGS_CHANGE_TOPIC =
            Topic.create("MpSqlVisualizerSettingsChange", SettingsChangeListener.class);

    public static final String DEFAULT_DIALECT = "MySQL";

    /** SQL 列表显示模式默认值（T-ListMode，关键字换行）。 */
    public static final String DEFAULT_LOG_LIST_DISPLAY_MODE = "KEYWORD_WRAP";

    /** 默认界面语言（M14，用户要求默认中文）。 */
    public static final String DEFAULT_LANGUAGE = "zh";

    // ---------- T-PT-04：空参数填充策略 ----------

    /** 空参数填充策略：{@code ?} 占位符（O-79 默认——未填写保留待填提示；NULL 会静默过滤行不易察觉）。 */
    public static final String EMPTY_PARAM_FILL_PLACEHOLDER = "PLACEHOLDER";

    /** 空参数填充策略：NULL 字面量。 */
    public static final String EMPTY_PARAM_FILL_NULL = "NULL";

    /** 空参数填充策略：空字符串字面量 {@code ''}。 */
    public static final String EMPTY_PARAM_FILL_EMPTY_STRING = "EMPTY_STRING";

    /** 空参数填充策略默认值（O-79 定案：{@code ?} 占位，待填提示醒目；NULL/'' 档换取可直接执行的 SQL）。 */
    public static final String DEFAULT_EMPTY_PARAM_FILL = EMPTY_PARAM_FILL_PLACEHOLDER;

    /** 表名推断策略：驼峰转下划线（默认，对齐 MP，`BikeDeviceInfoEntity` → `bike_device_info`）。 */
    public static final String INFERENCE_SNAKE = "SNAKE";

    /** 表名推断策略：纯保留驼峰——仅首字母小写、不去 Entity 后缀（`BikeDeviceInfoEntity` → `bikeDeviceInfoEntity`）。 */
    public static final String INFERENCE_CAMEL = "CAMEL";

    /** 反向转换 Wrapper 形态：自动（有实体 → Lambda，无实体 → 字符串）。 */
    public static final String REVERSE_AUTO = "AUTO";

    /** 反向转换 Wrapper 形态：恒字符串列名 QueryWrapper。 */
    public static final String REVERSE_QUERY = "QUERY";

    /** 反向转换 Wrapper 形态：LambdaQueryWrapper（无实体回落字符串）。 */
    public static final String REVERSE_LAMBDA = "LAMBDA";

    /** 反向转换 Wrapper 形态：Wrappers 静态工厂（有实体 lambdaQuery / 无实体 query，T-M36.6）。 */
    public static final String REVERSE_WRAPPERS = "WRAPPERS";

    /** 反向转换 Wrapper 形态：MPJLambdaWrapper（无实体回落字符串）。 */
    public static final String REVERSE_MPJ_LAMBDA = "MPJ_LAMBDA";

    /** 嵌入列命名：自动（按工程 Hibernate 命名配置判定，T-EmbeddedColPrefix 默认）。 */
    public static final String EMBEDDED_NAMING_AUTO = "AUTO";

    /** 嵌入列命名：无前缀（JPA 默认，`qty.baseQty` → `base_qty`）。 */
    public static final String EMBEDDED_NAMING_NONE = "NONE";

    /** 嵌入列命名：组件路径前缀（Hibernate component-path，`qty.baseQty` → `qty_base_qty`）。 */
    public static final String EMBEDDED_NAMING_COMPONENT_PATH = "COMPONENT_PATH";

    // ---------- SQL 日志默认值（T-M31.14） ----------
    public static final String DEFAULT_LOG_SQL_PREFIX = "Preparing:";
    public static final String DEFAULT_LOG_PARAM_PREFIX = "Parameters:";
    public static final String DEFAULT_LOG_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss";
    public static final String DEFAULT_LOG_TIMESTAMP_SOURCE = "log";
    public static final String LOG_TIMESTAMP_SOURCE_LOG = "log";
    public static final String LOG_TIMESTAMP_SOURCE_CAPTURE = "capture";
    public static final long DEFAULT_LOG_DEDUPE_WINDOW_MS = 60000L;
    public static final int DEFAULT_LOG_MAX_PARSE_LENGTH = 20000;
    public static final int DEFAULT_LOG_MAX_COUNT = 200;

    /** 调用栈快照总帧数缺省（T-StackHoverUi）：栈顶 B-8 帧＋栈底固定 8 帧；agent 侧钳位 [12,64]。 */
    public static final int DEFAULT_ENHANCED_CAPTURE_STACK_MAX_FRAMES = 24;
    public static final int MIN_ENHANCED_CAPTURE_STACK_MAX_FRAMES = 12;
    public static final int MAX_ENHANCED_CAPTURE_STACK_MAX_FRAMES = 64;

    /** 自动保存历史 SQL 默认文件名模板（F-43）。 */
    public static final String DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN = "sql-history-$yyyy-MM-dd$.sql";

    /** 历史 SQL 文件保留上限默认值（T-HistoryFileRetention）：默认留最近 30 个文件（按日模板≈约一个月），防磁盘膨胀；0/负 = 全量保留。 */
    public static final int DEFAULT_LOG_AUTO_SAVE_MAX_FILES = 30;

    // ---------- O-1 日志窗口差异化默认值 ----------
    public static final String DEFAULT_LOG_TIME_PREFIX = "Time:";
    public static final long DEFAULT_SLOW_QUERY_THRESHOLD_MS = 1000L;

    // ---------- Hibernate/JPA 日志默认值 ----------
    public static final String DEFAULT_LOG_HIBERNATE_SQL_PREFIX = "Hibernate:";
    public static final String DEFAULT_LOG_HIBERNATE_PARAM_PREFIX = "binding parameter";
    public static final String DEFAULT_LOGIC_DELETE_VALUE = "0";

    /** 默认租户列名（T-TenantLine，对齐 MP TenantLineInnerInterceptor 默认列名）。 */
    public static final String DEFAULT_TENANT_COLUMN_NAME = "tenant_id";

    /** 默认租户列值（F-6 Phase A）：UPDATE SET / INSERT 追加租户列时的字面量默认值。 */
    public static final String DEFAULT_TENANT_COLUMN_DEFAULT = "1";

    /** 默认 SQL 历史记录上限（F-7，非收藏条目）。 */
    public static final int DEFAULT_HISTORY_MAX_SIZE = 200;

    /** 默认执行超时（秒；0=不限）——单一定义点在 ExecutionTimeoutGuard，State 侧引用避免双份字面量。 */
    public static final int DEFAULT_EXECUTION_TIMEOUT_SECONDS =
        cn.song.mybatisplus.visualizer.execute.ExecutionTimeoutGuard.DEFAULT_TIMEOUT_SECONDS;

    // ---------- T-CrudColor：SQL 列表 CRUD 类型颜色标记默认值 ----------
    // 单一真源在 SqlListTypeColor（语义与其渲染同处一处），State 侧只引用，避免双份字面量漂移。
    /** 默认标记形态：左侧色条。 */
    public static final String CRUD_COLOR_STYLE_BAR = SqlListTypeColor.STYLE_BAR;

    /** 备选标记形态：整行淡背景（选中态走描边）。 */
    public static final String CRUD_COLOR_STYLE_BACKGROUND = SqlListTypeColor.STYLE_BACKGROUND;

    /** 关闭标记。 */
    public static final String CRUD_COLOR_STYLE_OFF = SqlListTypeColor.STYLE_OFF;

    public static final String CRUD_COLOR_DEFAULT_SELECT = SqlListTypeColor.DEFAULT_SELECT;
    public static final String CRUD_COLOR_DEFAULT_INSERT = SqlListTypeColor.DEFAULT_INSERT;
    public static final String CRUD_COLOR_DEFAULT_UPDATE = SqlListTypeColor.DEFAULT_UPDATE;
    public static final String CRUD_COLOR_DEFAULT_DELETE = SqlListTypeColor.DEFAULT_DELETE;

    /** 自动折叠触发的顶层字段数阈值（T-AutoFold）。 */
    public static final int DEFAULT_AUTO_FOLD_FIELD_THRESHOLD = 10;
}
