package cn.song.mybatisplus.visualizer.log;

/**
 * F-44-7：SqlSourceInterceptor 和配置类的代码模板。
 *
 * 生成可直接编译的 Java 源码，用户项目重启后自动生效。
 * 标记行格式与 {@link SqlSourceMarker} 保持一致：
 * {@code SQLVIS-SOURCE|<相对路径>|<行号>}（3 段，旧版存量形态）
 * {@code SQLVIS-SOURCE|<相对路径>|<行号>|<mapper 语句 id>}（4 段，M56 T-M56-00 起）
 * {@code SQLVIS-SOURCE|<相对路径>|<行号>|<mapper 语句 id>|<请求标签>}（5 段，MDC 兜底可选）
 */
public final class SourceInterceptorTemplates {

    /** 拦截器类文件名。 */
    public static final String INTERCEPTOR_FILE_NAME = "SqlSourceInterceptor.java";

    /** 配置类文件名。 */
    public static final String CONFIG_FILE_NAME = "SqlVisualizerConfig.java";

    /** 原生 MyBatis 包装插件类文件名（R24；与 {@link MyBatisNativeWiring#NATIVE_PLUGIN_FILE_NAME} 同源）。 */
    public static final String NATIVE_PLUGIN_FILE_NAME = MyBatisNativeWiring.NATIVE_PLUGIN_FILE_NAME;

    private SourceInterceptorTemplates() {
    }

    /**
     * 生成原生 MyBatis 包装插件类代码（R24）：{@code <plugin>} 注册的硬前提是实现
     * org.apache.ibatis.plugin.Interceptor——拦截器本体（InnerInterceptor）不满足，
     * 由本薄包装按无参构造实例化并分发。装配点写入 mybatis-config.xml（MyBatisNativeWiring）。
     */
    public static String generateNativePluginClass(String basePackage) {
        return """
package __BASE__.config;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.util.Collections;
import java.util.Properties;

/**
 * SQL Visualizer 原生 MyBatis 注册插件（自动生成）。
 *
 * MyBatis 解析 mybatis-config.xml 的 &lt;plugins&gt; 时按**无参构造**实例化 &lt;plugin interceptor="..."&gt;
 * 指向的类（必须是 org.apache.ibatis.plugin.Interceptor），由本类持有并分发到
 * {@link SqlSourceInterceptor}（InnerInterceptor 本体，与 Spring Boot 注入共用同一份捕获逻辑）。
 *
 * 自动生成于 SQL Visualizer 插件，可安全删除（删除后实时日志不再携带源码位置）。
 */
@Intercepts({
    @Signature(type = Executor.class, method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(type = Executor.class, method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                CacheKey.class, BoundSql.class}),
    @Signature(type = Executor.class, method = "update",
        args = {MappedStatement.class, Object.class}),
})
public class SqlVisualizerNativePlugin implements Interceptor {

    private final SqlSourceInterceptor delegate;

    public SqlVisualizerNativePlugin() {
        // 第二参数把本包装类自己也登记进自跳帧表：intercept 帧在调用栈上且落在 appPackages 内，
        // 不登记的话 locateSource 会把包装器自己当成用户调用点上报（标记行指到本文件）。
        this.delegate = new SqlSourceInterceptor(Collections.singleton("__BASE__."), getClass().getName());
    }

    /** 标记行捕获失败绝不影响业务执行（整体 try/catch 兜底）。 */
    public Object intercept(Invocation invocation) throws Throwable {
        try {
            Object[] args = invocation.getArgs();
            if (args.length > 0 && args[0] instanceof MappedStatement) {
                MappedStatement ms = (MappedStatement) args[0];
                Executor executor = (Executor) invocation.getTarget();
                if ("update".equals(invocation.getMethod().getName())) {
                    delegate.beforeUpdate(executor, ms, args.length > 1 ? args[1] : null);
                } else {
                    BoundSql boundSql = args.length > 5 && args[5] instanceof BoundSql ? (BoundSql) args[5] : null;
                    // 20261008 验收（3.25 真机编译）：rowBounds/resultHandler 参数显式类型化——裸三元
                    // `args[N] : null` 依赖 javac 目标类型推断，JDK8 判为 Object 无法转具体形参类型
                    // （原生 mybatis-config.xml 工程编译失败，RowBounds 与 ResultHandler 两处先后报）；
                    // 显式 instanceof+cast 让类型明确，8/17/21 全可编译。
                    delegate.beforeQuery(executor, ms,
                            args.length > 1 ? args[1] : null,
                            args.length > 2 && args[2] instanceof RowBounds ? (RowBounds) args[2] : null,
                            args.length > 3 && args[3] instanceof ResultHandler ? (ResultHandler) args[3] : null,
                            boundSql);
                }
            }
        } catch (Throwable t) {
            // 捕获逻辑自身的异常静默吞掉——标记行是旁路观测，不能打断业务查询
        }
        return invocation.proceed();
    }

    /** 不加 @Override：兼容旧版 MyBatis 接口无 default 方法的形态（与拦截器本体同一姿态）。 */
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    public void setProperties(Properties properties) {
        // 无可配置项
    }
}
""".replace("__BASE__", basePackage);
    }

