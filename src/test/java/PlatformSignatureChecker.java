import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨版本平台签名检查器（T-DynUpdate 262 签名漂移事故的回归护栏）。
 *
 * 由各发行版自带的 JBR 运行（字节码版本天然兼容），对本发行版 lib 目录下的平台 jar
 * 用与生产代码一致的弹性匹配逻辑解析动态安装五入口；任一入口缺失/形态不满足即
 * 非零退出。 stdout 末行输出 `RESULT OK` / `RESULT MISSING <n>`。
 *
 * 用法：java -cp <checker目录> PlatformSignatureChecker <发行版lib目录>
 */
public class PlatformSignatureChecker {

    public static void main(String[] args) throws Exception {
        File lib = new File(args[0]);
        List<URL> urls = new ArrayList<URL>();
        collectJars(lib, urls);
        File modules = new File(lib, "modules");
        collectJars(modules, urls);
        // T-DbSchemaCompletion：Database Tools 是 bundled 插件（plugins/DatabaseTools/lib），
        // 不在 lib 下——ObjectKind 检查（见 #6）需要它，且它依赖 lib 里的 DynamicBundle
        // （单 DBN jar 加载会 NoClassDefFoundError: com/intellij/DynamicBundle，实测）。
        File distroRoot = lib.getParentFile();
        File dbnLib = new File(distroRoot, "plugins/DatabaseTools/lib");
        collectJars(dbnLib, urls);
        collectJars(new File(dbnLib, "modules"), urls);
        // 原生控制台client/view超接口引用独立grid模块，必须与Database Tools一起加载。
        for (String plugin : new String[]{"grid-core-impl", "grid-impl", "grid-core-plugin", "grid-plugin"}) {
            File gridLib = new File(distroRoot, "plugins/" + plugin + "/lib");
            collectJars(gridLib, urls);
            collectJars(new File(gridLib, "modules"), urls);
        }
        File miscLib = new File(distroRoot, "plugins/libraries-misc-plugin/lib");
        collectJars(miscLib, urls);
        collectJars(new File(miscLib, "modules"), urls);
        File[] mcpPlugins = new File(distroRoot, "plugins").listFiles(
                file -> file.isDirectory() && file.getName().toLowerCase(java.util.Locale.ROOT).contains("mcp"));
        if (mcpPlugins != null) {
            for (File plugin : mcpPlugins) {
                collectJars(new File(plugin, "lib"), urls);
                collectJars(new File(plugin, "lib/modules"), urls);
            }
        }
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null);

        // UI011：跨发行物生产探测必须运行于目标 JBR，不能在 243 的 JBR 21 加载 262 的 major 69。
        // 独立模式只运行 MCP 探测，不初始化其它平台 API；后两参为生产类与 Kotlin stdlib 位置。
        if (args.length == 4 && "--mcp-capability-probe".equals(args[1])) {
            int missing = checkMcpToolsetCapability(loader);
            List<URL> probeUrls = new ArrayList<URL>();
            probeUrls.add(new File(args[2]).toURI().toURL());
            probeUrls.add(new File(args[3]).toURI().toURL());
            try (URLClassLoader probeLoader = new URLClassLoader(probeUrls.toArray(new URL[0]), null)) {
                Class<?> facade = Class.forName(
                        "cn.song.mybatisplus.visualizer.mcp.McpToolsetDynamicRegistrarKt", false, probeLoader);
                Object result = facade.getMethod("findMcpToolsetClass", ClassLoader.class).invoke(null, loader);
                System.out.println("MCP_CAPABILITY=" + (result != null ? "PRESENT" : "ABSENT"));
            }
            loader.close();
            if (missing != 0) System.exit(1);
            return;
        }

        int missing = 0;
        Class<?> dp = Class.forName("com.intellij.ide.plugins.DynamicPlugins", true, loader);
        Class<?> desc = Class.forName("com.intellij.ide.plugins.IdeaPluginDescriptorImpl", true, loader);

        // 1. allowLoadUnloadWithoutRestart：首参弹性（262/243 首参可能漂移），优先最少参数
        missing += check(byFirstParam(dp, "allowLoadUnloadWithoutRestart", desc),
                "allowLoadUnloadWithoutRestart(<descriptor>)");

        // 2. unloadPlugin：首参弹性（262=PluginMainDescriptor 子类，243=IdeaPluginDescriptorImpl）
        Method unload = byFirstParam(dp, "unloadPlugin", desc);
        missing += check(unload, "unloadPlugin(<descriptor>)");
        if (unload != null && unload.getParameterTypes().length >= 2) {
            try {
                unload.getParameterTypes()[1].getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                System.out.println("MISS unloadPlugin options no-arg ctor: " + t);
                missing++;
            }
        }

        // 3. installAndLoadDynamicPlugin(Path, JComponent, IdeaPluginDescriptorImpl)
        Class<?> installer = Class.forName("com.intellij.ide.plugins.PluginInstaller", true, loader);
        missing += check(byNameAndCount(installer, "installAndLoadDynamicPlugin", 3),
                "installAndLoadDynamicPlugin(3 params)");

        // 4. loadDescriptorFromArtifact(Path, BuildNumber)
        Class<?> pdl = Class.forName("com.intellij.ide.plugins.PluginDescriptorLoader", true, loader);
        missing += check(byNameAndCount(pdl, "loadDescriptorFromArtifact", 2),
                "loadDescriptorFromArtifact(2 params)");

