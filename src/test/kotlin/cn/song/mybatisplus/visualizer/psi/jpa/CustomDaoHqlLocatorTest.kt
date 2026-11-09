package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * CustomDaoHqlLocator 单元测试。
 *
 * 验证自定义 DAO 方法中的 HQL 字符串识别能力（三重启发检测）。
 */
class CustomDaoHqlLocatorTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // 正向用例：应识别为自定义 DAO HQL
    // ------------------------------------------------------------------

    fun testCustomDao_nameValuePairs() {
        myFixture.configureByText(
            "WmsZoneService.java",
            """
            public class WmsZoneService {
                public Long countAutoZone(CommonDao commonDao, java.util.List<Long> locIdList) {
                    return (Long) commonDao.findByQueryUniqueResult(
                        "select count(1) from WmsZone z " +
                        "left join WmsLocation loc on loc.zoneId = z.id " +
                        "where loc.id in (:locIdList) and z.beAutoZone = true",
                        "locIdList", locIdList);
                }
            }
            interface CommonDao {
                Object findByQueryUniqueResult(String hql, Object... params);
            }
            """.trimIndent(),
        )
        val method = findMethod("countAutoZone")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("commonDao.findByQueryUniqueResult 应被识别", info)
        assertEquals("WmsZone", info!!.entityClassName)
        assertTrue("HQL 应含 from WmsZone", info.hql.contains("from WmsZone"))
        assertTrue("参数列表不应为空", info.parameters.isNotEmpty())
        assertEquals("locIdList", info.parameters[0].paramName)
        assertEquals(ParamSource.NAME_VALUE_PAIRS, info.paramSource)
    }

    fun testCustomDao_mapParam() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public java.util.List<UserEntity> findUsers(UserDao userDao, java.util.Map<String,Object> params) {
                    return userDao.findByQuery("from UserEntity u where u.status = :status", params);
                }
            }
            interface UserDao {
                java.util.List findByQuery(String hql, java.util.Map params);
            }
            """.trimIndent(),
        )
        val method = findMethod("findUsers")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("userDao.findByQuery (Map 参数) 应被识别", info)
        assertEquals("UserEntity", info!!.entityClassName)
        assertEquals(ParamSource.MAP, info.paramSource)
    }

    fun testCustomDao_noParam() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public java.util.List<UserEntity> findAll(BaseDao baseDao) {
                    return baseDao.findByQuery("from UserEntity");
                }
            }
            interface BaseDao {
                java.util.List findByQuery(String hql);
            }
            """.trimIndent(),
        )
        val method = findMethod("findAll")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("baseDao.findByQuery (无参数) 应被识别", info)
        assertEquals("UserEntity", info!!.entityClassName)
        assertEquals(ParamSource.NONE, info.paramSource)
    }

    fun testCustomDao_updateHql() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public int updateStatus(CommonDao commonDao, int oldStatus, int newStatus) {
                    return commonDao.execute("update UserEntity set status = :newStatus where status = :oldStatus", "newStatus", newStatus, "oldStatus", oldStatus);
                }
            }
            interface CommonDao {
                int execute(String hql, Object... params);
            }
            """.trimIndent(),
        )
        val method = findMethod("updateStatus")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("commonDao.execute (UPDATE) 应被识别", info)
        assertTrue("HQL 应含 update: [" + info!!.hql + "]", info.hql.contains("update", ignoreCase = true))
        assertEquals("UserEntity", info.entityClassName)
    }

    fun testCustomDao_deleteHql() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public int deleteByStatus(CommonDao commonDao, int status) {
                    return commonDao.execute("delete from UserEntity where status = :status", "status", status);
                }
            }
            interface CommonDao {
                int execute(String hql, Object... params);
            }
            """.trimIndent(),
        )
        val method = findMethod("deleteByStatus")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("commonDao.execute (DELETE) 应被识别", info)
        assertTrue("HQL 应含 delete", info!!.hql.contains("delete", ignoreCase = true))
    }

    fun testCustomDao_joinQuery() {
        myFixture.configureByText(
            "WmsZoneService.java",
            """
            public class WmsZoneService {
                public Long countAutoZone(CommonDao commonDao, java.util.List<Long> locIdList) {
                    return (Long) commonDao.findByQueryUniqueResult(
                        "select count(1) from WmsZone z " +
                        "left join WmsLocation loc on loc.zoneId = z.id " +
                        "where loc.id in (:locIdList) and z.beAutoZone = true",
                        "locIdList", locIdList);
                }
            }
            interface CommonDao {
                Object findByQueryUniqueResult(String hql, Object... params);
            }
            """.trimIndent(),
        )
        val method = findMethod("countAutoZone")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("JOIN HQL 应被识别", info)
        assertEquals("WmsZone", info!!.entityClassName)
        assertTrue("HQL 应含 left join", info.hql.contains("left join", ignoreCase = true))
    }

    fun testCustomDao_mapperSuffix() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public java.util.List<UserEntity> findUsers(UserMapper userMapper) {
                    return userMapper.findByQuery("from UserEntity u where u.name = :name", "name", "test");
                }
            }
            interface UserMapper {
                java.util.List findByQuery(String hql, Object... params);
            }
            """.trimIndent(),
        )
        val method = findMethod("findUsers")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("userMapper.findByQuery 应被识别（qualifier 匹配 *Mapper）", info)
        assertEquals("UserEntity", info!!.entityClassName)
    }

    fun testCustomDao_repositorySuffix() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public java.util.List<UserEntity> findUsers(UserRepository userRepository) {
                    return userRepository.findByQuery("from UserEntity");
                }
            }
            interface UserRepository {
                java.util.List findByQuery(String hql);
            }
            """.trimIndent(),
        )
        val method = findMethod("findUsers")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("userRepository.findByQuery 应被识别（qualifier 匹配 *Repository）", info)
    }

    // ------------------------------------------------------------------
    // 拼接变量初始化式（2026-09-03 用户反馈：String hql = "select" + " ..." 无预览图标）
    // ------------------------------------------------------------------

    fun testCustomDao_variableConcatInitializer() {
        myFixture.configureByText(
            "WmsZoneService.java",
            """
            public class WmsZoneService {
                public Long countAutoZone(CommonDao commonDao, java.util.List<Long> locIdList) {
                    String hql = "select count(case when z.beAutoZone = true then 0 end) " +
                            "from WmsZone z " +
                            "where z.id in (:locIdList)";
                    return (Long) commonDao.findByQueryUniqueResult(hql, "locIdList", locIdList);
                }
            }
            interface CommonDao {
                Object findByQueryUniqueResult(String hql, Object... params);
            }
            """.trimIndent(),
        )
        val method = findMethod("countAutoZone")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("拼接变量初始化式应被识别（内容启发须递归展开）", info)
        assertEquals("WmsZone", info!!.entityClassName)
        assertTrue("HQL 应展开拼接结果", info.hql.contains("from WmsZone z"))
        assertEquals("locIdList", info.parameters[0].paramName)
        assertEquals(ParamSource.NAME_VALUE_PAIRS, info.paramSource)
    }

    fun testCustomDao_nameValueArrays() {
        myFixture.configureByText(
            "BookingService.java",
            """
            public class BookingService {
                public Object[] getHeadData(CommonDao commonDao, String warehouseId, java.util.Date startTime, java.util.Date endTime) {
                    String hql = "select count(case when booking.status = 'F' then 0 end) " +
                            "from WmsDockBooking booking " +
                            "where booking.warehouseId = :warehouseId " +
                            "and booking.updateInfo.createdTime >= :startTime " +
                            "and booking.updateInfo.createdTime < :endTime";
                    return (Object[]) commonDao.findByQueryUniqueResult(hql,
                            new String[]{"warehouseId", "startTime", "endTime"},
                            new Object[]{warehouseId, startTime, endTime});
                }
            }
            interface CommonDao {
                Object findByQueryUniqueResult(String hql, String[] paramNames, Object[] paramValues);
            }
            """.trimIndent(),
        )
        val method = findMethod("getHeadData")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("拼接变量 + 名字数组/值数组双数组应被识别", info)
        assertEquals("WmsDockBooking", info!!.entityClassName)
        assertEquals(ParamSource.NAME_VALUE_ARRAYS, info.paramSource)
        assertEquals(3, info.parameters.size)
        assertEquals("warehouseId", info.parameters[0].paramName)
        assertEquals("warehouseId", info.parameters[0].valueExpr)
        assertEquals("startTime", info.parameters[1].paramName)
        assertEquals("startTime", info.parameters[1].valueExpr)
        assertEquals("endTime", info.parameters[2].paramName)
        assertEquals("endTime", info.parameters[2].valueExpr)
    }

    // ------------------------------------------------------------------
    // 负向用例：不应识别为非 HQL
    // ------------------------------------------------------------------

    fun testNotHql_plainString() {
        myFixture.configureByText(
            "LogService.java",
            """
            public class LogService {
                public void logMessage(Logger logger) {
                    logger.info("select something from somewhere");
                }
            }
            interface Logger {
                void info(String msg);
            }
            """.trimIndent(),
        )
        val method = findMethod("logMessage")
        val info = CustomDaoHqlLocator.locate(method)
        assertNull("logger.info 不应被识别（qualifier 不匹配 DAO 模式）", info)
    }

    fun testNotHql_noEntityName() {
        myFixture.configureByText(
            "CommonService.java",
            """
            public class CommonService {
                public void doSomething(CommonDao commonDao) {
                    commonDao.execute("print 'hello'");
                }
            }
            interface CommonDao {
                void execute(String cmd);
            }
            """.trimIndent(),
        )
        val method = findMethod("doSomething")
        val info = CustomDaoHqlLocator.locate(method)
        assertNull("commonDao.execute(\"print 'hello'\") 不应被识别（内容不匹配 HQL 模式）", info)
    }

    fun testNotHql_variableConcatPlainString() {
        myFixture.configureByText(
            "CommonService.java",
            """
            public class CommonService {
                public void doSomething(CommonDao commonDao) {
                    String msg = "hello" + " world";
                    commonDao.execute(msg);
                }
            }
            interface CommonDao {
                void execute(String cmd);
            }
            """.trimIndent(),
        )
        val method = findMethod("doSomething")
        val info = CustomDaoHqlLocator.locate(method)
        assertNull("拼接变量但内容非 HQL 不应被识别（内容启发保持守门）", info)
    }

    // ------------------------------------------------------------------
    // T-J12：HibernateTemplate 家族方法名扩展（bulkUpdate / findByNamedQuery）
    // ------------------------------------------------------------------

    fun testTJ12_hibernateTemplateBulkUpdate() {
        // bulkUpdate 是 HibernateTemplate 唯一 DML 入口，首参 UPDATE HQL 字符串走现有 HQL 路径
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public int deactivateUsers() {
                    return getHibernateTemplate().bulkUpdate(
                        "update UserEntity set status = :status where lastLogin < :cutoff",
                        "status", 0, "cutoff", "2020-01-01");
                }
                HibernateTemplate getHibernateTemplate() { return null; }
            }
            interface HibernateTemplate {
                int bulkUpdate(String hql, Object... values);
            }
            class UserEntity {
                private Integer status;
            }
            """.trimIndent(),
        )
        val method = findMethod("deactivateUsers")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("bulkUpdate（HibernateTemplate DML）应被识别（T-J12）", info)
        assertEquals(DaoQueryKind.HQL, info!!.kind)
        assertTrue("HQL 应为 UPDATE 语句字面量: [" + info.hql + "]", info.hql.startsWith("update UserEntity"))
        assertEquals(ParamSource.NAME_VALUE_PAIRS, info.paramSource)
    }

    fun testTJ12_hibernateTemplateFindByNamedQuery() {
        // findByNamedQuery 首参是查询名字符串而非 HQL：kind=NAMED_QUERY，hql 存查询名，entityClassName 为 null
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public java.util.List findByStatus(int status) {
                    return getHibernateTemplate().findByNamedQuery("UserEntity.findByStatus", "status", status);
                }
                HibernateTemplate getHibernateTemplate() { return null; }
            }
            interface HibernateTemplate {
                java.util.List findByNamedQuery(String queryName, Object... values);
            }
            class UserEntity {
                private Integer status;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByStatus")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("findByNamedQuery 应被识别（T-J12）", info)
        assertEquals("类别应标记为 NAMED_QUERY（SqlPreviewService 接线后续做）", DaoQueryKind.NAMED_QUERY, info!!.kind)
        assertEquals("hql 字段应存查询名", "UserEntity.findByStatus", info.hql)
        assertNull("查询名形态不应提取实体类名", info.entityClassName)
    }

    fun testTJ12_qbcMethodsRegisteredButNotClaimed() {
        // findByCriteria/executeFind 只登记到名单（QBC 形态占位），无 SQL 字符串不认领、不误抛
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public java.util.List byCriteria() {
                    return getHibernateTemplate().findByCriteria(null);
                }
                public java.util.List executeFind() {
                    return getHibernateTemplate().executeFind(null);
                }
                HibernateTemplate getHibernateTemplate() { return null; }
            }
            interface HibernateTemplate {
                java.util.List findByCriteria(Object criteria);
                java.util.List executeFind(Object action);
            }
            class UserEntity {}
            """.trimIndent(),
        )
        assertNull("findByCriteria 不应认领（无 SQL 字符串）", CustomDaoHqlLocator.locate(findMethod("byCriteria")))
        assertNull("executeFind 不应认领（QBC 形态占位）", CustomDaoHqlLocator.locate(findMethod("executeFind")))
        // 名单登记可查：findByCriteria 按 T-J21 归 ENTITY_OP（两名单无交集），executeFind 仍为 QBC
        assertEquals(DaoQueryKind.ENTITY_OP, CustomDaoHqlLocator.classifyHibernateTemplateMethod("findByCriteria"))
        assertEquals(DaoQueryKind.QBC, CustomDaoHqlLocator.classifyHibernateTemplateMethod("executeFind"))
    }

    // ------------------------------------------------------------------
    // T-J21：HibernateTemplate 实体操作名单（仅登记，不认领、不误判 HQL）
    // ------------------------------------------------------------------

    fun testTJ21_entityOpMethodsNotClaimed() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public void allEntityOps(UserEntity user) {
                    HibernateTemplate t = getHibernateTemplate();
                    t.save(user);
                    t.saveOrUpdate(user);
                    t.update(user);
                    t.delete(user);
                    t.deleteAll(java.util.Arrays.asList(user));
                    t.get(UserEntity.class, 1L);
                    t.load(UserEntity.class, 1L);
                    t.loadAll(UserEntity.class);
                    t.refresh(user);
                    t.merge(user);
                    t.persist(user);
                    t.replicate(user, null);
                    t.lock(user, null);
                    t.findByExample(user);
                    t.findByCriteria(null);
                }
                HibernateTemplate getHibernateTemplate() { return null; }
            }
            interface HibernateTemplate {
                void save(Object entity);
                void saveOrUpdate(Object entity);
                void update(Object entity);
                void delete(Object entity);
                void deleteAll(java.util.Collection entities);
                Object get(Class clazz, Long id);
                Object load(Class clazz, Long id);
                java.util.List loadAll(Class clazz);
                void refresh(Object entity);
                Object merge(Object entity);
                void persist(Object entity);
                void replicate(Object entity, Object mode);
                void lock(Object entity, Object lockMode);
                java.util.List findByExample(Object entity);
                java.util.List findByCriteria(Object criteria);
            }
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("allEntityOps")
        assertNull("实体操作方法（save/update/delete/...）无 SQL 字符串，不应认领（T-J21）", CustomDaoHqlLocator.locate(method))
    }

    fun testTJ21_entityOpClassifierMappings() {
        // 名单登记（SqlPreviewService 批量派发接线由 T-J1 里程碑做）
        assertEquals(DaoQueryKind.ENTITY_OP, CustomDaoHqlLocator.classifyHibernateTemplateMethod("save"))
        assertEquals(DaoQueryKind.ENTITY_OP, CustomDaoHqlLocator.classifyHibernateTemplateMethod("saveOrUpdate"))
        assertEquals(DaoQueryKind.ENTITY_OP, CustomDaoHqlLocator.classifyHibernateTemplateMethod("update"))
        assertEquals(DaoQueryKind.ENTITY_OP, CustomDaoHqlLocator.classifyHibernateTemplateMethod("delete"))
        assertEquals(DaoQueryKind.ENTITY_OP, CustomDaoHqlLocator.classifyHibernateTemplateMethod("deleteAll"))
        assertEquals(DaoQueryKind.ENTITY_OP, CustomDaoHqlLocator.classifyHibernateTemplateMethod("get"))
        assertEquals(DaoQueryKind.ENTITY_OP, CustomDaoHqlLocator.classifyHibernateTemplateMethod("load"))
        assertEquals(DaoQueryKind.ENTITY_OP, CustomDaoHqlLocator.classifyHibernateTemplateMethod("loadAll"))
        assertEquals(DaoQueryKind.ENTITY_OP, CustomDaoHqlLocator.classifyHibernateTemplateMethod("refresh"))
        assertEquals(DaoQueryKind.ENTITY_OP, CustomDaoHqlLocator.classifyHibernateTemplateMethod("merge"))
        assertEquals(DaoQueryKind.ENTITY_OP, CustomDaoHqlLocator.classifyHibernateTemplateMethod("persist"))
        assertEquals(DaoQueryKind.ENTITY_OP, CustomDaoHqlLocator.classifyHibernateTemplateMethod("replicate"))
        assertEquals(DaoQueryKind.ENTITY_OP, CustomDaoHqlLocator.classifyHibernateTemplateMethod("lock"))
        assertEquals(DaoQueryKind.ENTITY_OP, CustomDaoHqlLocator.classifyHibernateTemplateMethod("findByExample"))
        assertEquals(DaoQueryKind.ENTITY_OP, CustomDaoHqlLocator.classifyHibernateTemplateMethod("findByCriteria"))
        // 名单外方法不归类
        assertNull(CustomDaoHqlLocator.classifyHibernateTemplateMethod("execute"))
        assertNull(CustomDaoHqlLocator.classifyHibernateTemplateMethod(null))
    }

    fun testTJ12_classifierMappings() {
        assertEquals(DaoQueryKind.HQL, CustomDaoHqlLocator.classifyHibernateTemplateMethod("find"))
        assertEquals(DaoQueryKind.HQL, CustomDaoHqlLocator.classifyHibernateTemplateMethod("bulkUpdate"))
        assertEquals(DaoQueryKind.NAMED_QUERY, CustomDaoHqlLocator.classifyHibernateTemplateMethod("findByNamedQuery"))
        assertEquals(DaoQueryKind.NAMED_QUERY, CustomDaoHqlLocator.classifyHibernateTemplateMethod("findByNamedQueryAndNamedParam"))
        assertEquals(DaoQueryKind.NAMED_QUERY, CustomDaoHqlLocator.classifyHibernateTemplateMethod("findByNamedQueryAndValueBean"))
    }

    // ------------------------------------------------------------------
    // T-HqlBufConcat：StringBuffer/StringBuilder 拼接串（2026-10-13 用户报障）
    // ------------------------------------------------------------------

    fun testHqlBufConcat_stringBufferToString() {
        myFixture.configureByText(
            "WmsLocationService.java",
            """
            public class WmsLocationService {
                public java.util.List findLocations(WmsLocationDao wmsLocationDao) {
                    StringBuffer sb = new StringBuffer();
                    sb.append("SELECT loc.id AS locationId ");
                    sb.append("FROM WmsLocation loc ");
                    sb.append("WHERE 1=1 ");
                    sb.append("AND loc.status = 'E' ");
                    sb.append("ORDER BY loc.putawaySequence");
                    return wmsLocationDao.findByQueryMaxNum(sb.toString(), new String[]{}, new Object[]{}, 0, 10);
                }
            }
            interface WmsLocationDao {
                java.util.List findByQueryMaxNum(String hql, String[] names, Object[] values, int start, int max);
            }
            """.trimIndent(),
        )
        val info = CustomDaoHqlLocator.locate(findMethod("findLocations"))
        assertNotNull("StringBuffer + append + sb.toString() 应被识别", info)
        val hql = info!!.hql
        assertEquals("WmsLocation", info.entityClassName)
        assertTrue("应含 SELECT 段: $hql", hql.contains("SELECT loc.id AS locationId"))
        assertTrue("应含 FROM 段: $hql", hql.contains("FROM WmsLocation loc"))
        assertTrue("应含 ORDER BY 段: $hql", hql.contains("ORDER BY loc.putawaySequence"))
        assertTrue("片段应按书写序拼接", hql.indexOf("WHERE 1=1") < hql.indexOf("ORDER BY"))
        assertTrue("全字面量应判为静态", info.isLiteralHql)
    }

    fun testHqlBufConcat_stringBuilderRuntimeValueDegraded() {
        myFixture.configureByText(
            "WmsQueryService.java",
            """
            public class WmsQueryService {
                public java.util.List findLocations(CommonDao commonDao, java.util.Map<String,Object> param) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("SELECT loc.id FROM WmsLocation loc WHERE 1=1 ");
                    sb.append("and loc.warehouseId = ").append(param.get("warehouseId")).append(" ");
                    sb.append("AND loc.status = 'E'");
                    return commonDao.findByQueryMaxNum(sb.toString(), new String[]{}, new Object[]{}, 0, 10);
                }
            }
            interface CommonDao {
                java.util.List findByQueryMaxNum(String hql, String[] names, Object[] values, int start, int max);
            }
            """.trimIndent(),
        )
        val info = CustomDaoHqlLocator.locate(findMethod("findLocations"))
        assertNotNull("StringBuilder + 链式 append + 运行时值 应被识别", info)
        val hql = info!!.hql
        assertTrue("应含 FROM 段: $hql", hql.contains("FROM WmsLocation"))
        assertFalse("含运行时值应判为非静态", info.isLiteralHql)
        // 178 轮：R1 自洽占位注释（T-RuntimeSlotFill）把标记从 `/*运行时值*/` 扩成
        // `/* 运行时值: <表达式> · rtN */`——判据放宽为「含运行时值标记」。
        assertTrue("运行时值应留占位标记: $hql", hql.contains("运行时值"))
    }

    fun testHqlBufConcat_viaStringVariable() {
        myFixture.configureByText(
            "WmsQueryService2.java",
            """
            public class WmsQueryService2 {
                public java.util.List findLocations(CommonDao commonDao) {
                    StringBuffer sb = new StringBuffer();
                    sb.append("SELECT loc.id FROM WmsLocation loc ");
                    sb.append("WHERE loc.status = 'E'");
                    String hql = sb.toString();
                    return commonDao.findByQueryMaxNum(hql, new String[]{}, new Object[]{}, 0, 10);
                }
            }
            interface CommonDao {
                java.util.List findByQueryMaxNum(String hql, String[] names, Object[] values, int start, int max);
            }
            """.trimIndent(),
        )
        val info = CustomDaoHqlLocator.locate(findMethod("findLocations"))
        assertNotNull("String hql = sb.toString() 经变量传入应被识别", info)
        assertTrue("应含 FROM 段: ${info!!.hql}", info.hql.contains("FROM WmsLocation"))
    }

    fun testHqlBufConcat_nonHqlContentNotClaimed() {
        myFixture.configureByText(
            "LogService.java",
            """
            public class LogService {
                public java.util.List doLog(CommonDao commonDao) {
                    StringBuffer sb = new StringBuffer();
                    sb.append("hello ");
                    sb.append("world");
                    return commonDao.findByQueryMaxNum(sb.toString(), new String[]{}, new Object[]{}, 0, 10);
                }
            }
            interface CommonDao {
                java.util.List findByQueryMaxNum(String hql, String[] names, Object[] values, int start, int max);
            }
            """.trimIndent(),
        )
        assertNull(
            "非 HQL 内容的拼接串不应被认领（内容启发守门）",
            CustomDaoHqlLocator.locate(findMethod("doLog")),
        )
    }

    fun testHqlBufConcat_wrapperChainNotClaimed() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public java.util.List list(UserMapper userMapper) {
                    return userMapper.selectList(
                        new QueryWrapper().exists("select 1 from t_order where x = 1"));
                }
            }
            interface UserMapper { java.util.List selectList(Object wrapper); }
            class QueryWrapper {
                QueryWrapper exists(String sql) { return this; }
            }
            """.trimIndent(),
        )
        assertNull(
            "wrapper 链（内嵌 SQL 串）不应被认领为自定义 DAO HQL（T-GutterDupFreeze 反向守卫）",
            CustomDaoHqlLocator.locate(findMethod("list")),
        )
    }

    // ------------------------------------------------------------------
    // T-HqlHelperReturn：首参是「源码可见方法返回的字符串」
    // ------------------------------------------------------------------

    fun testHqlHelperReturn_methodReturningHql() {
        myFixture.configureByText(
            "WmsQueryService.java",
            """
            public class WmsQueryService {
                public java.util.List findLocations(CommonDao commonDao) {
                    return commonDao.findByQueryMaxNum(buildHql(), new String[]{}, new Object[]{}, 0, 10);
                }

                private String buildHql() {
                    return "from WmsLocation loc " + "where loc.status = 'E'";
                }
            }
            interface CommonDao {
                java.util.List findByQueryMaxNum(String hql, String[] names, Object[] values, int start, int max);
            }
            """.trimIndent(),
        )
        val info = CustomDaoHqlLocator.locate(findMethod("findLocations"))
        assertNotNull("首参为方法调用（源码可见、返回 String）应被识别", info)
        assertTrue("应聚合出被调方法的 return 内容: ${info!!.hql}", info.hql.contains("from WmsLocation loc"))
        assertEquals("WmsLocation", info.entityClassName)
    }

    fun testHqlHelperReturn_multiBranchInconsistentNotClaimed() {
        myFixture.configureByText(
            "WmsQueryService2.java",
            """
            public class WmsQueryService2 {
                public java.util.List findLocations(CommonDao commonDao, boolean flag) {
                    return commonDao.findByQueryMaxNum(buildHql(flag), new String[]{}, new Object[]{}, 0, 10);
                }

                private String buildHql(boolean flag) {
                    if (flag) {
                        return "from WmsLocation loc where loc.status = 'E'";
                    }
                    return "select count(1) from WmsZone z";
                }
            }
            interface CommonDao {
                java.util.List findByQueryMaxNum(String hql, String[] names, Object[] values, int start, int max);
            }
            """.trimIndent(),
        )
        assertNull(
            "多分支返回内容不一致 → 不猜哪条分支（宁缺不错），不认领",
            CustomDaoHqlLocator.locate(findMethod("findLocations")),
        )
    }

    fun testHqlHelperReturn_nonStringReturnNotClaimed() {
        myFixture.configureByText(
            "WrapperQueryService.java",
            """
            public class WrapperQueryService {
                public java.util.List findLocations(CommonDao commonDao) {
                    return commonDao.findByQueryMaxNum(buildWrapper(), new String[]{}, new Object[]{}, 0, 10);
                }

                private Object buildWrapper() {
                    return new Object();
                }
            }
            interface CommonDao {
                java.util.List findByQueryMaxNum(String hql, String[] names, Object[] values, int start, int max);
            }
            """.trimIndent(),
        )
        assertNull(
            "被调方法返回非 String → 不认领（防 wrapper 链误判回归）",
            CustomDaoHqlLocator.locate(findMethod("findLocations")),
        )
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private fun findMethod(methodName: String): PsiMethod {
        val methods = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethod::class.java)
        // 优先返回有方法体的方法（类方法），避免接口方法声明（无 body 导致 locate 返回 null）
        return methods.firstOrNull { it.name == methodName && it.body != null }
            ?: methods.firstOrNull { it.name == methodName }
            ?: error("测试文件缺少方法: $methodName")
    }
}