    /** 生成拦截器类代码。 */
    public static String generateInterceptorClass(String basePackage) {
        return """
package __BASE__.config;

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.SQLException;
import java.util.Set;

/**
 * SQL Visualizer 源码位置捕获拦截器（自动生成）。
 *
 * 在 SQL 执行时捕获调用栈，打印源码标记行到控制台。
 * 插件解析后可实现「跳转到源码」精确跳转。
 *
 * 标记行格式：SQLVIS-SOURCE|&lt;相对路径&gt;|&lt;行号&gt;[|&lt;mapper 语句 id&gt;[|&lt;请求标签&gt;]]
 * 例：SQLVIS-SOURCE|src/main/java/com/example/service/UserService.java|87|com.example.mapper.UserMapper.selectById
 *
 * 自动生成于 SQL Visualizer 插件，可安全删除（删除后实时日志不再携带源码位置）。
 */
public class SqlSourceInterceptor implements InnerInterceptor {

    private final Set<String> appPackages;

    /** R24：额外自跳帧类名（原生 MyBatis 包装器把自己的类名传进来，intercept 帧不被当成调用点）。 */
    private final Set<String> selfClassNames;

    public SqlSourceInterceptor(Set<String> appPackages) {
        this(appPackages, new String[0]);
    }

    /**
     * extraSelfClassNames：外层包装类的类名（原生 MyBatis 场景，SqlVisualizerNativePlugin
     * 构造委托时传 getClass().getName()）。包装器的 intercept 帧在调用栈上且落在 appPackages
     * 内——不登记进自跳帧表的话，locateSource 会把包装器自己当成用户调用点上报。
     */
    public SqlSourceInterceptor(Set<String> appPackages, String... extraSelfClassNames) {
        this.appPackages = appPackages;
        this.selfClassNames = new java.util.HashSet<>(java.util.Arrays.asList(extraSelfClassNames));
    }

    /**
     * 查询前拦截——打印源码标记行。
     * 注意：不加 @Override，兼容不同 MP 版本的 InnerInterceptor 接口签名差异。
     */
    @SuppressWarnings("unused")
    public void beforeQuery(Executor executor, MappedStatement ms,
            Object parameter, RowBounds rowBounds,
            ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
        emitSource(ms == null ? null : ms.getId());
    }

    /**
     * 更新前拦截——打印源码标记行。
     */
    @SuppressWarnings("unused")
    public void beforeUpdate(Executor executor, MappedStatement ms,
            Object parameter) throws SQLException {
        emitSource(ms == null ? null : ms.getId());
    }

    /**
     * 把当前线程调用栈里的真实调用点打印成标记行（3~5 段）：
     * 第 4 段为 mapper 语句 id（ms.getId()，null 省略）；第 5 段为请求标签（MDC 兜底，
     * 仅在第 4 段在场时输出——段位固定不可跳位）。
     */
    private void emitSource(String mapperStatement) {
        String[] src = locateSource();
        if (src != null) {
            StringBuilder sb = new StringBuilder("SQLVIS-SOURCE|")
                    .append(src[0]).append('|').append(src[1]);
            if (mapperStatement != null && !mapperStatement.isEmpty()) {
                sb.append('|').append(mapperStatement);
                String tag = requestTagFromMdc();
                if (tag != null && !tag.isEmpty()) {
                    sb.append('|').append(tag);
                }
            }
            System.out.println(sb);
        }
    }

    /** 反射读取 MDC 请求标签（org.slf4j.MDC.get("sqlvisRequestTag")），无 slf4j 时静默返回 null。 */
    private String requestTagFromMdc() {
        try {
            Class<?> mdc = Class.forName("org.slf4j.MDC");
            Object v = mdc.getMethod("get", String.class).invoke(null, "sqlvisRequestTag");
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 沿调用栈跳过框架帧和拦截器自身，取第一个用户代码帧。返回 [相对路径, 行号] 或 null。 */
    private String[] locateSource() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String cn = frame.getClassName();
            if (isFrameworkFrame(cn)) continue;
            if (isSelf(cn)) continue;  // 跳过拦截器自身
            if (!appPackages.isEmpty() && startsWithNone(cn)) continue;
            String fileName = frame.getFileName();
            if (fileName == null) continue;
            String pkg = substringBeforeLast(cn, '.');
            String rel = pkg.isEmpty() ? fileName : pkg.replace('.', '/') + "/" + fileName;
            int line = frame.getLineNumber();
            if (line < 0) continue;
            return new String[]{rel, String.valueOf(line)};
        }
        return null;
    }

    /**
     * 是否为拦截器自身（跳过，避免捕获到自己的位置）。
     * 用运行时类名而非生成期包名常量——用户拷贝/改包/改类名后自跳过依旧生效
     * （历史缺陷：包名常量与运行类分叉时拦截器自身帧被当成调用点上报）。
     */
    private boolean isSelf(String cn) {
        if (cn.equals(getClass().getName())) return true;
        for (String self : selfClassNames) {
            if (cn.equals(self)) return true;
        }
        return false;
    }

    /** 取 str 中 delimiter 最后一次出现之前的子串（兼容 Java 8+，无 Kotlin 扩展方法）。 */
    private static String substringBeforeLast(String str, char delimiter) {
        int idx = str.lastIndexOf(delimiter);
        return idx < 0 ? "" : str.substring(0, idx);
    }

    /** 检查 cn 是否不以任何 appPackage 开头。 */
    private boolean startsWithNone(String cn) {
        for (String pkg : appPackages) {
            if (cn.startsWith(pkg)) return false;
        }
        return true;
    }

    /**
     * 框架 / 基础设施帧一律跳过（不视为调用点）。
     *
     * <b>T-AgentResiduals R1b（2026-09-22）：本份是生效副本，与 agent 侧 {@code StackLocator}
     * 的 {@code FRAME_SKIP_PREFIXES} 同集</b>。此前它只收 JDK / 平台 / 本插件 / MyBatis 系 15 项，
     * 而 agent 侧另收驱动族 / 连接池 / AOP / 数据层插件族四十余项 —— 于是同一执行两侧可能给出
     * 不同位置：agent 通道的事件带正确位置，而本模板产出的 {@code SQLVIS-SOURCE} 标记行会指到
     * 框架帧（用户点跳转即落进第三方库）。R1 当时只同步了 IDE 侧参考副本与文档示例，
     * 本份（真正在用户应用里跑的那份）漏了 —— 故补此表，并把守卫扩到全部副本。
     *
     * 为什么收宽前缀（{@code com.alibaba.} / {@code org.apache.} / {@code com.google.} 等）：
     * 数据层插件帧一旦漏收就被误选为「调用点」（PageHelper 实证）。若你的业务代码恰好落在这些
     * 宽包下，请删掉对应那几行 —— 本模板是拷进你工程的源码，随你改。
     *
     * <b>迁移前提（存量工程必读）</b>：本模板只在注入那一刻写进你的工程。已经注入过的工程，
     * 若文件是当前形态（含 {@code getClass().getName()}），插件幂等跳过、不会自动更新——
     * 即本次补齐只对新注入的拦截器与旧形态副本的内容升级生效。要让存量工程也拿到新表：
     * 删除注入的 {@code SqlSourceInterceptor.java} 后重新执行注入，或照上面几行手工补前缀。
     */
    private boolean isFrameworkFrame(String cn) {
        return cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("jdk.") ||
                cn.startsWith("sun.") || cn.startsWith("com.sun.") || cn.startsWith("jdk.internal.") ||
                cn.startsWith("com.intellij.") || cn.startsWith("org.jetbrains.") ||
                // 本插件自身 + agent 自身（agent 帧同样在应用 JVM 的栈上）
                cn.startsWith("cn.song.mybatisplus.visualizer.") || cn.startsWith("cn.song.mybatisplus.sqlvis.") ||
                // 驱动家族
                cn.startsWith("com.mysql.") || cn.startsWith("org.mariadb.") || cn.startsWith("org.postgresql.") ||
                cn.startsWith("org.h2.") || cn.startsWith("org.hsqldb.") || cn.startsWith("org.sqlite.") ||
                cn.startsWith("com.microsoft.sqlserver.") || cn.startsWith("oracle.jdbc.") ||
                cn.startsWith("dm.jdbc.") || cn.startsWith("com.gbasedbt.") || cn.startsWith("com.kingbase8.") ||
                // 信创 PG 系新族：海量 Vastbase（cn.com.vastbase.*）/ 瀚高 HighGo（com.highgo.*）
                cn.startsWith("cn.com.vastbase.") || cn.startsWith("com.highgo.") ||
                // OceanBase（com.oceanbase.*）：独立实现非 MySQL 分叉（URL 前缀 jdbc:oceanbase: 唯一）
                cn.startsWith("com.oceanbase.") ||
                // 华为 GaussDB 商用 / openGauss 品牌（同挂 com.huawei.）与社区 openGauss 2.x/3.x
                cn.startsWith("com.huawei.") || cn.startsWith("org.opengauss.") ||
                // 连接池 / ORM / Spring 数据层
                cn.startsWith("com.zaxxer.") || cn.startsWith("com.alibaba.druid.") ||
                cn.startsWith("com.mchange.") || cn.startsWith("org.apache.commons.dbcp2.") ||
                cn.startsWith("org.apache.tomcat.") || cn.startsWith("org.apache.ibatis.") ||
                cn.startsWith("com.baomidou.") || cn.startsWith("org.hibernate.") ||
                cn.startsWith("org.springframework.") || cn.startsWith("org.aspectj.") ||
                cn.startsWith("net.sf.cglib.") || cn.startsWith("org.objectweb.asm.") ||
                cn.startsWith("com.sun.proxy.") || cn.startsWith("org.jboss.") ||
                // 数据层插件 / 基础设施家族补齐（漏收即被误选为用户帧——PageHelper 实证）
                cn.startsWith("com.github.pagehelper.") || cn.startsWith("org.mybatis.") ||
                cn.startsWith("net.sf.jsqlparser.") || cn.startsWith("com.alibaba.") ||
                cn.startsWith("org.apache.") || cn.startsWith("com.google.") ||
                cn.startsWith("com.fasterxml.") || cn.startsWith("io.netty.") ||
                cn.startsWith("io.lettuce.") || cn.startsWith("redis.clients.") ||
                cn.startsWith("io.micrometer.") || cn.startsWith("io.seata.") ||
                cn.startsWith("org.slf4j.") || cn.startsWith("ch.qos.") ||
                cn.startsWith("org.ehcache.") || cn.startsWith("net.sf.ehcache.") ||
                cn.startsWith("com.github.benmanes.") || cn.startsWith("org.quartz.") ||
                cn.startsWith("com.netflix.") || cn.startsWith("org.elasticsearch.") ||
                cn.startsWith("com.mongodb.") ||
                // MPJ（mybatis-plus-join）全族
                cn.startsWith("com.github.yulichang.") ||
                cn.startsWith("$Proxy");
    }
}
""".replace("__BASE__", basePackage);
    }