        // 5. findPlugin(PluginId)
        Class<?> core = Class.forName("com.intellij.ide.plugins.PluginManagerCore", true, loader);
        missing += check(byNameAndCount(core, "findPlugin", 1), "findPlugin(1 param)");

        // 6. T-DbSchemaCompletion：DatabaseSchemaResolver 新增的反射入口——
        //    `ObjectKind.COLUMN` 必须是可读的 public static final 字段。DBN 的 ObjectKind 是
        //    手写 type-safe enum（非 java.lang.Enum，enumConstants 恒 null）：历史上按 enumConstants
        //    取，于是数据源 schema 列遍历一次都没执行过且静默无日志（真机潜伏数周）。
        //    Database Tools 未捆绑的发行版（Community/裁剪包）跳过本项，不计缺失。
        missing += checkObjectKindColumn(loader);

        // 7. F48（T-PerfQuick5）：SqlMapperParserDefinition 编程式注册的卸载摘除路径——
        //    KeyedExtensionCollector 三参 addExplicitExtension（内部 Disposer.register(parent,
        //    removeExplicitExtension)，243 源码 :80-82）与两参 removeExplicitExtension。
        //    生产摘除挂插件应用级宿主（SqlMapperInjectionBarrierHost），动态卸载容器级联即摘。
        Class<?> collector = Class.forName("com.intellij.openapi.util.KeyedExtensionCollector", true, loader);
        missing += check(byNameAndCount(collector, "addExplicitExtension", 3),
                "addExplicitExtension(3 params)");
        missing += check(byNameAndCount(collector, "removeExplicitExtension", 2),
                "removeExplicitExtension(2 params)");

        // 8. T-XmlSynHi D68：mapper XML 注入门「平台 per-host 探测」的运行面——
        //    MultiHostInjector.MULTIHOST_INJECTOR_EP_NAME（ProjectExtensionPointName，EP 名串
        //    com.intellij.multiHostInjector；243 lib/util-8.jar / 262 lib/intellij.platform.core.jar
        //    双版 javap 实证同形态）+ getExtensions(AreaInstance) + MultiHostRegistrar stub 三方法
        //    （startInjecting 二参默认实现委托一参）。均为运行期按名解析的平台签名，漂移即本项红。
        //    注：initialize=false——仅核签名不触发静态初始化（EP 常量构造依赖平台引导态，裸
        //    URLClassLoader 不具备，true 形态会 NoClassDefFoundError 假红）。
        missing += checkPerHostProbeApis(loader);
        missing += checkEditorColorsTopic(loader);
        missing += checkSqlContextApis(loader, dbnLib.isDirectory());
        missing += checkExecutionDialectGateApis(loader, dbnLib.isDirectory());
        missing += checkModuleCountApi(loader);
        missing += checkPreviewSourceContextApis(loader, dbnLib.isDirectory());
        missing += checkSqlLogTabsApis(loader);
        missing += checkPreviewEditorPopupApis(loader);
        missing += checkNativeConsoleViewLifetimeApis(loader, dbnLib.isDirectory());
        missing += checkAttachTargetApis(loader);
        missing += checkRunnerFinalStepApi(loader);
        missing += checkMcpToolsetCapability(loader);
        // UI025：预览错列 ExternalAnnotator 的编译期 API 面（243 app-client / 253 app 实测同形）——
        // 生产不反射，但跨版本核验保证「注册后 annotator 真能被平台调度」的四段签名不漂移。
        missing += checkExternalAnnotatorApis(loader);

        if (missing > 0) {
            System.out.println("RESULT MISSING " + missing);
            System.exit(1);
        }
        System.out.println("RESULT OK");
    }

    /** 精确核验生产查既有view入口；不初始化或创建数据库会话。 */
    public static int checkNativeConsoleViewLifetimeApis(ClassLoader loader, boolean databaseBundled) {
        if (!databaseBundled) {
            System.out.println("SKIP native console lifetime: Database Tools not bundled");
            return 0;
        }
        try {
            Class<?> service = Class.forName("com.intellij.database.console.session.DatabaseSessionViewService", false, loader);
            Class<?> client = Class.forName("com.intellij.database.console.client.DatabaseSessionClientWithFile", false, loader);
            Class<?> view = Class.forName("com.intellij.database.run.session.LogView", false, loader);
            Class<?> disposable = Class.forName("com.intellij.openapi.Disposable", false, loader);
            if (!disposable.isAssignableFrom(view)) {
                System.out.println("MISS native console LogView must implement Disposable");
                return 1;
            }
            Class<?> session = Class.forName("com.intellij.database.console.session.DatabaseSession", false, loader);
            Class<?> baseSession = Class.forName("com.intellij.database.run.session.Session", false, loader);
            Class<?> baseView = Class.forName("com.intellij.database.run.session.BaseLogView", false, loader);
            Class<?> languageConsole = Class.forName("com.intellij.execution.console.LanguageConsoleView", false, loader);
            Method getConsole = baseView.getMethod("getConsoleView");
            int consoleMissing = getConsole.getReturnType() == languageConsole &&
                    !java.lang.reflect.Modifier.isStatic(getConsole.getModifiers()) && disposable.isAssignableFrom(languageConsole)
                    ? check(getConsole, "BaseLogView.getConsoleView[]") : 1;
            return consoleMissing + checkNativeConsoleViewLifetimeSignatures(service, client, view)
                    + checkSessionOutputSignatures(service, client, session, baseSession, view);
        } catch (Throwable t) {
            System.out.println("MISS native console lifetime: " + t);
            return 1;
        }
    }

    public static int checkNativeConsoleViewLifetimeSignatures(Class<?> service, Class<?> client, Class<?> view) {
        int missing = 0;
        try {
            Method method = service.getMethod("getInstance");
            if (method.getReturnType() != service || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                System.out.println("MISS DatabaseSessionViewService.getInstance[] shape");
                missing++;
            } else missing += check(method, "DatabaseSessionViewService.getInstance[]");
        } catch (NoSuchMethodException e) { missing++; }
        try {
            Method method = service.getMethod("getClientViewIfAny", client);
            if (method.getReturnType() != view || java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                System.out.println("MISS DatabaseSessionViewService.getClientViewIfAny shape");
                missing++;
            } else missing += check(method, "DatabaseSessionViewService.getClientViewIfAny[DatabaseSessionClientWithFile]");
        } catch (NoSuchMethodException e) { missing++; }
        return missing;
    }

    public static int checkSessionOutputSignatures(Class<?> service, Class<?> client, Class<?> session,
                                                   Class<?> baseSession, Class<?> view) {
        int missing = 0;
        try {
            Method m = client.getMethod("getSession");
            if (m.getReturnType() != baseSession || java.lang.reflect.Modifier.isStatic(m.getModifiers())) missing++;
            else missing += check(m, "DatabaseSessionClientWithFile.getSession[]");
        } catch (NoSuchMethodException e) { missing++; }
        try {
            Method m = service.getMethod("getViewIfAny", session);
            if (m.getReturnType() != view || java.lang.reflect.Modifier.isStatic(m.getModifiers())) missing++;
            else missing += check(m, "DatabaseSessionViewService.getViewIfAny[DatabaseSession]");
        } catch (NoSuchMethodException e) { missing++; }
        return missing;
    }

    /** UI014：数据源选择完成后再弹确认，精确登记生产调用签名。 */
    private static int checkRunnerFinalStepApi(ClassLoader loader) {
        try {
            Class<?> step = Class.forName("com.intellij.openapi.ui.popup.util.BaseStep", false, loader);
            Method method = step.getMethod("doFinalStep", Runnable.class);
            if (!"com.intellij.openapi.ui.popup.PopupStep".equals(method.getReturnType().getName()) ||
                    java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                System.out.println("MISS BaseStep.doFinalStep(Runnable): unexpected return type or modifiers");
                return 1;
            }
            return check(method, "BaseStep.doFinalStep(Runnable)");
        } catch (Throwable t) {
            System.out.println("MISS BaseStep.doFinalStep(Runnable): " + t);
            return 1;
        }
    }

    /** 校验 ObjectKind.COLUMN 静态字段，Database Tools 缺席时跳过。 */
    private static int checkObjectKindColumn(ClassLoader loader) {
        try {
            Class<?> kind = Class.forName("com.intellij.database.model.ObjectKind", true, loader);
            Object[] constants = kind.getEnumConstants();
            Object column = kind.getField("COLUMN").get(null);
            System.out.println("INFO ObjectKind.COLUMN enumConstants=" + (constants == null ? "null" : "non-null")
                    + " isEnum=" + kind.isEnum() + " value=" + column);
            if (column == null) {
                System.out.println("MISS ObjectKind.COLUMN static field resolves to null");
                return 1;
            }
            return 0;
        } catch (ClassNotFoundException e) {
            System.out.println("SKIP DatabaseTools (Database Tools) not bundled: " + e.getMessage());
            return 0;
        } catch (Throwable t) {
            System.out.println("MISS ObjectKind.COLUMN resolve failed: " + t);
            return 1;
        }
    }

    /**
     * #8（T-XmlSynHi D68）：per-host 注入探针运行面核验。
     *
     * 顺带打印三代 SQL 注入器在本发行版的在场实况（INFO 不计缺失）——
     *  - `com.intellij.sql.injector.SqlLanguageInjector`（历史让位门槛的判定对象）在 243/262
     *    发行物已不存在（本仓 599/1809 jar 全扫实证），类在场判定废止的直接证据；
     *  - SqlTypeBasedInjector 按代际不同名（243=com.intellij.sql.injection，database-plugin.jar
     *    META-INF/intellilang-sql-support.xml 注册；262=com.intellij.database.sql.intelliLang.injection，
     *    META-INF/plugin.xml 注册）——per-host 探测经 EP 枚举对改名免疫，故只作 INFO 不判缺失。
     */
    private static int checkPerHostProbeApis(ClassLoader loader) {
        int missing = 0;
        try {
            Class<?> mhi = Class.forName("com.intellij.lang.injection.MultiHostInjector", false, loader);
            try {
                java.lang.reflect.Field epField = mhi.getField("MULTIHOST_INJECTOR_EP_NAME");
                System.out.println("INFO MULTIHOST_INJECTOR_EP_NAME type=" + epField.getType().getName());
                missing += check(byNameAndCount(epField.getType(), "getExtensions", 1),
                        "ProjectExtensionPointName.getExtensions(1 param)");
            } catch (NoSuchFieldException e) {
                System.out.println("MISS MULTIHOST_INJECTOR_EP_NAME field: " + e);
                missing++;
            }
            missing += check(byNameAndCount(mhi, "getLanguagesToInject", 2),
                    "MultiHostInjector.getLanguagesToInject(2 params)");
            missing += check(byNameAndCount(mhi, "elementsToInjectIn", 0),
                    "MultiHostInjector.elementsToInjectIn()");

            Class<?> registrar = Class.forName("com.intellij.lang.injection.MultiHostRegistrar", false, loader);
            missing += check(byNameAndCount(registrar, "startInjecting", 1),
                    "MultiHostRegistrar.startInjecting(1 param)");
            missing += check(byNameAndCount(registrar, "addPlace", 4),
                    "MultiHostRegistrar.addPlace(4 params)");
            missing += check(byNameAndCount(registrar, "doneInjecting", 0),
                    "MultiHostRegistrar.doneInjecting()");
        } catch (ClassNotFoundException e) {
            System.out.println("MISS per-host probe platform types: " + e);
            return 1;
        }

        try {
            Class.forName("com.intellij.sql.injector.SqlLanguageInjector", false, loader);
            System.out.println("INFO legacy SqlLanguageInjector present (pre-243 代际；类在场判定已废止)");
        } catch (ClassNotFoundException absent) {
            System.out.println("INFO legacy SqlLanguageInjector absent（243+ 已移除——类在场让位门槛废止的实证）");
        }
        boolean anyTypeBased = false;
        String[] generations = {
                "com.intellij.sql.injection.SqlTypeBasedInjector",
                "com.intellij.database.sql.intelliLang.injection.SqlTypeBasedInjector",
        };
        for (String fqn : generations) {
            try {
                Class<?> c = Class.forName(fqn, false, loader);
                Class<?>[] ifaces = c.getInterfaces();
                boolean multiHost = false;
                for (Class<?> i : ifaces) {
                    if ("com.intellij.lang.injection.MultiHostInjector".equals(i.getName())) {
                        multiHost = true;
                        break;
                    }
                }
                System.out.println("INFO SqlTypeBasedInjector present: " + fqn
                        + " implementsMultiHostInjector=" + multiHost);
                anyTypeBased = true;
            } catch (ClassNotFoundException otherGeneration) {
                // 代际缺席正常（243 无 database.* 代、262 无 sql.injection 注册代）
            }
        }
        if (!anyTypeBased) {
            System.out.println("INFO SqlTypeBasedInjector absent in this distro（Community/裁剪包形态，探针按 EP 空清单判不让位）");
        }
        return missing;
    }

    private static int checkEditorColorsTopic(ClassLoader loader) {
        try {
            Class<?> manager = Class.forName("com.intellij.openapi.editor.colors.EditorColorsManager", false, loader);
            Class<?> listener = Class.forName("com.intellij.openapi.editor.colors.EditorColorsListener", false, loader);
            Class<?> scheme = Class.forName("com.intellij.openapi.editor.colors.EditorColorsScheme", false, loader);
            java.lang.reflect.Field topic = manager.getField("TOPIC");
            if (!java.lang.reflect.Modifier.isStatic(topic.getModifiers()) ||
                    !"com.intellij.util.messages.Topic".equals(topic.getType().getName())) {
                System.out.println("MISS EditorColorsManager.TOPIC type or modifiers");
                return 1;
            }
            return check(listener.getMethod("globalSchemeChange", scheme),
                    "EditorColorsListener.globalSchemeChange(EditorColorsScheme)");
        } catch (Throwable t) {
            System.out.println("MISS editor color scheme change topic: " + t);
            return 1;
        }
    }

    private static int checkModuleCountApi(ClassLoader loader) {
        try {
            Class<?> project = Class.forName("com.intellij.openapi.project.Project", false, loader);
            Class<?> manager = Class.forName("com.intellij.openapi.module.ModuleManager", false, loader);
            int missing = check(manager.getMethod("getInstance", project), "ModuleManager.getInstance(Project)");
            Method modules = manager.getMethod("getModules");
            missing += check(modules, "ModuleManager.getModules()");
            if (!modules.getReturnType().isArray()) {
                System.out.println("MISS ModuleManager.getModules() return type is not an array");
                missing++;
            }
            return missing;
        } catch (Throwable t) {
            System.out.println("MISS ModuleManager module count API: " + t);
            return 1;
        }
    }

    /** O-145 W0/W1：真实注入 SQL PSI、模块全候选归属与外部系统稳定身份所依赖的平台面。 */
    private static int checkPreviewSourceContextApis(ClassLoader loader, boolean databaseToolsPresent) {
        int missing = 0;
        try {
            Class<?> project = Class.forName("com.intellij.openapi.project.Project", false, loader);
            Class<?> psiFile = Class.forName("com.intellij.psi.PsiFile", false, loader);
            Class<?> psiElement = Class.forName("com.intellij.psi.PsiElement", false, loader);
            Class<?> injected = Class.forName("com.intellij.lang.injection.InjectedLanguageManager", false, loader);
            missing += check(injected.getMethod("getInstance", project),
                    "InjectedLanguageManager.getInstance(Project)");
            missing += check(injected.getMethod("findInjectedElementAt", psiFile, int.class),
                    "InjectedLanguageManager.findInjectedElementAt(PsiFile,int)");
            Method injectedFiles = injected.getMethod("getInjectedPsiFiles", psiElement);
            missing += check(injectedFiles, "InjectedLanguageManager.getInjectedPsiFiles(PsiElement)");
            if (!List.class.isAssignableFrom(injectedFiles.getReturnType())) {
                System.out.println("MISS getInjectedPsiFiles does not return List: " + injectedFiles.getReturnType());
                missing++;
            }

            Class<?> module = Class.forName("com.intellij.openapi.module.Module", false, loader);
            Class<?> virtualFile = Class.forName("com.intellij.openapi.vfs.VirtualFile", false, loader);
            Class<?> moduleUtil = Class.forName("com.intellij.openapi.module.ModuleUtilCore", false, loader);
            missing += check(moduleUtil.getMethod("moduleContainsFile", module, virtualFile, boolean.class),
                    "ModuleUtilCore.moduleContainsFile(Module,VirtualFile,boolean)");

            Class<?> external = Class.forName(
                    "com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager", false, loader);
            missing += check(external.getMethod("getInstance", module),
                    "ExternalSystemModulePropertyManager.getInstance(Module)");
            missing += check(external.getMethod("getExternalSystemId"),
                    "ExternalSystemModulePropertyManager.getExternalSystemId()");
            missing += check(external.getMethod("getLinkedProjectId"),
                    "ExternalSystemModulePropertyManager.getLinkedProjectId()");
            missing += check(external.getMethod("getLinkedProjectPath"),
                    "ExternalSystemModulePropertyManager.getLinkedProjectPath()");

            if (databaseToolsPresent) {
                Class<?> sqlFile = Class.forName("com.intellij.sql.psi.SqlFile", false, loader);
                if (!sqlFile.isInterface() || !psiFile.isAssignableFrom(sqlFile)) {
                    System.out.println("MISS SqlFile is not a PsiFile interface: " + sqlFile);
                    missing++;
                } else {
                    System.out.println("OK   com.intellij.sql.psi.SqlFile is a PsiFile interface");
                }
            } else {
                System.out.println("SKIP SqlFile reflection type (Database Tools not bundled)");
            }
        } catch (Throwable t) {
            System.out.println("MISS preview source context API: " + t);
            return missing + 1;
        }
        return missing;
    }

    private static int checkSqlContextApis(ClassLoader loader, boolean databaseToolsPresent) {
        if (!databaseToolsPresent) {
            System.out.println("SKIP DatabaseTools SQL context APIs (bundled plugin absent)");
            return 0;
        }
        try {
            Class<?> project = Class.forName("com.intellij.openapi.project.Project", false, loader);
            Class<?> psiFile = Class.forName("com.intellij.psi.PsiFile", false, loader);
            Class<?> virtualFile = Class.forName("com.intellij.openapi.vfs.VirtualFile", false, loader);
            Class<?> dataSources = Class.forName("com.intellij.sql.dialects.SqlDataSourceMappings", false, loader);
            Class<?> dialects = Class.forName("com.intellij.sql.dialects.SqlDialectMappings", false, loader);
            Class<?> scopes = Class.forName("com.intellij.sql.dialects.SqlResolveMappings", false, loader);
            int missing = 0;
            missing += check(dataSources.getMethod("getInstance", project), "SqlDataSourceMappings.getInstance(Project)");
            for (String name : new String[] {"getDataSources", "getProvidedDataSources"}) {
                Method m = dataSources.getMethod(name, psiFile);
                missing += check(m, "SqlDataSourceMappings." + name + "(PsiFile)");
                if (!List.class.isAssignableFrom(m.getReturnType())) {
                    System.out.println("MISS " + name + " does not return List: " + m.getReturnType());
                    missing++;
                }
            }
            missing += check(dialects.getMethod("getInstance", project), "SqlDialectMappings.getInstance(Project)");
            missing += check(dialects.getMethod("getMapping", project, virtualFile), "SqlDialectMappings.getMapping(Project,VirtualFile)");
            missing += check(dialects.getMethod("getMapping", virtualFile), "SqlDialectMappings.getMapping(VirtualFile)");
            missing += check(dialects.getMethod("getAvailableValues"), "SqlDialectMappings.getAvailableValues()");
            missing += check(scopes.getMethod("getInstance", project), "SqlResolveMappings.getInstance(Project)");
            missing += check(scopes.getMethod("getMapping", virtualFile), "SqlResolveMappings.getMapping(VirtualFile)");
            missing += check(scopes.getMethod("getConfiguredMapping", virtualFile), "SqlResolveMappings.getConfiguredMapping(VirtualFile)");
            Class<?> pattern = Class.forName("com.intellij.database.util.TreePattern", false, loader);
            Class<?> provider = Class.forName("com.intellij.sql.psi.impl.DataSourceProvider", false, loader);
            // Inherited generic getter erases to Object; concrete default mapping and ALL pin TreePattern.
            missing += checkExact(scopes, "getConfiguredMapping", Object.class, false, virtualFile);
            missing += checkExact(scopes, "getDefaultMapping", pattern, false, virtualFile);
            missing += checkExact(provider, "getDataSources", List.class, false, psiFile);
            missing += checkExact(dataSources, "getProvidedDataSources", List.class, false, psiFile);
            if (scopes.getField("ALL_DATA_SOURCES").getType() != pattern ||
                    pattern.getField("EMPTY").getType() != pattern) {
                System.out.println("MISS scope constants must be TreePattern");
                missing++;
            }
            System.out.println("INFO configured scope getter owner=" + scopes.getMethod("getConfiguredMapping", virtualFile).getDeclaringClass().getName()
                    + " mapping=" + pattern.getName() + " provider=" + provider.getName());
            // T-M19/V178: exercise every candidate-reader owner, not just the mappings entry.
            Class<?> candidate = Class.forName("com.intellij.database.psi.DbDataSource", false, loader);
            Class<?> config = Class.forName("com.intellij.database.model.RawConnectionConfig", false, loader);
            Class<?> language = Class.forName("com.intellij.lang.Language", false, loader);
            missing += checkExact(candidate, "getUniqueId", String.class, false);
            missing += checkExact(candidate, "getName", String.class, false);
            missing += checkExact(candidate, "getQueryLanguage", language, false);
            missing += checkExact(candidate, "isValid", boolean.class, false);
            missing += checkExact(candidate, "getConnectionConfig", config, false);
            missing += checkExact(config, "getUrl", String.class, false);
            System.out.println("INFO O145 connection config owner=" + config.getName());
            Class<?> dbms = Class.forName("com.intellij.database.Dbms", false, loader);
            if (dbms.getField("UNKNOWN").getType() != dbms) {
                System.out.println("MISS Dbms.UNKNOWN field type");
                missing++;
            }
            Class<?> instanceBean = Class.forName("com.intellij.database.DbmsExtension$InstanceBean", false, loader);
            Method factory = instanceBean.getDeclaredMethod("doGetInstance", Class.class, dbms);
            missing += check(factory, "DbmsExtension.InstanceBean.doGetInstance(Class,Dbms)");
            missing += checkClassBytes(loader, instanceBean.getName(), "sql.dialect INSTANCE field reader",
                    "INSTANCE", "getField");
            System.out.println("INFO DM compatibility parent=UNKNOWN (not native DM support)");
            return missing;
        } catch (ClassNotFoundException e) {
            System.out.println("MISS DatabaseTools SQL context API class: " + e.getMessage());
            return 1;
        } catch (Throwable t) {
            System.out.println("MISS DatabaseTools SQL context API: " + t);
            return 1;
        }
    }

    /** O-145 W7：最终实际目标闸门反射面，三代平台任一漂移都必须在发布前变红。 */
    private static int checkExecutionDialectGateApis(ClassLoader loader, boolean databaseToolsPresent) {
        if (!databaseToolsPresent) {
            System.out.println("SKIP DatabaseTools execution dialect gate APIs (bundled plugin absent)");
            return 0;
        }
        try {
            // 243/262 的 DatabaseSession/JdbcConsole 在裸 URLClassLoader 下会解析到发行包未带入的
            // 可选 datagrid 类型，直接 Class#getMethod 会在目标方法检查前 NoClassDefFoundError 假红。
            // 本组改读 classfile UTF-8 常量：方法名与参数/返回类型描述符都在常量池，既核真实发行物，
            // 又不触发无关可选类型链接。
            int missing = 0;
            missing += checkClassBytes(loader, "com.intellij.database.console.session.DatabaseSession",
                    "DatabaseSession.getConnectionPoint()", "getConnectionPoint", "DatabaseConnectionPoint");
            missing += checkClassBytes(loader, "com.intellij.database.dataSource.DatabaseConnectionPoint",
                    "DatabaseConnectionPoint.getDataSource()", "getDataSource", "LocalDataSource");
            missing += checkClassBytes(loader, "com.intellij.database.console.JdbcConsole",
                    "JdbcConsole.getDataSource()", "getDataSource", "LocalDataSource");
            missing += checkClassBytes(loader, "com.intellij.database.dataSource.LocalDataSource",
                    "LocalDataSource.getUniqueId/getUrl", "getUniqueId", "getUrl");
            // getUrl 的声明所有者在三代间漂移（253 只在 LocalDataSource，AbstractDataSource 无该方法）；
            // Abstract 侧只钉稳定身份方法，URL 由上面的 LocalDataSource 项覆盖。
            missing += checkClassBytes(loader, "com.intellij.database.dataSource.AbstractDataSource",
                    "AbstractDataSource.getUniqueId()", "getUniqueId");
            missing += checkClassBytes(loader, "com.intellij.database.util.DbSqlUtilCore",
                    "DbSqlUtilCore.findSqlDialect(LocalDataSource)", "findSqlDialect", "LocalDataSource");
            return missing;
        } catch (Throwable t) {
            System.out.println("MISS DatabaseTools execution dialect gate API: " + t);
            return 1;
        }
    }

    /** O-149/V182：逐一核 SqlLogPanel 当前静态调用的参数及返回类型，不以同名/参数个数冒充兼容。 */
    private static int checkSqlLogTabsApis(ClassLoader loader) {
        try {
            Class<?> factory = Class.forName("com.intellij.ui.tabs.JBTabsFactory", false, loader);
            Class<?> project = Class.forName("com.intellij.openapi.project.Project", false, loader);
            Class<?> disposable = Class.forName("com.intellij.openapi.Disposable", false, loader);
            Class<?> tabs = Class.forName("com.intellij.ui.tabs.JBTabs", false, loader);
            Class<?> presentation = Class.forName("com.intellij.ui.tabs.JBTabsPresentation", false, loader);
            Class<?> info = Class.forName("com.intellij.ui.tabs.TabInfo", false, loader);
            Class<?> impl = Class.forName("com.intellij.ui.tabs.impl.JBTabsImpl", false, loader);
            int missing = 0;
            missing += checkExact(factory, "createTabs", tabs, true, project, disposable);
            missing += checkExact(tabs, "getPresentation", presentation, false);
            missing += checkExact(presentation, "setSingleRow", presentation, false, boolean.class);
            missing += checkExact(presentation, "setSupportsCompression", presentation, false, boolean.class);
            missing += checkExact(presentation, "setTabDraggingEnabled", presentation, false, boolean.class);
            try {
                info.getConstructor(javax.swing.JComponent.class);
                System.out.println("OK   TabInfo(JComponent)");
            } catch (NoSuchMethodException e) {
                System.out.println("MISS TabInfo(JComponent): " + e);
                missing++;
            }
            missing += checkExact(info, "setText", info, false, String.class);
            missing += checkExact(info, "getText", String.class, false);
            missing += checkExact(info, "getComponent", javax.swing.JComponent.class, false);
            missing += checkExact(tabs, "addTab", info, false, info);
            Class<?> listener = Class.forName("com.intellij.ui.tabs.TabsListener", false, loader);
            missing += checkExact(tabs, "addListener", tabs, false, listener, disposable);
            missing += checkExact(tabs, "getComponent", javax.swing.JComponent.class, false);
            missing += checkExact(tabs, "getTabs", List.class, false);
            missing += checkExact(tabs, "getTabCount", int.class, false);
            missing += checkExact(tabs, "getSelectedInfo", info, false);
            Class<?> callback = Class.forName("com.intellij.openapi.util.ActionCallback", false, loader);
            missing += checkExact(tabs, "select", callback, false, info, boolean.class);
            // 结构测试读取生产实例的真实旗位，不以测试自造 presentation 代替。
            missing += checkExact(impl, "isSingleRow", boolean.class, false);
            missing += checkExact(impl, "supportCompression", boolean.class, false);
            missing += checkExact(impl, "getTabLabel", Class.forName("com.intellij.ui.tabs.impl.TabLabel", false, loader), false, info);
            return missing;
        } catch (Throwable t) {
            System.out.println("MISS SqlLogPanel JBTabs API types: " + t);
            return 1;
        }
    }

    /** UI015：自建预览专用菜单；243 已实证，三代按精确参数/返回值/实例方法登记。 */
    public static int checkPreviewEditorPopupApis(ClassLoader loader) {
        try {
            return checkPreviewEditorPopupSignatures(
                    Class.forName("com.intellij.openapi.editor.ex.EditorEx", false, loader));
        } catch (Throwable t) {
            System.out.println("MISS PreviewEditor popup API: " + t);
            return 1;
        }
    }

    public static int checkPreviewEditorPopupSignatures(Class<?> editor) {
        return checkExact(editor, "setContextMenuGroupId", void.class, false, String.class)
                + checkExact(editor, "getContextMenuGroupId", String.class, false);
    }

    /** UI-007：AgentAttachTarget 在 handler 上反射读取命令；公共实例零参 String，不能仅按名字匹配。 */
    public static int checkAttachTargetApis(ClassLoader loader) {
        try {
            Class<?> handler = Class.forName("com.intellij.execution.process.BaseProcessHandler", false, loader);
            return checkAttachTargetCommandLine(handler);
        } catch (Throwable t) {
            System.out.println("MISS AgentAttachTarget BaseProcessHandler API: " + t);
            return 1;
        }
    }

    /** UI011：设置页复用注册器的只读 McpToolset 探测；缺席合法，存在时必须仍是接口。
     *  不绑定版本号；不初始化类、不读 Companion、不访问服务，更不注册工具或启动服务器。
     *  253/262 本地 javap 均证实 interface；243 发行物缺席。
     */
    private static int checkMcpToolsetCapability(ClassLoader loader) {
        String name = "com.intellij.mcpserver.McpToolset";
        if (loader.getResource(name.replace('.', '/') + ".class") == null) {
            System.out.println("INFO MCP toolset capability absent (settings must not report ready)");
            return 0;
        }
        try {
            Class<?> toolset = Class.forName(name, false, loader);
            if (!toolset.isInterface()) {
                System.out.println("MISS MCP toolset capability type is no longer an interface");
                return 1;
            }
            System.out.println("OK   MCP toolset capability present (not registration or server status)");
            return 0;
        } catch (Throwable t) {
            System.out.println("MISS MCP toolset capability linkage: " + t);
            return 1;
        }
    }

    /** 与真实发行版登记共用的精确签名核验，供形态漂移负例调用。 */
    public static int checkAttachTargetCommandLine(Class<?> handler) {
        return checkExact(handler, "getCommandLine", String.class, false);
    }

    /**
     * UI025：ExternalAnnotator 调度面 + 标注落点 API 的跨版本签名核验。
     *
     * 核验四段（243 app-client.jar / 253 app.jar 双版 javap 实测同形）：
     *  - `collectInformation(PsiFile)` / `collectInformation(PsiFile, Editor, boolean)`；
     *  - `doAnnotate(Object)`（泛型擦除）；
     *  - `apply(PsiFile, Object, AnnotationHolder)`；
     *  - `AnnotationHolder.newAnnotation(HighlightSeverity, String)` → `AnnotationBuilder.range(TextRange)`
     *    → `create()`，以及 `HighlightSeverity.ERROR` 静态字段。
     * 任一漂移 → 注册后的 annotator 不会被平台正确调度或标不到红，本项即红。
     */
    public static int checkExternalAnnotatorApis(ClassLoader loader) {
        int missing = 0;
        try {
            Class<?> annotator = Class.forName("com.intellij.lang.annotation.ExternalAnnotator", false, loader);
            if (!java.lang.reflect.Modifier.isAbstract(annotator.getModifiers())) {
                System.out.println("MISS ExternalAnnotator is no longer abstract");
                missing++;
            }
            Class<?> psiFile = Class.forName("com.intellij.psi.PsiFile", false, loader);
            Class<?> editor = Class.forName("com.intellij.openapi.editor.Editor", false, loader);
            Class<?> holder = Class.forName("com.intellij.lang.annotation.AnnotationHolder", false, loader);
            Class<?> builder = Class.forName("com.intellij.lang.annotation.AnnotationBuilder", false, loader);
            Class<?> severity = Class.forName("com.intellij.lang.annotation.HighlightSeverity", false, loader);
            Class<?> textRange = Class.forName("com.intellij.openapi.util.TextRange", false, loader);
            missing += checkExact(annotator, "collectInformation", Object.class, false, psiFile);
            missing += checkExact(annotator, "collectInformation", Object.class, false, psiFile, editor, boolean.class);
            missing += checkExact(annotator, "doAnnotate", Object.class, false, Object.class);
            missing += checkExact(annotator, "apply", void.class, false, psiFile, Object.class, holder);
            missing += checkExact(holder, "newAnnotation", builder, false, severity, String.class);
            missing += checkExact(builder, "range", builder, false, textRange);
            missing += checkExact(builder, "create", void.class, false);
            try {
                java.lang.reflect.Field error = severity.getField("ERROR");
                if (!java.lang.reflect.Modifier.isStatic(error.getModifiers()) || error.getType() != severity) {
                    System.out.println("MISS HighlightSeverity.ERROR static field mismatch: " + error);
                    missing++;
                } else {
                    System.out.println("OK   HighlightSeverity.ERROR static field");
                }
            } catch (Throwable t) {
                System.out.println("MISS HighlightSeverity.ERROR: " + t);
                missing++;
            }
        } catch (Throwable t) {
            System.out.println("MISS ExternalAnnotator API: " + t);
            missing++;
        }
        return missing;
    }

    private static int checkExact(Class<?> owner, String name, Class<?> returns, boolean isStatic, Class<?>... args) {
        String label = owner.getSimpleName() + "." + name + java.util.Arrays.toString(args);
        try {
            Method method = owner.getMethod(name, args);
            if (method.getReturnType() != returns || java.lang.reflect.Modifier.isStatic(method.getModifiers()) != isStatic) {
                System.out.println("MISS " + label + " return/static mismatch: " + method);
                return 1;
            }
            return check(method, label);
        } catch (Throwable t) {
            System.out.println("MISS " + label + ": " + t);
            return 1;
        }
    }

    private static int checkClassBytes(ClassLoader loader, String className, String label, String... tokens) {
        String resource = className.replace('.', '/') + ".class";
        try (java.io.InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                System.out.println("MISS " + label + " class resource: " + resource);
                return 1;
            }
            byte[] bytes = in.readAllBytes();
            String raw = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            for (String token : tokens) {
                if (!raw.contains(token)) {
                    System.out.println("MISS " + label + " token=" + token);
                    return 1;
                }
            }
            System.out.println("OK   " + label + " -> classfile tokens " + java.util.Arrays.toString(tokens));
            return 0;
        } catch (Throwable t) {
            System.out.println("MISS " + label + " classfile read failed: " + t);
            return 1;
        }
    }

    private static int check(Method m, String label) {
        if (m == null) {
            System.out.println("MISS " + label);
            return 1;
        }
        System.out.println("OK   " + label + " -> " + m.getName() + m.toGenericString().substring(m.toGenericString().indexOf('(')));
        return 0;
    }

    private static void collectJars(File dir, List<URL> urls) throws java.net.MalformedURLException {
        File[] jars = dir.listFiles((d, n) -> n.endsWith(".jar"));
        if (jars != null) {
            for (File j : jars) {
                urls.add(j.toURI().toURL());
            }
        }
    }

    /** 与生产 DynamicInstallSupport.resolveMethodByFirstParam 同款逻辑：首参互为可赋值，最少参数优先。 */
    private static Method byFirstParam(Class<?> clazz, String name, Class<?> firstParamBase) {
        Method best = null;
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length == 0) {
                continue;
            }
            Class<?> first = m.getParameterTypes()[0];
            if (!firstParamBase.isAssignableFrom(first) && !first.isAssignableFrom(firstParamBase)) {
                continue;
            }
            if (best == null || m.getParameterTypes().length < best.getParameterTypes().length) {
                best = m;
            }
        }
        return best;
    }

    private static Method byNameAndCount(Class<?> clazz, String name, int count) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == count) {
                return m;
            }
        }
        return null;
    }
}