    /** 生成 Spring Boot 配置类代码。 */
    public static String generateSpringBootConfigClass(String basePackage) {
        return """
package __BASE__.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.Map;

/**
 * SQL Visualizer 源码位置捕获配置（自动生成）。
 *
 * 将 SqlSourceInterceptor 添加到 MyBatis-Plus 拦截器链中，
 * 使实时日志携带源码位置信息，支持精确跳转。
 *
 * 兼容三种场景：
 *  1. 用户已定义 MybatisPlusInterceptor bean（如 MyBatisPlusConfig）→ 追加拦截器
 *  2. MP 自动配置创建了 MybatisPlusInterceptor → 追加拦截器
 *  3. 无任何 MybatisPlusInterceptor → 自动创建并注册到容器
 *
 * 使用 SmartInitializingSingleton 确保所有 bean 初始化完成后注册，避免时序问题。
 *
 * 自动生成于 SQL Visualizer 插件，可安全删除。
 */
@Configuration
public class SqlVisualizerConfig {

    /** 定义 SqlSourceInterceptor bean（不创建 MybatisPlusInterceptor，避免 bean 名称冲突）。 */
    @Bean
    public SqlSourceInterceptor sqlSourceInterceptor() {
        return new SqlSourceInterceptor(Collections.singleton("__BASE__."));
    }

    /**
     * 在所有 singleton bean 初始化完成后，将 SqlSourceInterceptor 添加到 MybatisPlusInterceptor。
     *
     * - 遍历所有 MybatisPlusInterceptor bean 并追加（兼容多数据源）
     * - 无任何 MybatisPlusInterceptor 时，自动创建并注册到容器
     * - 去重检查避免重复添加
     */
    @Bean
    public SmartInitializingSingleton sqlVisualizerInterceptorRegistrar(
            ApplicationContext applicationContext) {
        return () -> {
            SqlSourceInterceptor sqlSourceInterceptor =
                    applicationContext.getBean(SqlSourceInterceptor.class);
            Map<String, MybatisPlusInterceptor> beans =
                    applicationContext.getBeansOfType(MybatisPlusInterceptor.class);

            if (beans.isEmpty()) {
                // 无任何 MybatisPlusInterceptor → 创建并注册
                MybatisPlusInterceptor newInterceptor = new MybatisPlusInterceptor();
                newInterceptor.addInnerInterceptor(sqlSourceInterceptor);
                ConfigurableListableBeanFactory factory =
                        ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
                factory.registerSingleton("sqlVisualizerMybatisPlusInterceptor", newInterceptor);
            } else {
                // 追加到所有已有的 MybatisPlusInterceptor
                // 注意：不去重（兼容旧版 MP 无 getInnerInterceptors 方法）；
                // 重复添加仅导致重复打印标记行，插件只取第一个标记
                for (MybatisPlusInterceptor mpInterceptor : beans.values()) {
                    mpInterceptor.addInnerInterceptor(sqlSourceInterceptor);
                }
            }
        };
    }
}
""".replace("__BASE__", basePackage);
    }
}
