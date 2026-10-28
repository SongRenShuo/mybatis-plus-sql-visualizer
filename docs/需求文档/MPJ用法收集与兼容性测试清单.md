# MyBatis-Plus 与 MPJ（MyBatis-Plus-Join）用法收集与兼容性测试清单

> **文档用途**：为「MyBatis-Plus SQL Visualizer」IDEA 插件提供一套**完整的 MyBatis-Plus / MyBatis-Plus-Join 用法语料**（跨文件去重、全量复核），用于插件兼容性测试，确保插件**完全支持真实项目使用的全部语法形态**。
>
> 插件核心目标功能编号（F-xx）需覆盖本清单每一种模式：F-01 LambdaQueryWrapper、F-09 LambdaUpdateWrapper、F-10 LambdaDeleteWrapper、F-11 MPJLambdaWrapper 联表、F-12 MPJQueryWrapper 字符串联表、F-13 表名/列名解析、F-02 参数替换、F-03 SQL 格式化、F-15 批量操作、F-17 字符串版 Wrapper、F-18 apply/last 透传、F-14 多数据库方言。
>
> **覆盖范围（2026-08-13 全量复核）**：MPJ 61 个源文件（125 处构造）；原生 `LambdaQueryWrapper` 92 个文件；`LambdaUpdateWrapper` 36 个文件；`UpdateWrapper`（字符串版）7 个文件；`lambdaQuery()` 链 35 个文件；Mapper XML 20 个（69 条语句）；实体类 108 个（注解盘点）；MP 基础设施配置 4 个文件。
>
> **脱敏说明**：示例代码中的实体类名、方法名、常量名均为**通用占位名**（如 `XxxEntity`、`XxxVO`、`dto.getX()`），仅保留语法结构；位置引用与附录保留**真实类名**（便于在真实工程中定位做插件验证）；表名/列名在 XML 章节统一占位（`xxx_table`/`xxx_col`）；业务数据字面量（单号、金额、手机号等）一律占位。与任何外部业务无关。
>
> 配套文档：「MyBatis-Plus SQL Visualizer」插件的《README》与《功能概览》（插件仓库内）。

---

## 1. 环境与依赖版本

| 依赖 | 版本 | 说明 |
|------|------|------|
| `mybatis-plus-spring-boot3-starter` | **3.5.15** | MyBatis-Plus 核心（`com.baomidou`） |
| `mybatis-plus-jsqlparser` | **3.5.15** | 仅作为依赖引入；Java 代码中无 JSqlParser 扩展 API 使用 |
| `mybatis-plus-join-boot-starter` | **1.5.6** | MPJ 核心（`com.github.yulichang`） |
| mybatis | 3.5.19（传递依赖） | MyBatis 底层 |
| 数据库方言 | **PostgreSQL** | jsonb、FILTER、DATE_TRUNC、PostGIS、`::` 强转等全项目出现 |
| 数据源 | dynamic-datasource-spring-boot3-starter | 多数据源 |
| SQL 日志 | p6spy（MybatisPlusLogFactory） | 运行时 SQL 监控 |

**MPJ import 面（全量统计）**：

| import | 文件数 |
|--------|--------|
| `com.github.yulichang.base.MPJBaseMapper` | 67（modules 层 Mapper 接口声明） |
| `com.github.yulichang.base.MPJBaseServiceImpl` | 62（modules 层 ServiceImpl 继承） |
| `com.github.yulichang.base.MPJBaseService` | 59（modules 层 Service 接口） |
| `com.github.yulichang.wrapper.MPJLambdaWrapper` | 59（另有 1 文件全限定名无 import，共 **60** 个用户文件） |
| `com.github.yulichang.query.MPJLambdaQueryWrapper` | 1（app-merchant/SubAccountAppService） |
| `com.github.yulichang.toolkit.StrUtils` | 4（纯字符串工具，与 Wrapper 无关） |
| `com.github.yulichang.wrapper.MPJQueryWrapper` | **0** |

**原生 MP import / 用法面（全量统计）**：

| import / 用法 | 文件数 |
|--------|--------|
| `LambdaQueryWrapper` | **92** |
| `QueryWrapper`（字符串列版） | **1**（app-merchant/AccountManagementAppService） |
| `LambdaUpdateWrapper` | **36** |
| `UpdateWrapper`（字符串列版） | **7** |
| `LambdaDeleteWrapper` / `LambdaDeleteChainWrapper` | **0** |
| `LambdaQueryChainWrapper`（显式类型声明） | 1（modules/CarDevicePortInfoServiceImpl） |
| `service.lambdaQuery()` 链 | 35 文件（约 177 处调用） |
| `service.lambdaUpdate()` 链 | 20 文件（约 87 处调用） |

---

## 2. 统计总览

### 2.1 MPJ（mybatis-plus-join）

| 指标 | 数值 |
|------|------|
| 使用 MPJ 的源文件数 | **61**（app 46 + modules 14 + integration 1；另有 4 文件仅用 StrUtils） |
| `new MPJLambdaWrapper` 构造处数 | **125** |
| 使用 `MPJLambdaWrapper` | ✅ 60 文件全覆盖 |
| 使用 `MPJLambdaQueryWrapper` | 1 处（app-merchant/SubAccountAppService#configStationEcho） |
| 使用 `MPJQueryWrapper` | ❌ **0 处（项目完全未使用）** |

**MPJ 消费方法分布（全量）**：

| 消费方法 | 处数 | 载体 |
|----------|:----:|------|
| `selectJoinList(Class<R>, wrapper)` | 58 | MPJBaseService 隐式/显式接收者、注入的**另一 Service**（跨 Service 3 处）、**Mapper 直调**（2 处） |
| `selectJoinOne(Class<R>, wrapper)` | 36 | Service 隐式接收者为主（modules 层 12 处无显式接收者） |
| `selectJoinListPage(Page<R>, Class<R>, wrapper)` | 27 | app 层（`Page` 与 `IPage` 并存） |
| `selectJoinCount(wrapper)` | 4 | app-merchant/StationInfoService |
| `selectJoinPage` / `selectJoinMaps` / `selectJoinMapsPage` / `selectJoinOnePage` | ❌ 0 | – |
| 原生方法消费 MPJ wrapper（`getOne`/`count`/`remove`/`selectList`/`list`） | 约 12 | 见 P17 |

### 2.2 原生 MyBatis-Plus（查询）

| 指标 | 数值 |
|------|------|
| `LambdaQueryWrapper` 文件数 | **92**（app 层 70 + modules/infra/integration 22） |
| `QueryWrapper`（字符串列）文件数 | 1（app-merchant/AccountManagementAppService） |
| `lambdaQuery()` 链文件数 | 35（约 177 处调用） |
| `new Page<>(...)` | 92 处（全部两参构造，**无** searchCount/orders/isSearchCount 定制） |
| Controller 层直接构造 wrapper | 1 处（app-merchant/CarStationInfoController#all） |

**app 层条件方法命中数（grep 全量，含与 MPJ 同文件混用噪声，量级参考）**：

`eq` 1073 / `in` 157 / `isNotNull` 162 / `isNull` 240 / `like` 108 / `likeRight` 43 / `ge` 75 / `le` 42 / `ne` 35 / `gt` 27 / `lt` 17 / `between` 18 / `notIn` 3 / `apply` 20 / `last` 59 / `and(` 18 / `or(` 26 / `exists(` 21 / `groupBy` 1 / `select(` 103 / `orderByDesc` 198 / `orderByAsc` 128 / `having` 0 / `nested(` 0 / `func(` 0 / `inSql` 0 / `notInSql` 0 / `likeLeft` 0 / `notLike` 0 / `notBetween` 0 / 裸 `orderBy(` 0 / `selectMaps` 0 / `selectObjs` 0 / `selectMap` 0 / `getMap` 0

### 2.3 原生 MyBatis-Plus（更新 / 删除 / 链式）

| 指标 | 数值 |
|------|------|
| `LambdaUpdateWrapper` 文件数 | **36**（app 22 + integration 4 + modules 10） |
| `UpdateWrapper`（字符串版）文件数 | **7**（全部集中在优惠券/裂变域） |
| `lambdaUpdate()` 链文件数 | 20（约 87 处调用，含 1 处链式 `.remove()`） |
| `LambdaDeleteWrapper` | **0**（删除全部复用查询/更新 wrapper：`remove(wrapper)`、`mapper.delete(wrapper)`、`lambdaUpdate().remove()`） |
| `update(entity, wrapper)` 双参且 entity 非 null | 1 处（upsert 语义，app-admin/StationBarrierPushConfigRelAppService） |
| `setEntity(entity)` | 0 |
| `@Version` 乐观锁 | 0（以"手动 CAS 快照条件 eq"替代，3 处） |
| `@TableLogic` 逻辑删除 | BaseEntity.deleted 声明 + 全局配置；更新侧另有显式 `.eq(Entity::getDeleted, 0)` 约 10 处 |

### 2.4 Mapper XML / 注解 / 基础设施

| 指标 | 数值 |
|------|------|
| Mapper XML | **20 个**（biz 17 + sys 3），69 条语句（64 select + 5 update，**无 insert/delete 语句**） |
| 动态标签 | `<if>` 123 / `<foreach>` 50 / `<bind>` 110 / `<include>` 20 / `<sql>` 9 / `<choose>` 10 / `<when>` 14 / `<otherwise>` 6 / `<where>` 5 / `<set>` 0 / `<trim>` 0 / CDATA 7 |
| `#{}` / `${}` | `#{}` 约 400+（含 `#{p.xxx}`、`#{query.xxx}` 嵌套路径）；`${}` 22 次（**全部为 bind 常量的结果列别名**，无表名/列名/排序注入）；**无 `${ew.customSqlSegment}`** |
| resultMap | 8 个（仅 sys 结算模块，`<id>`+`<result>`，无 association/collection/constructor，1 个 `extends` 继承） |
| 注解 SQL | 4 个 Mapper 接口（@Delete ×4 日志清理、@Insert ×2 含 `ON CONFLICT` upsert、@Update ×3 钱包原子加减）；`@Mapper` 102 / `@Param` 201；**无** @Options/@Results/@SelectKey/`<script>` 注解 SQL |
| 实体注解 | `@TableName` 107（103 显式表名 + 4 命名参数含 `autoResultMap=true` ×3）；`@TableId` 28（**全部 `type = IdType.AUTO`**）；`@TableField` 40（fill INSERT 11 / INSERT_UPDATE 11 / typeHandler 9 / updateStrategy 7 / exist=false 1 / 保留字 value 1）；`@TableLogic` 1；`@Version` 0 |
| 拦截器 | 仅 `PaginationInnerInterceptor(DbType.POSTGRE_SQL)`；无 Tenant / OptimisticLocker / BlockAttack |
| 其他 | 自定义 `BatchInsertSqlInjector`（`insertBatchSomeColumn`，空字段走 `DEFAULT`）；`MetaObjectHandler`（insert 填 createTime/updateTime/deleted，update 填 updateTime）；全局 `update-strategy: always`、`id-type: auto`、`logic-delete-field: deleted` |

---

## 3. Wrapper 构造形式（去重）

### 3.1 MPJ 构造（C1~C7，全部实证）

| # | 构造形式 | 代表性位置 |
|---|----------|------------|
| C1 | `new MPJLambdaWrapper<T>()` 无参显式泛型 | 绝大多数（app 层 40+ 处） |
| C2 | `new MPJLambdaWrapper<>()` 菱形 + 分步续链 | app-merchant/StationInfoService（6 处）、modules/BikeStationShareSchemeMerchantInfoServiceImpl#listStationIdAndNameByMerchantId、modules/CarStationShareSchemeMerchantInfoServiceImpl（同构） |
| C3 | `new MPJLambdaWrapper<>(Entity.class)` 实体类构造 | app-admin/UserDiscountAppService#detail/#page、app-admin/MerchantRoleAppService#getDropList、modules/MerchantTeamInfoServiceImpl#getDetailWithVins |
| C4 | `new MPJLambdaWrapper<>(Entity.class, "表别名")` | app-admin/CarInvoiceAppService#buildOrderRelApplyJoinQuery（`"apply_info"`，全项目唯一） |
| C5 | 全限定名无 import 构造 | app-bridge/AbstractOperationStatsInfoPushService（3 处） |
| C6 | 私有工厂方法返回 + 跨方法续链（最长 3 级） | app-admin/GiftFundAppService#baseJoinWrapper、app-admin/MerchantWalletSettlementAppService#baseWrapper、app-admin/MerchantWalletWithdrawAppService#baseWithdrawQueryWrapper、app-admin/MerchantShareRecordAppService#buildQueryWrapper→#buildExportWrapper、app-merchant-admin/MerchantShareRecordAppService（同构）、app-merchant-admin/BikeDeviceInfoAppService#baseWrapper、app-merchant-admin/CarDeviceInfoAppService#baseWrapper |
| C7 | wrapper 构建后作为**实参传入另一个 Service / 模块**消费（跨对象） | modules/CarChargingSummaryDayServiceImpl#listPlatformOrderSummarySources/#listThirdOrderSummarySources（2 处，本类不继承 MPJ 基类）、integration/HuifuInvoiceReloginSmsReminderService（wrapper 内联跨模块传入 modules Service） |

### 3.2 原生 MP 构造（C8~C14）

| # | 构造形式 | 代表性位置 |
|---|----------|------------|
| C8 | `LambdaQueryWrapper<T> w = new LambdaQueryWrapper<>();` 声明后逐条续链（分步声明） | app-bridge/StationThirdExtendServiceImpl（3 处）、app-merchant/ChargingPortManagementService（8 处）、app-consumer/UserInvoiceAppService#page、app-merchant/CarStationInfoController#all |
| C9 | `new LambdaQueryWrapper<T>()` / `new LambdaQueryWrapper<>()` 内联作为方法实参 | app-admin/MenuAppService（5 处）、app-admin/StpInterfaceImpl（6 处）、app-consumer/CouponFissionService、app-callback/HuifuInvoiceAutoReissueService |
| C10 | `Wrappers.<T>lambdaQuery()` 工厂赋值长链 | app-admin/BannerAppService#list、app-admin/CarInvoiceAppService#buildInvoiceApplyBaseQuery、app-merchant-admin/SubAccountService#list、app-merchant-admin/CarOccupyRecordAppService#buildQueryWrapper |
| C11 | 工厂方法返回 wrapper 后调用点继续追加链 | app-admin/CarInvoiceAppService#listInvoiceApplyByCursor（`buildQueryWrapper(dto).gt(...).orderByAsc(...).last(...)`）、app-merchant-admin/CarOccupyRecordAppService#createExportTask |
| C12 | `Wrappers.<T>query()` 字符串列 QueryWrapper（全项目唯一） | app-merchant/AccountManagementAppService#queryDayAmountSummary |
| C13 | 更新型构造：`new LambdaUpdateWrapper<T>()` / `new LambdaUpdateWrapper<>(Entity.class)` / `Wrappers.lambdaUpdate()` / `Wrappers.<T>lambdaUpdate()` / `new UpdateWrapper<>()` / `new UpdateWrapper<>(Entity.class)` / `wrapper.lambda()` 动态转换 | 详见 §4.3 U01/U13 |
| C14 | 链式：`service.lambdaQuery()` / `service.lambdaUpdate()`（含 `this.lambdaUpdate()` 内部链） | 35 / 20 文件，详见 §4.3 U14/U17 |

---

## 4. 用法模式清单（核心测试语料）

> 每条模式 = 一个**可独立转换为 SQL 的用法形态**。模式编号稳定标识：`P01~P20`（MPJ）、`Q01~Q19`（原生查询）、`U01~U13`（更新/删除/链式）、`X01~X12`（XML/注解/配置）。
> 位置格式：`{模块}/{类名}#{方法}`；代码为代表性节选（实体名/列名已占位化，示例值一律 `xxx`/`1`/`dto.getX()` 风格）。模块缩写：admin / merchant / merchant-admin / consumer / bridge / callback / modules / integration / api / infra。

### 4.1 MPJ 模式（P01~P20，2026-08-13 全量复核更新）

### P01 单条详情查询：`selectAll + selectAs + leftJoin + eq (+orderBy)` → `selectJoinOne`

典型多表联查，`selectAll` 主表全列 + `selectAs` 关联表列映射到 VO/BO。

```java
MPJLambdaWrapper<DeviceInfoEntity> wrapper = new MPJLambdaWrapper<DeviceInfoEntity>()
        .selectAll(DeviceInfoEntity.class)
        .selectAs(DeviceInfoEntity::getId, DeviceDetailVO::getDeviceId)
        .selectAs(StationInfoEntity::getStationName, DeviceDetailVO::getStationName)
        .leftJoin(StationInfoEntity.class, StationInfoEntity::getId, DeviceInfoEntity::getStationId)
        .leftJoin(ProductInfoEntity.class, ProductInfoEntity::getId, DeviceInfoEntity::getProductId)
        .eq(DeviceInfoEntity::getId, deviceId);
DeviceDetailVO vo = deviceInfoService.selectJoinOne(DeviceDetailVO.class, wrapper);
```

**出现位置**：`merchant/DeviceManagementService#queryCarDeviceDetail/#queryBikeDeviceDetail`、`merchant/ChargingPortManagementService#queryCarChargingPortDetail/#queryBikeChargingPortDetail`（3 个 leftJoin）、`admin/BikeDeviceInfoAppService#getById`、`merchant-admin/BikeDeviceInfoAppService#getById`、`admin/CarDeviceInfoAppService#getById`、`merchant-admin/CarDeviceInfoAppService#getById`、`admin/IcCardInfoAppService#getDetail`、`admin/UserDiscountAppService#detail`、`admin/UserAppService#basic`、`bridge/PlatformService#findActivePushAccountByStationIdAndPlatformCode`、`modules/IcCardInfoServiceImpl#selectJoinOneWithWalletByPhysicalCardCode/#selectJoinOneWithWalletByLogicCardCode`、`modules/BizStationBarrierPushConfigRelServiceImpl#getByStationId`（innerJoin + 4×selectAs + 3×eq）、`modules/MerchantTeamInfoServiceImpl#getDetailWithVins`、`modules/CarDevicePortInfoServiceImpl#findDeviceAndStationByPortId/#findDeviceAndStationByNumberAndCode`（11 个 selectAs）、`modules/BikeDevicePortInfoServiceImpl#findDeviceStationPortByDeviceCodeAndPortNo`（innerJoin+leftJoin 混合）、`modules/BizCarStationOccupyInfoServiceImpl#getOccupyDetailBoByStationId`、`modules/CarStationPriceTaskRecordServiceImpl#getExecutionDetailById`、`modules/UserVinServiceImpl`、`consumer/CarStationAppService#getDetailByDeviceCode`。

**测试要点**：多表 `leftJoin` 顺序、主表与关联表字段混排、`selectJoinOne` 返回值映射；`innerJoin`/`leftJoin` 混合；无 `selectAll` 时纯 `selectAs` 映射链（`modules/CarDevicePortInfoServiceImpl` 11 列）。

---

### P02 分页列表查询：`selectAll + selectAs + leftJoin + 动态条件 + orderByDesc + selectJoinListPage`

条件均带 `condition` 布尔参数（`StringUtils.hasText(...)`、`dto.getX() != null`），未命中时忽略该条件。

```java
MPJLambdaWrapper<StationInfoEntity> wrapper = new MPJLambdaWrapper<StationInfoEntity>()
        .selectAll(StationInfoEntity.class)
        .selectAs(MerchantEntity::getName, StationInfoVO::getManagerName)
        .like(StringUtils.hasText(dto.getStationCode()), StationInfoEntity::getStationCode, dto.getStationCode())
        .eq(dto.getId() != null, StationInfoEntity::getId, dto.getId())
        .leftJoin(MerchantEntity.class, MerchantEntity::getId, StationInfoEntity::getManagerId)
        .orderByDesc(StationInfoEntity::getCreateTime);
Page<StationInfoVO> page = stationInfoService.selectJoinListPage(
        new Page<>(dto.getPageNum(), dto.getPageSize()), StationInfoVO.class, wrapper);
```

**出现位置**：`admin/BikeStationInfoAppService#page`、`admin/CarDeviceInfoAppService#page`、`admin/BikeDeviceInfoAppService#page`、`admin/MerchantWalletSettlementAppService#list`、`admin/GiftFundAppService#list`、`admin/UserAppService#orderRecord/#bikeOrderRecord/#occupyOrder/#walletFlow`、`admin/MerchantWalletWithdrawAppService#list`、`merchant/IcCardInfoAppService#page`、`merchant/HomeService#queryCarDevicePorts/#queryBikeDevicePorts`（**返回 `IPage`**）、`merchant-admin/CarStationInfoAppService#page`、`merchant-admin/BikeDeviceInfoAppService#page`、`merchant-admin/DeviceInfoAppService#page`、`consumer/CarOccupyAppService#page`、`bridge/PlatformService#pageThirdPlatformOrder`。

**测试要点**：`eq/like/likeRight/ge/le/ne/in` 的条件参数重载（第一个参数为 boolean）；`selectJoinListPage` 的 `Page` 参数泛型 `Page<R>`；`Page` 与 `IPage` 两种返回类型并存；多条件组合、双 `orderByDesc`。

---

### P03 私有工厂方法返回 Wrapper + 跨方法续链（最长 3 级）

Wrapper 由 `baseWrapper()` / `buildQueryWrapper(dto)` 等私有方法构建并返回，调用方继续 `.eq(...)`、`.orderByDesc(...)`、`.last(...)`。**插件需要跨方法追踪 Wrapper 变量**。

```java
// 工厂方法（返回可变 MPJ wrapper）
private MPJLambdaWrapper<CouponEntity> baseJoinWrapper() {
    return new MPJLambdaWrapper<CouponEntity>()
            .selectAll(CouponEntity.class)
            .selectAs(MerchantEntity::getName, CouponVO::getMerchantName)
            .leftJoin(MerchantEntity.class, MerchantEntity::getId, CouponEntity::getMerchantId);
}
// 调用方续链
MPJLambdaWrapper<CouponEntity> wrapper = baseJoinWrapper()
        .eq(StrUtil.isNotBlank(dto.getCouponCode()), CouponEntity::getCouponCode, dto.getCouponCode())
        .orderByDesc(CouponEntity::getCreateTime);
```

**出现位置**：`admin/GiftFundAppService#baseJoinWrapper/#list/#detail`、`admin/MerchantWalletSettlementAppService#baseWrapper/#list`、`merchant-admin/MerchantWalletSettlementAppService#list`、`admin/MerchantWalletWithdrawAppService#baseWithdrawQueryWrapper/#baseWithdrawJoinWrapper/#applyWithdrawFilters/#createExportTask`（**3 级链**：base → join → filters → 续链 gt/orderByAsc/last）、`admin/MerchantShareRecordAppService#buildQueryWrapper/#buildExportWrapper/#listNextDetails`、`merchant-admin/MerchantShareRecordAppService#buildQueryWrapper/#buildExportWrapper/#listNextDetails`、`merchant-admin/BikeDeviceInfoAppService#baseWrapper`、`merchant-admin/CarDeviceInfoAppService#baseWrapper`、`admin/CarInvoiceAppService#buildOrderRelApplyJoinQuery`。

**测试要点**：Wrapper 在方法间传递仍为**同一可变对象**；`buildExportWrapper` 返回后继续 `.last("LIMIT " + N)`；`selectJoinCount` 与 `selectJoinListPage`/`selectJoinList` 复用同一工厂链；同 wrapper 先 `selectCount` 预检后追加 `set` 更新（原生侧见 U12）。

---

### P04 下拉/回显：`select + selectAs` 两列映射到 DropListVO

无 join，主表 `select(Entity::getId)` + `selectAs(Entity::getName, DropListVO::getValue)`。

```java
return bikeProductInfoService.selectJoinList(DropListVO.class,
        new MPJLambdaWrapper<BikeProductInfoEntity>()
                .select(BikeProductInfoEntity::getId)
                .selectAs(BikeProductInfoEntity::getModel, DropListVO::getValue));
```

**出现位置**：`admin/BikeDeviceProductInfoAppService#dropAll`、`admin/BikeStationInfoAppService#getDropList`、`admin/CarStationInfoAppService#getDropList/#getDropListMerchant`（后者含 leftJoin + orderByDesc + stream 二次组装）、`admin/MerchantRoleAppService#getDropList`、`merchant-admin/RoleAppService#getDropList`、`merchant/controller/UserController#all`（**select varargs 4 列**）、`consumer/CarStationAppService#listAllEnabledStationTags`、`admin/HelpCenterInfoAppService#listAllDropList`（含 `ne(boolean, ...)`）、`admin/BikeDeviceInfoAppService#listUnbound`、`admin/CarDeviceInfoAppService#listUnbound`（含 `isNull`）。

**测试要点**：`select` varargs（单列/多列）、`selectAs` 别名映射、无 join 的纯单表场景、`isNull`/`ne` 条件、结果类型 `DropListVO`（common 层公共 VO）。

---

### P05 `distinct + innerJoin` 多级联表去重

连续两次 `innerJoin` 关联到二级表，`distinct` 去重后取主表外键列（或映射 DropListVO）；同一结构 wrapper 分别喂 `selectJoinList(Long.class, ...)` 与 `selectJoinCount`。

```java
MPJLambdaWrapper<SchemeMerchantInfoEntity> schemeWrapper = new MPJLambdaWrapper<>();
schemeWrapper.select(SchemeInfoEntity::getStationId)
        .distinct()
        .innerJoin(SchemeInfoEntity.class,
                SchemeInfoEntity::getId, SchemeMerchantInfoEntity::getSchemeId)
        .innerJoin(StationInfoEntity.class,
                StationInfoEntity::getId, SchemeInfoEntity::getStationId)
        .eq(SchemeMerchantInfoEntity::getMerchantId, merchantId);
return stationSchemeMerchantInfoService.selectJoinList(Long.class, schemeWrapper);
```

**变体（映射 DropListVO）**：`modules/BikeStationShareSchemeMerchantInfoServiceImpl#listStationIdAndNameByMerchantId`、`modules/CarStationShareSchemeMerchantInfoServiceImpl#listStationIdAndNameByMerchantId`——`selectAs(主表列, DropListVO::getX)` + `distinct()` + 双 `innerJoin` + `this.` 显式接收者。

**出现位置**：`merchant/StationInfoService#getCarStationIdsByMerchantId/#getBikeStationIdsByMerchantId/#countCarStationIdsByMerchantId/#countBikeStationIdsByMerchantId`（4 处，同一 wrapper 结构双消费）、`modules/BikeStationShareSchemeMerchantInfoServiceImpl`、`modules/CarStationShareSchemeMerchantInfoServiceImpl`。

**测试要点**：`distinct` 生成 `SELECT DISTINCT`；多级 `innerJoin`；`selectJoinCount` 需忽略 select 列生成 `COUNT(*)`；wrapper 级 `distinct` 全项目仅此 6 处（其余 `.distinct(` 均为 Java stream）。

---

### P06 `selectCollection` 一对多集合映射（详情聚合）

`selectCollection(子表类, 目标VO::get集合, map -> map.id(...).result(...))` 生成一对多子查询；可与多个 `leftJoin` 叠加。

```java
MPJLambdaWrapper<OccupyRecordEntity> wrapper = new MPJLambdaWrapper<OccupyRecordEntity>()
        .selectAll(OccupyRecordEntity.class)
        .selectCollection(
                OccupyConsumptionRecordEntity.class,
                OccupyRecordDetailVO::getConsumptions,
                map -> map
                        .id(OccupyConsumptionRecordEntity::getId, OccupyRecordConsumptionItemVO::getId)
                        .result(OccupyConsumptionRecordEntity::getLadder, OccupyRecordConsumptionItemVO::getLadder)
                        .result(OccupyConsumptionRecordEntity::getFee, OccupyRecordConsumptionItemVO::getFee))
        .leftJoin(OccupyConsumptionRecordEntity.class,
                OccupyConsumptionRecordEntity::getOccupyId, OccupyRecordEntity::getId)
        .eq(OccupyRecordEntity::getId, id)
        .orderByAsc(OccupyConsumptionRecordEntity::getLadder);
```

**变体**：
- 简写 `selectCollection(类, VO::get集合)`（无子映射）：`admin/UserAppService#wallet`、`modules/GiftFundServiceImpl#listAvailableDetailByUserId`（+leftJoin+3×orderByAsc 含子表列）、`modules/MerchantTeamInfoServiceImpl#getDetailWithVins`
- 单条内**两个 selectCollection**：`admin/CarOccupyRecordAppService#detail`（2 个 + 2×leftJoin + orderByDesc + orderByAsc）、`merchant-admin/CarOccupyRecordAppService#detail`（2 个）、`admin/UserAppService#basic`（2 个）
- map 与简写混用同一链：`admin/CarStationPriceTaskRecordAppService#getById`（stations 带 map + priceItems 简写）
- 与大量 selectAs + 多 leftJoin 叠加：`consumer/CarDeviceAppService#getDetailByDeviceCode`（7 个 selectAs + 3 leftJoin）

**出现位置**：`admin/CarOccupyRecordAppService#detail`、`admin/UserAppService#basic/#wallet`、`admin/CarStationPriceTaskRecordAppService#getById`、`merchant-admin/CarOccupyRecordAppService#detail`、`consumer/CarOccupyAppService#detail`、`consumer/CarDeviceAppService#getDetailByDeviceCode`、`modules/BizCarStationOccupyInfoServiceImpl#getOccupyDetailBoByStationId/#getOccupyDetailBos`（带 map）、`modules/GiftFundServiceImpl#listAvailableDetailByUserId`（简写）、`modules/MerchantTeamInfoServiceImpl#getDetailWithVins`（简写）。

**测试要点**：`selectCollection` 子映射 `id/result`；多 `selectCollection`；`selectCollection` + `selectAll` + 多 `leftJoin` 组合；一对多结果按 `orderByAsc(子表列)` 排序；map 与简写混用。

---

### P07 `selectCollection` 内嵌套 `collection` 三层关联 + 简写混用

子映射中继续 `.collection(孙表类, 孙VO::get集合)`；同链第二条 `selectCollection` 简写；与 innerJoin/leftJoin 三连 join + 3 个 `orderByAsc`（含 join 表列）叠加。

```java
MPJLambdaWrapper<PriceTaskRecordEntity> wrapper = new MPJLambdaWrapper<PriceTaskRecordEntity>()
        .selectAll(PriceTaskRecordEntity.class)
        .selectCollection(PriceTaskStationRelEntity.class, PriceTaskExecutionBO::getStations,
                stationMap -> stationMap
                        .id(PriceTaskStationRelEntity::getId, PriceTaskExecutionStationBO::getRelationId)
                        .result(PriceTaskStationRelEntity::getStationId, PriceTaskExecutionStationBO::getStationId)
                        .collection(DeviceInfoEntity.class, PriceTaskExecutionStationBO::getDevices))
        .selectCollection(PriceTaskItemRecordEntity.class, PriceTaskExecutionBO::getPriceItems)
        .innerJoin(...).leftJoin(...).innerJoin(...)
        .eq(PriceTaskRecordEntity::getId, taskId)
        .orderByAsc(PriceTaskStationRelEntity::getId)
        .orderByAsc(DeviceInfoEntity::getId)
        .orderByAsc(PriceTaskItemRecordEntity::getDicPriceType);
```

**出现位置**：`modules/CarStationPriceTaskRecordServiceImpl#getExecutionDetailById`（任务→场站→设备三层）。

**测试要点**：两级集合嵌套的 SQL 形态（多级子查询/分组）、`selectCollection` 简写与带映射混用、`orderByAsc` 引用 join 表列。

---

### P08 `selectAssociation` 一对一关联映射

```java
MPJLambdaWrapper<DeviceInfoEntity> wrapper = new MPJLambdaWrapper<DeviceInfoEntity>()
        .selectAll(DeviceInfoEntity.class)
        .selectAssociation(ProductInfoEntity.class, DeviceInfoBO::getProductInfo)
        .leftJoin(ProductInfoEntity.class, ProductInfoEntity::getId, DeviceInfoEntity::getProductId)
        .eq(DeviceInfoEntity::getId, stationId);
return Optional.ofNullable(selectJoinOne(DeviceInfoBO.class, wrapper)).map(this::getDeviceInfoBO);
```

**出现位置**：`bridge/PlatformService#findCarDeviceInfoByStationIds`（同时含 if/else 分叉与 stream().peek 后处理）、`modules/CarDeviceInfoServiceImpl#findById/#findByIds/#findByDeviceCode/#findByDeviceCodes`（4 处；**SFunction 目标用全限定类名** `com.cyzc.platform.modules.domain.bo.CarDeviceInfoBO::getProductInfo`，无 import 的 lambda 类型解析）。

**测试要点**：`selectAssociation` 一对一映射、结果后处理（`Optional.map`、`stream().map(...).toList()`）、全限定名 SFunction 目标。

---

### P09 `selectFunc` 自定义 SQL 聚合函数（统计查询）

`selectFunc("COUNT(%s)", func -> func.accept(列).values(参数), "别名")`，`%s` 占列、`{0}`/`{1}` 占绑定参数。

```java
MPJLambdaWrapper<WalletWithdrawEntity> wrapper = applyWithdrawFilters(baseWithdrawQueryWrapper(), dto)
        .selectFunc("COUNT(%s)", func -> func.accept(WalletWithdrawEntity::getId), "totalCount")
        .selectFunc("COUNT(CASE WHEN %s = {0} AND %s = {1} THEN 1 END)",
                func -> func.accept(WalletWithdrawEntity::getDicAuditStatus,
                                WalletWithdrawEntity::getDicWithdrawStatus)
                        .values(DicConstants.DIC_VALUE_WALLET_AUDIT_APPROVED,
                                DicConstants.DIC_VALUE_WITHDRAW_PROCESSING),
                "paymentProcessingCount")
        .selectFunc("COALESCE(SUM(%s), 0)",
                func -> func.accept(WalletWithdrawEntity::getWithdrawAmount), "totalWithdrawAmount");
return walletWithdrawService.selectJoinOne(WalletWithdrawStatisticsVO.class, wrapper);
```

**出现位置**：`admin/MerchantWalletWithdrawAppService#queryStatistics`（共 **10 个** `selectFunc`，含 COUNT(%s)/COUNT(CASE WHEN)/COALESCE(SUM(%s),0)，与工厂链 baseWithdrawQueryWrapper→applyWithdrawFilters 组合）。

**测试要点**：`%s` 列占位符替换、`{0}` 参数替换、`func.accept(...)` 多列、`func.values(...)` 多值、别名列名映射到 VO 字段、`{0}` 与 `?` 混合时参数序。

---

### P10 `apply` 动态/参数化 SQL 片段（三形态）

```java
// 形态一：无条件 SQL 片段（短路空集）
wrapper.apply("1 = 0");
// 形态二：带 boolean 条件 + 占位参数 {0} {1}（元组比较）
wrapper.apply(ObjectUtil.isNotNull(lastApplyId) && lastApplyId > 0,
        "(rel.apply_id, rel.id) > ({0}, {1})", lastApplyId, ObjectUtil.isNull(lastRelId) ? 0L : lastRelId);
// 形态三：拼接外部 SQL（PostgreSQL jsonb 表达式）
String sql = buildStationIdsFilterSql(columnName, stationIds);   // "(station_ids @> '[x]'::jsonb or ...)"
wrapper.apply(StrUtil.isNotBlank(sql), sql);
```

**出现位置**：`admin/CarInvoiceAppService#applyBusinessOpenInfoFilter`（`apply("1 = 0")` + `in` 大集合）、`admin/CarInvoiceAppService#listOrderRelByApplyJoinCursor`（**元组比较参数化 apply**）、`admin/CarInvoiceAppService#applyStationIdsFilter`（jsonb `@>` 拼接 + 字符串列名参数）、`admin/GiftFundAppService#list`（`apply(condition, buildShowStatusCondition(...))` boolean + 动态 SQL）。

**测试要点**：`apply` 原样透传 SQL；`{0}/{1}` 绑定参数替换（含元组比较形态）；带 boolean 条件的重载；方言表达式（jsonb）透传；**M19.6（用户实测）：无布尔前缀的 `apply` 同样生成复选框**（`apply("1 = 0")` 短路空集可在参数面板勾选开关，flagSource 为 null 默认勾选）。

---

### P11 表别名构造 + 字符串列 + 带别名 join（别名四件套）

```java
MPJLambdaWrapper<InvoiceApplyEntity> wrapper = new MPJLambdaWrapper<>(InvoiceApplyEntity.class, "apply_info")
        .selectAs(InvoiceApplyOrderRelEntity::getId, InvoiceOrderExportJoinRow::getId)
        .innerJoin(InvoiceApplyOrderRelEntity.class, "rel",
                InvoiceApplyOrderRelEntity::getApplyId, InvoiceApplyEntity::getId)
        .likeRight(StrUtil.isNotBlank(dto.getApplyNo()), InvoiceApplyEntity::getApplyNo, dto.getApplyNo())
        .ge(ObjectUtil.isNotNull(dto.getStartTime()), InvoiceApplyEntity::getCreateTime, dto.getStartTime());
// 调用方继续：别名排序 + 游标截断
... .orderByAsc("rel", InvoiceApplyOrderRelEntity::getApplyId)
    .orderByAsc("rel", InvoiceApplyOrderRelEntity::getId)
    .last("limit " + EXPORT_ORDER_CURSOR_BATCH_SIZE);

// 字符串列 + 带别名 join（auditAccount）
.selectAs(AUDIT_ACCOUNT_ALIAS + ".username", WalletWithdrawVO::getAuditedUsername)
.leftJoin(AccountEntity.class, AUDIT_ACCOUNT_ALIAS, AccountEntity::getId, WalletWithdrawEntity::getAuditedBy)
```

**出现位置**：`admin/CarInvoiceAppService#buildOrderRelApplyJoinQuery/#listOrderRelByApplyJoinCursor`（构造别名 `apply_info` + `innerJoin(Class, "rel", ...)` **四参别名 innerJoin** + `orderByAsc("rel", ...)` 字符串别名排序）、`admin/MerchantWalletWithdrawAppService#baseWithdrawJoinWrapper/#baseWithdrawQueryWrapper`（别名 `auditAccount`，字符串列 `selectAs("auditAccount.username", ...)` + 别名 join 配合）。

**测试要点**：带别名构造（`new MPJLambdaWrapper<>(Class, alias)`）、`leftJoin/innerJoin(Class, alias, ...)` 四参重载、`selectAs("alias.column", ...)` 字符串列映射、`orderByAsc("alias", Entity::getX)` 别名排序——四处别名需联动解析。

---

### P12 `last` 分页 / 游标截断

```java
return Optional.ofNullable(orderMonitorRecordService.getOne(
        new MPJLambdaWrapper<OrderMonitorRecordEntity>()
                .eq(OrderMonitorRecordEntity::getOrderCode, orderCode)
                .orderByDesc(OrderMonitorRecordEntity::getTime)
                .last("limit 1")));
```

**出现位置**（MPJ 链内 6 处）：`bridge/PlatformService#findActivePushAccountByStationIdAndPlatformCode/#findPushPortDeviceViewByStationIdAndPortId/#findBasicPushPortDeviceViewByStationIdAndPortId/#findLatestOrderMonitorByOrderId/#findLatestOrderMonitorByOrderCode`（`getOne` + `last("limit 1")`）、`consumer/CarOccupyAppService#getLastUnpaid`（`selectJoinOne` + `last("limit 1")`）、`modules/UserVinServiceImpl#selectJoinOneWithWalletByVin`（MPJ 链内唯一，含 and/or）、`admin/CarInvoiceAppService#listOrderRelByApplyJoinCursor`（`"limit " + N` 游标）、`admin/MerchantShareRecordAppService#listNextDetails`、`merchant-admin/MerchantShareRecordAppService#listNextDetails`、`admin/MerchantWalletWithdrawAppService#createExportTask`（`"LIMIT " + EXPORT_BATCH_SIZE`）。

**注意**：app 层共 37+ 处 `.last(`，**多数在原生 wrapper 上**（见 Q12）——插件必须按 wrapper 类型归属。

**测试要点**：`last` 原样追加到 SQL 末尾（不换行、不改写）；与 `orderByDesc` 组合的"取最新一条"；导出游标 `last("LIMIT " + N)`；大小写并存（`"limit 1"` vs `"LIMIT " + n`）。

---

### P13 `leftJoin` lambda 形式 ON 条件

```java
if (stationId != null && dicStationCategory != null) {
    wrapper.leftJoin(CouponStationEntity.class, on -> on
                    .eq(CouponStationEntity::getCouponId, CouponEntity::getId)
                    .eq(CouponStationEntity::getStationId, stationId)
                    .eq(CouponStationEntity::getDicStationCategory, dicStationCategory))
            .and(w -> w.eq(CouponEntity::getDicCouponType, DicConstants.DIC_VALUE_COUPON_TYPE_GENERAL)
                    .or(t -> t.eq(CouponEntity::getDicCouponType, DicConstants.DIC_VALUE_COUPON_TYPE_TARGETED)
                            .isNotNull(CouponStationEntity::getId)));
}
wrapper.orderByDesc(CouponEntity::getDicCouponType)
        .orderByAsc(CouponEntity::getCreateTime)
        .orderByAsc(CouponEntity::getId);
```

**出现位置**：`modules/GiftFundServiceImpl#listAllocatableForStation/#sumAllocatableRemainByUserIds`（2 处；均为 **if 条件内追加** join + and，if 之后继续排序续链；结果映射**实体类** `GiftFundEntity.class`）。

**测试要点**：`leftJoin(Class, on -> on.eq(...))` lambda ON；`on` 内多条件 `eq`；与 `.and(w -> ...or(t -> ...isNotNull(...)))` 叠加；条件式续链（P15 同链）。

> **M19.5/M19.6 扩展（2026-08-13 用户实测）**：① **四参别名 + lambda ON**：`leftJoin(Class, "t1", on -> on.eq("t1.workflow_id", Entity::getId).apply("...子查询..."))`——别名与 lambda ON 组合形态（parseJoin 4b 分支）；② on 内 **`eq` 字符串列**（`eq("t1.workflow_id", 主表列)` 左字符串列原样、右方法引用加主表别名）；③ on 内 **`apply` 子查询**（`Condition.Raw` 原样透传）；④ **双字符串 eq**（Scratch 用例）：`eq("t1.workflow_id", "t.id")` 右参含 `.` 按**字符串列引用**生成列对列比较（`t1.workflow_id = t.id`，不加引号）；无 `.` 的字符串右参按值（`eq("t2.script_status", "1")` → `= '1'`）。

---

### P14 `and` / `or` 嵌套复合条件

```java
wrapper.and(condition -> condition
        .gt(ShareRecordEntity::getDicBizType, cursor.dicBizType)
        .or(or -> or.eq(ShareRecordEntity::getDicBizType, cursor.dicBizType)
                .gt(ShareRecordEntity::getBizId, cursor.bizId))
        .or(or -> or.eq(ShareRecordEntity::getDicBizType, cursor.dicBizType)
                .eq(ShareRecordEntity::getBizId, cursor.bizId)
                .gt(ShareRecordEntity::getMerchantId, cursor.merchantId))
        .or(or -> or.eq(ShareRecordEntity::getDicBizType, cursor.dicBizType)
                .eq(ShareRecordEntity::getBizId, cursor.bizId)
                .eq(ShareRecordEntity::getMerchantId, cursor.merchantId)
                .gt(ShareRecordEntity::getId, cursor.id)));

// 无参 or() 作分隔符（AND ( a = ? OR b = ? )）+ 表达式参数
wrapper.and(wr -> wr.eq(UserInfoEntity::getCode, code)
        .or()
        .eq(UserInfoEntity::getCode, new StringBuilder(code).reverse().toString()));
```

**出现位置**：`admin/MerchantShareRecordAppService#appendCursorCondition`（4 层嵌套游标）、`merchant-admin/MerchantShareRecordAppService#appendCursorCondition`（同构）、`merchant/HomeService#applyCarKeywordFilter/#applyBikeKeywordFilter`（`and(w -> w.like(...).or().like(...))`）、`merchant/HomeService#applyCarDicStatusFilter/#applyBikeDicStatusFilter`（`and(w -> w.eq(...).or().eq(...))`）、`merchant/IcCardInfoAppService#page`（if 内追加 and）、`modules/UserVinServiceImpl#selectJoinOneWithWalletByVin`（`or()` 无参分隔符 + `new StringBuilder(...).reverse().toString()` 表达式参数）、`modules/GiftFundServiceImpl`（`and(w -> eq.or(t -> eq.isNotNull))`）。

**测试要点**：`and(lambda)` / `or(lambda)` 嵌套、无参 `or()` 分隔符、参数可为 `new StringBuilder(...).reverse()` 表达式、游标 3~4 层嵌套的括号配对。

---

### P15 条件式续链（if/else 分叉）

```java
MPJLambdaWrapper<DeviceInfoEntity> wrapper = new MPJLambdaWrapper<DeviceInfoEntity>()
        .selectAll(DeviceInfoEntity.class)
        .selectAssociation(ProductInfoEntity.class, DeviceInfoBO::getProductInfo)
        .leftJoin(ProductInfoEntity.class, ProductInfoEntity::getId, DeviceInfoEntity::getProductId);
if (stationIds.size() == 1) {
    wrapper.eq(DeviceInfoEntity::getStationId, stationIds.get(0));
} else {
    wrapper.in(DeviceInfoEntity::getStationId, stationIds);
}
```

**出现位置**：`bridge/PlatformService#findCarDeviceInfoByStationIds`（if/else eq vs in）、`merchant/HomeService#applyCarDicStatusFilter/#applyBikeDicStatusFilter`（if/else 分支 eq/ne）、`merchant/IcCardInfoAppService#page`（if 内追加 `and(lambda)`）、`modules/GiftFundServiceImpl`（if 分支内追加 `leftJoin` + `and`，if 后继续 `orderByDesc/orderByAsc` 续链）。

**测试要点**：Wrapper 被局部变量持有后按分支追加条件；分支内 `in(List)` 单元素等价性；if 分支追加 join 后链外继续排序。

---

### P16 Wrapper 作为方法入参就地修改

```java
private MPJLambdaWrapper<WalletWithdrawEntity> applyWithdrawFilters(
        MPJLambdaWrapper<WalletWithdrawEntity> wrapper, DTO dto) {
    return wrapper
            .like(StrUtil.isNotBlank(dto.getWithdrawCode()), WalletWithdrawEntity::getWithdrawCode, dto.getWithdrawCode())
            .eq(dto.getMerchantId() != null, WalletWithdrawEntity::getMerchantId, dto.getMerchantId())
            .ge(dto.getStartTime() != null, WalletWithdrawEntity::getCreateTime, dto.getStartTime())
            .le(dto.getEndTime() != null, WalletWithdrawEntity::getCreateTime, dto.getEndTime());
}
```

**出现位置**：`admin/MerchantWalletWithdrawAppService#applyWithdrawFilters`、`admin/CarInvoiceAppService#applyStationIdsFilter/#applyBusinessOpenInfoFilter`（MPJ 重载）、`admin/MerchantShareRecordAppService#appendCursorCondition`、`merchant-admin/MerchantShareRecordAppService#appendCursorCondition`、`merchant/HomeService#applyCarKeywordFilter/#applyCarDicStatusFilter/#applyBikeKeywordFilter/#applyBikeDicStatusFilter`。

**测试要点**：`MPJLambdaWrapper<T>` 作为入参类型；入参方法内继续链式（有 return 与无 return 两种形态）；与 P03 工厂模式配合（3 级链）。

> **M19.6 扩展（2026-08-13 用户实测）**：**wrapper 作为实参传入自定义 helper 方法的跨方法续链追踪**——`applyBusinessOpenInfoFilter(wrapper, dto)` 方法体内对形参的直接链式调用（`wrapper.apply("1 = 0")`、`wrapper.in(col, list)` 等）自动并入主链（helper 调用点之后逻辑排序，P15 if 分支去重同样生效）；**MPJ wrapper 同样适用**（`applyWorkflowDataScope(MPJLambdaWrapper)` 内 eq/ge/likeRight 等追加条件并入联表查询 WHERE，主表别名 `t.` 前缀统一）；**`wrapper.clear()` 截断**——按源码顺序遇 clear() 丢弃此前收集的条件（MP 语义清空），buildChain 与工厂链统一处理。

---

### P17 原生 MyBatis-Plus 方法消费 MPJ wrapper

```java
// 删除（remove，IService 原生）
if (!bikeDevicePortInfoService.remove(new MPJLambdaWrapper<BikeDevicePortInfoEntity>()
        .eq(BikeDevicePortInfoEntity::getDeviceId, id))) { ... }

// getOne / count（IService 原生）
Optional.ofNullable(orderMonitorRecordService.getOne(
        new MPJLambdaWrapper<OrderMonitorRecordEntity>().eq(...).orderByDesc(...).last("limit 1")));
long count = orderMonitorRecordService.count(new MPJLambdaWrapper<...>().eq(...).ge(...).lt(...));

// BaseMapper.selectList（仅条件场景，无 join）
List<StationChargingSummaryDayEntity> list = chargingSummaryDayMapper.selectList(
        new MPJLambdaWrapper<StationChargingSummaryDayEntity>()
                .select(StationChargingSummaryDayEntity::getStationId)
                .eq(StationChargingSummaryDayEntity::getDeleted, 0)
                .in(StationChargingSummaryDayEntity::getStationId, stationIds));   // Set<Long>

// MPJBaseMapper 直调 selectJoinList
return deviceInfoMapper.selectJoinList(DeviceShareMerchantJoinBO.class, w);

// IService.list + MPJLambdaQueryWrapper（字符串版，仅此 1 处）
subAccountStationService.list(new MPJLambdaQueryWrapper<SubAccountStationEntity>()
        .select(SubAccountStationEntity::getStationId, SubAccountStationEntity::getDicStationCategory)
        .eq(...).eq(...))
```

**出现位置**：`admin/BikeDeviceInfoAppService#delete`（remove）、`bridge/PlatformService`（getOne ×2、count ×1 `existsOrderMonitorWithinWindow`）、`bridge/AbstractOperationStatsInfoPushService#queryStationSummary/#queryDeviceSummaryList/#queryPortSummaryList`（selectList ×3，**全限定名构造**）、`bridge/CommonThirdPlatformOpenApiServiceImpl`（selectList ×3）、`merchant/SubAccountAppService#configStationEcho`（list + MPJLambdaQueryWrapper，select 用 SFunction 非字符串列）、`modules/CarChargingSummaryDayServiceImpl#buildStationSummaryList`（selectList ×2，`eq(列, 0)` + `in(Set)`）、`modules/SysMerchantShareRecordServiceImpl#listCarMerchantsByDeviceId/#listBikeMerchantsByDeviceId`（**Mapper 直调 selectJoinList ×2**）、`modules/CarChargingSummaryDayServiceImpl#listPlatformOrderSummarySources/#listThirdOrderSummarySources`（wrapper 传**另一 Service** 消费 ×2）。

**测试要点**：MPJ wrapper 兼容喂给原生 MP 方法；`getOne`+`last("limit 1")` 取首行；`MPJLambdaQueryWrapper` 字符串版仅此一处；Mapper 直调与跨 Service 消费。

---

### P18 聚合：`selectMax` / `selectCount` + `groupBy`

```java
// selectMax 单聚合（映射基本类型）
MPJLambdaWrapper<OrderMonitorRecordEntity> wrapper = new MPJLambdaWrapper<OrderMonitorRecordEntity>()
        .selectMax(OrderMonitorRecordEntity::getPower)
        .eq(OrderMonitorRecordEntity::getOrderId, order.getId());
List<BigDecimal> powerList = orderMonitorRecordService.selectJoinList(BigDecimal.class, wrapper);

// selectCount + 多 groupBy（integration 模块跨模块消费）
new MPJLambdaWrapper<InvoiceApplyEntity>()
        .selectAs(InvoiceApplyEntity::getHuifuId, InvoiceReminderBO::getHuifuId)
        .selectCount(InvoiceApplyEntity::getId, InvoiceReminderBO::getApplyCount)
        .innerJoin(InvoiceBusinessOpenInfoEntity.class,
                InvoiceBusinessOpenInfoEntity::getHuifuId, InvoiceApplyEntity::getHuifuId)
        .in(InvoiceApplyEntity::getDicApplyStatus, REMIND_APPLY_STATUSES)
        .in(InvoiceApplyEntity::getFailReason, REMIND_FAIL_REASONS)
        .isNotNull(InvoiceApplyEntity::getHuifuId)
        .ne(InvoiceApplyEntity::getHuifuId, StrUtil.EMPTY)
        .isNotNull(InvoiceBusinessOpenInfoEntity::getContactMobile)
        .ne(InvoiceBusinessOpenInfoEntity::getContactMobile, StrUtil.EMPTY)
        .groupBy(InvoiceApplyEntity::getHuifuId)
        .groupBy(InvoiceBusinessOpenInfoEntity::getContactName)
        .groupBy(InvoiceBusinessOpenInfoEntity::getContactMobile)
        .orderByAsc(InvoiceApplyEntity::getHuifuId)
        .orderByAsc(InvoiceBusinessOpenInfoEntity::getContactMobile);
```

**出现位置**：`merchant/OrderManagementService#buildCarChargeCurveSummary`（selectMax + 映射 BigDecimal）、`integration/HuifuInvoiceReloginSmsReminderService#listReminderRows`（selectCount + 3×groupBy + 2×in 常量 List + isNotNull ×2 + `ne(列, StrUtil.EMPTY)` ×2 + 2×orderByAsc，wrapper 内联传入 modules Service）。

**测试要点**：`selectMax/selectCount` 聚合列生成；`groupBy` 多列（含关联表列）；`in(List)` 常量集合；`ne(列, StrUtil.EMPTY)` 空串过滤；映射到基本类型 `BigDecimal.class`；selectAs 与 selectCount 混排。

---

### P19 全限定名构造（无 import）

```java
chargingSummaryDayMapper.selectList(
        new com.github.yulichang.wrapper.MPJLambdaWrapper<StationChargingSummaryDayEntity>()
                .eq(StationChargingSummaryDayEntity::getStationId, stationId)
                .eq(StationChargingSummaryDayEntity::getStartDate, startDate));
```

**出现位置**：`bridge/AbstractOperationStatsInfoPushService#queryStationSummary/#queryDeviceSummaryList/#queryPortSummaryList`（3 处）。

**测试要点**：PSI 解析需支持全限定类型名识别（另有"全限定名 SFunction 目标"形态见 P08，两类位置都要支持）。

---

### P20 映射到非 VO 类型 / 结果流后处理

```java
// 映射基本类型
List<Long> ids = ...selectJoinList(Long.class, schemeWrapper);
List<BigDecimal> powers = ...selectJoinList(BigDecimal.class, wrapper);
// 映射实体本身
List<GiftFundEntity> funds = selectJoinList(GiftFundEntity.class, wrapper);
List<RoleEntity> roles = roleService.selectJoinList(RoleEntity.class, wrapper);
// 结果 stream 后处理
List<DeviceInfoBO> list = deviceInfoService.selectJoinList(DeviceInfoBO.class, wrapper)
        .stream().peek(dto -> {...}).toList();
```

**出现位置**：`merchant/StationInfoService`（Long.class 5 处）、`merchant/OrderManagementService`（BigDecimal.class）、`merchant-admin/AuthAppService#fillRolesAndMenus`（**MerchantRoleEntity.class + innerJoin + eq**）、`modules/GiftFundServiceImpl`（GiftFundEntity.class ×3）、`bridge/PlatformService#findCarDeviceInfoByStationIds`（stream().peek + if/else）、`modules/CarDeviceInfoServiceImpl`（stream().map().toList()）、`admin/BikeStationInfoAppService#page`（selectJoinListPage 结果 stream().peek 回填统计）。

**测试要点**：`Class<R>` 形参可为基本类型包装类、实体类；转换引擎不应假设目标类型必为 VO。

### 4.2 原生查询模式（Q01~Q19，LambdaQueryWrapper / QueryWrapper）

### Q01 构造形态全集（4 种，见 §3.2 C8~C12）

```java
// ① 分步声明后逐条续链
LambdaQueryWrapper<OrderEntity> w = new LambdaQueryWrapper<>();
w.eq(OrderEntity::getOrderCode, code);
w.orderByDesc(OrderEntity::getCreateTime);
w.last("limit 1");
// ② Wrappers 工厂长链
LambdaQueryWrapper<OrderEntity> w = Wrappers.<OrderEntity>lambdaQuery()
        .eq(OrderEntity::getOrderCode, code)
        .orderByDesc(OrderEntity::getCreateTime);
// ③ 内联实参（不落变量）
List<RoleEntity> roles = roleMapper.selectList(new LambdaQueryWrapper<RoleEntity>()
        .eq(RoleEntity::getCode, code));
// ④ 工厂方法返回后调用点继续追加链（导出游标）
return buildQueryWrapper(dto)
        .gt(OrderEntity::getId, lastId)
        .orderByAsc(OrderEntity::getId)
        .last("LIMIT " + BATCH_SIZE);
```

**出现位置**：① `bridge/StationThirdExtendServiceImpl`、`merchant/ChargingPortManagementService`（8 处）、`consumer/UserInvoiceAppService#page`；② `admin/BannerAppService#list`、`admin/CarInvoiceAppService#buildInvoiceApplyBaseQuery`、`merchant-admin/SubAccountService#list`；③ `admin/MenuAppService`（5 处）、`admin/StpInterfaceImpl`（6 处）、`consumer/CouponFissionService`；④ `admin/CarInvoiceAppService#listInvoiceApplyByCursor`、`merchant-admin/CarOccupyRecordAppService#createExportTask`。

**测试要点**：同一文件内四种形态并存；工厂返回的 wrapper 需跟踪"追加条件累积顺序"。

---

### Q02 列选择：`select(SFunction...)` 多列 / 单列

```java
// 多列（5~20 列）
.orderByDesc(...) // 前置条件略
.list(Wrappers.<XxxEntity>lambdaQuery()
        .select(XxxEntity::getId, XxxEntity::getCode, XxxEntity::getName, ... /* 最多 20 列 */)
        .eq(...));
// 单列（取单字段/去重）
List<Long> ids = userService.list(Wrappers.<UserEntity>lambdaQuery()
        .select(UserEntity::getId)
        .eq(UserEntity::getDeleted, 0));
```

**出现位置**：多列 `admin/CarStationInfoAppService#all`（5 列）、`admin/CouponAppService#getSendList`（11 列）、`admin/CarInvoiceAppService#listInvoiceApplyByCursor`（20 列）、`merchant-admin/SubAccountService#list`（6 列）、`merchant/StationInfoManagementService`（6 处）；单列 `admin/UserAppService#listUserIds/#getBalance`、`consumer/UserDiscountAppService`、`consumer/CarStationInfoAppService`（3 处）。

**测试要点**：`select` varargs 数量不定（1~20）；列清单后接消费方法（`list`/`page`）。

---

### Q03 字符串列 select + SQL 表达式（QueryWrapper 唯一用例）

```java
QueryWrapper<MerchantWalletFlowEntity> sumQw = Wrappers.<MerchantWalletFlowEntity>query()
        .select("DATE(create_time)::timestamp as create_time, amount_direction, SUM(change_available_amount) as change_available_amount")
        .eq("merchant_id", merchantId)
        .between("create_time", startTime, endTime)
        .in(CollUtil.isNotEmpty(flowTypes), "dic_flow_type", flowTypes)
        .groupBy("DATE(create_time), amount_direction");
merchantWalletFlowService.list(sumQw);
```

**出现位置**：`merchant/AccountManagementAppService#queryDayAmountSummary`（全项目唯一 `QueryWrapper` + 字符串列 + 聚合表达式 + 字符串 `groupBy`）。

**测试要点**：字符串列 + SQL 函数/别名表达式（`::timestamp` 方言强转、`SUM(...) as ...`）；`in(boolean, String, Collection)` 字符串列重载；`between(String, ...)`。

---

### Q04 条件全家桶：`eq/ne/gt/ge/lt/le`（含 boolean 重载）

```java
.eq(dto.getDicStatus() != null, AccountEntity::getDicStatus, dto.getDicStatus())
.eq(StringUtils.hasText(dto.getDeviceCode()), BikeOrderRecordEntity::getDeviceCode, dto.getDeviceCode())
.eq(dto.getNumber() != null && !dto.getNumber().isBlank(), CarDevicePortInfoEntity::getNumber, dto.getNumber())
.ge(ObjectUtil.isNotNull(dto.getStartTime()), OrderEntity::getCreateTime, dto.getStartTime())
.le(ObjectUtil.isNotNull(dto.getEndTime()), OrderEntity::getCreateTime, dto.getEndTime())
.ne(AccountEntity::getCode, "")                    // 字符串常量
.gt(OrderEntity::getChargeDegree, BigDecimal.ZERO) // 常量值
.gt(ObjectUtil.isNotNull(lastApplyId) && lastApplyId > 0, OrderEntity::getId, lastApplyId) // 复合布尔表达式
```

**出现位置**：`eq` 1073 处、`ge` 75、`gt` 27、`le` 42、`lt` 17、`ne` 35（app 层全量）；代表性：`admin/BannerAppService#list`、`admin/BikeOrderRecordAppService`（174-178）、`merchant/CarInvoiceManagementAppService#page`、`merchant-admin/SubAccountService#checkPhoneUnique`（ne boolean 重载）、`consumer/CarOrderAppService#activeOrder`、`admin/StpInterfaceImpl#getPermissionList`（`ne(col, "")`）。

**测试要点**：boolean 条件表达式形态多样（判空 / `StringUtils.hasText` / `&&` 复合 / `== null` 取反 / 方法返回值）——**首个布尔参数不得被当作列名**；值参数可为常量（`""`、`BigDecimal.ZERO`）与表达式（`Long.valueOf(...)`）。

---

### Q05 `like` / `likeRight`（含 boolean 重载；likeLeft/notLike 未使用）

```java
.like(StringUtils.hasText(dto.getKeyword()), OrderEntity::getOrderCode, dto.getKeyword())
.likeRight(StrUtil.isNotBlank(dto.getMobile()), UserEntity::getMobile, dto.getMobile())   // 前缀匹配
```

**出现位置**：`like` 108 处、`likeRight` 43 处（手机号/单号前缀匹配）：`merchant/CarInvoiceManagementAppService#page`（75-87）、`merchant-admin/CarOccupyRecordAppService#buildQueryWrapper`（220-222）、`admin/MerchantAppService`（140-141）、`consumer/BikeOrderAppService`（176-179）。

**测试要点**：`like` → `LIKE CONCAT('%', val, '%')`、`likeRight` → `LIKE CONCAT(val, '%')`；boolean 重载。

---

### Q06 `in` 四种形态（Collection / boolean / varargs 多值 / 字符串列）

```java
.in(OrderEntity::getId, orderIds)                                    // Collection（157 处主流）
.in(CollUtil.isNotEmpty(flowTypes), "dic_flow_type", flowTypes)      // boolean + 字符串列
.in(StoreCouponUserEntity::getDicReceiveType, A, B, C, D)            // varargs 多值常量
.in(dto.getDicApplyStatus() == null, col, V1, V2, V3, V4)            // boolean + varargs（条件取反用法）
.in(CarDevicePortInfoEntity::getNumber, singleConstant)              // varargs 退化单值
```

**出现位置**：`admin/StpInterfaceImpl#getPermissionList`（`in(col, roleIds)` 前次查询结果回填）、`admin/CarStationInfoAppService#listChargeTypes`、`merchant-admin/SubAccountService#validateRoleIds`、`consumer/CouponUserService`（4 常量 varargs）、`consumer/UserInvoiceAppService#page`（284-288，boolean 取反 + 4 常量）、`merchant/ChargingPortManagementService`（342/445，单值 varargs）、`merchant/AccountManagementAppService#queryDayAmountSummary`（字符串列 boolean 形态）。

**测试要点**：`in` 参数可为 `Collection`/`List`/`Set`/varargs 常量/单值；boolean 条件取反用法（`dto.getX() == null` 为真时 in 默认集）；集合来源多样（DTO 列表、流式去重、上次查询结果、`List.of(...)` 常量）；**M19.6（用户实测）：`in(col, List 变量)` 的值来源进动态条件行输入框**（替代普通参数区避免重复），参数面板输入 `1,2` 回填为 `('1','2')`（逗号分割多值、每个值独立引号，String 列恒引号）。

---

### Q07 `notIn` / `isNull` / `isNotNull`

```java
.notIn(CollUtil.isNotEmpty(boundStationIds), OrderEntity::getStationId, boundStationIds)
.isNull(OrderEntity::getDeleted)          // 240 处
.isNotNull(OrderEntity::getPayId)         // 162 处
```

**出现位置**：`notIn` 仅 3 处（`admin/BarrierPushConfigAppService` boolean 形态、`admin/MerchantContractAppService`、`merchant/MerchantWalletSettlementAppService`）；`isNull/isNotNull` 高频且常嵌在 `and` 嵌套内（`consumer/UserInvoiceAppService#page` 356、`consumer/CarInvoiceTxService#queryActiveApplies` 的 `and(w -> w.isNull(...).or().isNotNull(...))`、`merchant/CouponAppService#getSendList`）。

**测试要点**：`isNull` 生成 `IS NULL`（非普通比较）；与 and/or 嵌套组合。

---

### Q08 `between`（含 boolean 重载）

```java
.between(OrderEntity::getCreateTime, startTime, endTime)
.between(ObjUtil.isAllNotEmpty(dto.getStartTime(), dto.getEndTime()),
        OrderEntity::getCreateTime, dto.getStartTime(), dto.getEndTime())
```

**出现位置**：18 处：`merchant/AccountManagementAppService#rechargeRecord/#fundDetail`、`merchant-admin/CarOrderRecordAppService`（447/501）、`consumer/UserDiscountAppService`（82 boolean/98）、`consumer/CarOrderAppService`（310/414）、`merchant/OrderManagementService`、`merchant/ChargingPortManagementService`。

**测试要点**：`between(col, v1, v2)` → `BETWEEN ? AND ?`；boolean 重载用 `ObjUtil.isAllNotEmpty` 双参判断；**M19.5（用户实测）：between 动态条件行渲染两个值输入框**（lo/hi 两个占位符 source 各一个输入框，`ConditionToggle.valueSources` 多值扩展，2026-08-13）。

---

### Q09 `groupBy`（字符串列，全项目仅 1 处）

```java
.groupBy("DATE(create_time), amount_direction")
```

**出现位置**：`merchant/AccountManagementAppService#queryDayAmountSummary`（与 Q03 同链）。**`having` 全项目 0 使用**。

**测试要点**：字符串版 groupBy 原样透传（含函数与逗号分隔多列）。

---

### Q10 `orderByAsc` / `orderByDesc`（多列、同链混排）

```java
.orderByDesc(OrderEntity::getCreateTime)
// 同链多段混排（asc+desc）
.orderByAsc(MenuEntity::getSort).orderByDesc(MenuEntity::getCreateTime)
// 多列同段
.orderByDesc(OrderEntity::getCreateTime, OrderEntity::getId)
```

**出现位置**：`orderByDesc` 198 处、`orderByAsc` 128 处；混排：`admin/BannerAppService#list`、`admin/CouponAppService#getSendList/#getCouponPage`、`merchant/CarInvoiceManagementAppService#titleDetail`。**裸 `orderBy(`、boolean 重载、字符串列 orderBy 均未使用**。

**测试要点**：多列 varargs 与多段混排；排序与 `last("limit n")`/分页组合。

---

### Q11 `apply` 四种形态（raw / {0} 参数化 / boolean / 短路）

```java
.apply("jsonb_typeof(station_ids) = 'array' AND jsonb_array_length(station_ids) > 0")   // raw 原生 SQL
.apply("station_ids <@ CAST({0} AS jsonb)", JSON.toJSONString(ownedStationIds))          // {0} 参数化（JSON 序列化集合）
.apply("1 = 0")                                                                           // 短路空集
.apply(ObjUtil.isNotNull(showStatus), buildShowStatusCondition(showStatus))               // boolean + 动态 SQL
.apply("COALESCE(frozen_amount, 0) >= {0}", allocated)                                    // 余额保护
```

**出现位置**：20 处：`merchant/CarInvoiceManagementAppService#page`（95-96，jsonb 数组包含）、`merchant-admin/CarInvoiceAppService#page`（97-98）、`admin/CarInvoiceAppService`（278/290 短路）、`merchant/CarInvoiceManagementAppService#applyBusinessOpenInfoFilter`（242 短路）、`consumer/CarStationInfoAppService`（79 boolean + 动态）、`merchant/MerchantWalletWithdrawAppService`（233 余额保护）、`consumer/CouponFissionService`（321 计数上限）。

**测试要点**：`apply` 原样透传；`{0}` 占位与 `CAST({0} AS jsonb)` 上下文类型推断；参数可为 `JSON.toJSONString(集合)`。

---

### Q12 `last`（取首行 / 游标截断，59 处）

```java
.last("limit 1")                      // 与 orderByDesc 组合取最新一条
.last("LIMIT " + EXPORT_BATCH_SIZE)   // 导出游标
```

**出现位置**：`bridge/StationThirdExtendServiceImpl`（78/95/113）、`merchant/OrderManagementService`（252/265）、`merchant/ChargingPortManagementService`（344/357/447/459/499/548）、`consumer/CarOrderAppService`（192）、`consumer/BikeOrderAppService`（117）、`admin/UserAppService`（1206/1217）、`merchant-admin/CarOccupyRecordAppService#createExportTask`（117）、`admin/CarInvoiceAppService`（621/643）、`consumer/UserInvoiceAppService`（75/78/112/117）。

**测试要点**：`last` 透传末尾追加；与 `orderByDesc` 组合；大小写与参数拼接并存（`"limit 1"` vs `"LIMIT " + n`）。

---

### Q13 `and(lambda)` / `or()` / `or(lambda)` 嵌套（含循环内动态构建）

```java
// 简单嵌套
.and(o -> o.eq(XxxEntity::getA, a).or().ge(XxxEntity::getB, b))
// boolean 重载
.and(StrUtil.isNotBlank(dto.getKeyword()), w -> w.like(...).or().like(...))
// 循环内动态 or（逐元素拼接 apply）
wrapper.and(nested -> {
    boolean first = true;
    for (Long stationId : stationIds) {
        if (!first) { nested.or(); }
        nested.apply("station_ids @> CAST({0} AS jsonb)", JSON.toJSONString(List.of(stationId)));
        first = false;
    }
});
```

**出现位置**：`admin/CouponAppService#getSendList`（143-147）、`admin/OrderManagementService`（95/443/497 boolean 重载）、`consumer/UserInvoiceAppService`（166-168）、`merchant/CarInvoiceManagementAppService#applyRequestedStationFilter`（212-221，**循环 or 构建**）、`merchant-admin/CarInvoiceAppService`（219-226 同构）、`consumer/BikeOrderAppService`（97/445/499 `.or().likeRight(...)`）。

**测试要点**：无参 `or()` 的"裸 OR 连接前一个条件"语义；for 循环内动态 `or()` 拼接（分支内条件数量运行时才确定）；括号嵌套层级跟踪。

---

### Q14 `exists` 三形态（21 处）

```java
// ① SQL 字符串 + {0} 参数（关联子查询）
.exists(dto.getStationId() != null,
        "SELECT 1 FROM rel WHERE rel.task_id = main.id AND rel.station_id = {0}", dto.getStationId())
// ② wrapper 子查询（service 级）
boolean exists = merchantService.exists(Wrappers.<MerchantEntity>lambdaQuery()
        .eq(MerchantEntity::getCode, code));
// ③ 链式终结点
boolean exists = orderService.lambdaQuery().eq(OrderEntity::getOrderCode, code).exists();
```

**出现位置**：① `admin/CarStationPriceTaskRecordAppService#page`（64-68）；② `admin/MerchantAppService`（165/205）、`admin/DictTypeAppService`、`admin/CarStationTagAppService`、`consumer/UserVinAppService`、`consumer/UserPlateAppService`；③ `consumer/UserInvoiceAppService`（793-796）、`consumer/GiftFundDataService`、`admin/GiftFundAppService#xxx`（102）。

**测试要点**：三种形态的 SQL 生成差异；`{0}` 参数化子查询；链式终结点 `exists()` 与查询 wrapper 子查询需区分。

---

### Q15 消费方法全集（原生）

```java
service.list(wrapper)                                  // 66+ 处
service.getOne(wrapper)                                // 28 处
service.getOne(wrapper, false)                         // 2 处（防多记录抛异常）
service.count(wrapper)                                 // 大量
service.page(new Page<>(pageNum, pageSize), wrapper)   // 92 处 new Page
service.getOneOpt(wrapper)                             // 4 处（MP 3.5+ Optional 版）
service.remove(wrapper)                                // 多条（逻辑删除）
mapper.selectList(wrapper) / selectOne(wrapper) / selectCount(wrapper) / selectPage(page, wrapper) / delete(wrapper)
service.lambdaQuery()...one()/.oneOpt()/.list()/.count()/.exists()/.page()/.last("limit 1")
```

**出现位置**：`admin/MenuAppService`（count 5 处）、`merchant-admin/SubAccountService#checkPhoneUnique/#validateRoleIds`（count）、`consumer/CarOrderAppService#getLastActiveOrder`（`getOne(w, false)` + `last("LIMIT 1")`）、`consumer/BikeOrderAppService`（111-118 同构）、`merchant/ChargingPortManagementService`（getOneOpt 4 处）、`bridge/StationThirdExtendServiceImpl`（mapper.delete/selectOne/selectList）、`admin/CouponAppService`（149 selectList、322 **mapper.selectPage**）、`admin/CarInvoiceAppService`（1502 mapper.selectCount）、`callback/HuifuInvoiceAutoReissueService`（157 selectCount、145 **mapper.update(null, lambdaUpdate)**）。

**测试要点**：`getOne(wrapper, false)` 第二参识别；`getOneOpt` 返回值 Optional；`Page<VO>` 泛型；`mapper.update(null, wrapper)` 的 null 第一参；**`selectMaps/selectObjs/selectMap/getMap/listMaps/listObjs` 全项目 0 使用**。

---

### Q16 Page 用法（默认配置，无定制）

```java
Page<StationInfoVO> page = stationInfoService.page(
        new Page<>(dto.getPageNum(), dto.getPageSize()), wrapper);
// MPJ 侧并行形态（P02）：selectJoinListPage
```

**出现位置**：92 处 `new Page<>(...)`；`admin/BannerAppService#list`、`admin/CouponAppService#getCouponPage`（mapper.selectPage）、`merchant/CarInvoiceManagementAppService#page`、`merchant-admin/SubAccountService#list`、`consumer/UserInvoiceAppService#page`、`callback/HuifuInvoiceQueryApplyCompensateService`。**未发现** `searchCount`/`setSearchCount`/`setOptimizeCountSql`/`setOrders` 定制；`Page<Long>` 用于纯 ID 分页（`bridge/StationThirdExtendServiceImpl#findActiveStationIdPageByPlatformCode`，配合自定义 mapper 方法）。

**测试要点**：两参构造为唯一形态；`Page<VO>` 与 `Page<Long>` 并存。

---

### Q17 `lambdaQuery()` 链式（35 文件，约 177 处调用）

```java
OrderEntity order = orderService.lambdaQuery()
        .eq(OrderEntity::getOrderCode, code)
        .one();
Optional<OrderEntity> opt = accountService.lambdaQuery()
        .eq(AccountEntity::getId, id)
        .oneOpt();
List<OrderEntity> list = orderService.lambdaQuery()
        .eq(OrderEntity::getDicStatus, status)
        .orderByDesc(OrderEntity::getCreateTime)
        .last("LIMIT 1")
        .list();
long count = stationService.lambdaQuery().eq(StationEntity::getCode, code).count();
boolean exists = giftFundDataService.lambdaQuery().eq(GiftFundEntity::getId, id).exists();
```

**出现位置**：`merchant/CarInvoiceManagementAppService#listOrders/#findByApplyNo`（one）、`merchant/AccountManagementAppService`（oneOpt 6 处）、`callback/HuifuInvoiceAutoReissueService`（62-64 one、145-152 update 链）、`consumer/UserInvoiceAppService`（793-796 exists）、`merchant/StationInfoManagementService`（729-748 count）、`modules/CarOrderInvoiceLifecycleService#getById`（294/313 one）。

**测试要点**：链式终结点全集（`.one()/.oneOpt()/.list()/.count()/.exists()/.page()/.last("LIMIT 1")`）；`lambdaQuery()` 链与 `lambdaUpdate()` 链（Q17 与 U13）方法名归属区分；显式 `LambdaQueryChainWrapper` 类型声明仅 1 处（`modules/CarDevicePortInfoServiceImpl#findByDeviceCodeAndNumber`，先 `count(wrapper)` 查重再 `getOne(wrapper)` 复用）。

> ✅ **已修复（M19.5，300 测试全绿）**：链式**赋值给结果变量**的形态（`XxxEntity e = service.lambdaQuery()...one();`、`long total = ...count();`）gutter 预览图标不显示——`WrapperAnchorDetector.isInsideWrapperVariableInitializer` 的"变量初始化去重"未区分 **Wrapper 变量**与**结果变量**，整条链失去锚点（临时回归测试实证复现后修复：仅对 Wrapper 变量去重，转换链路/快捷键路径本不受影响）。修复记录见 `docs/开发计划/20260813-1536-用户实测两类未识别形态修复-开发计划.md`。

---

### Q18 字符串列 QueryWrapper 聚合查询（Q03 完整链，见 Q03 代码）

全项目唯一字符串列聚合链：`select(String 表达式)` + `eq("merchant_id", ...)` + `between("create_time", ...)` + `in(boolean, "dic_flow_type", flowTypes)` + `groupBy("DATE(create_time), amount_direction")` → `service.list(qw)`。

**出现位置**：`merchant/AccountManagementAppService#queryDayAmountSummary`（890-898）。

**测试要点**：F-17 字符串版 QueryWrapper 的唯一真实语料；PostgreSQL `::timestamp` 方言。

---

### Q19 边界模式：查询接力 / 复合游标 / helper 入参

```java
// ① 上游结果回填 in（查询接力）
List<Long> roleIds = roleMapper.selectList(...).stream().map(RoleEntity::getId).toList();
... .in(RoleEntity::getId, roleIds)   // admin/StpInterfaceImpl、admin/CarInvoiceAppService、consumer/CarInvoiceTxService
// ② 复合游标 keyset pagination（and + 3 层 or 嵌套 + last）
wrapper.and(condition -> condition
        .gt(ShareRecordEntity::getDicBizType, cursor.dicBizType)
        .or(or -> or.eq(...).gt(...))
        .or(or -> or.eq(...).eq(...).gt(...)));
// ③ wrapper 作为方法入参就地修改（helper）
private void applyStationIdsFilter(LambdaQueryWrapper<OrderInvoiceApplyEntity> wrapper, List<Long> stationIds) {
    wrapper.and(nested -> { ... });
}
```

**出现位置**：① `admin/StpInterfaceImpl#getPermissionList`、`admin/CarInvoiceAppService`（281/293）、`consumer/CarInvoiceTxService`（339-359）；② `admin/MerchantShareRecordAppService`（235-245）、`merchant-admin/MerchantShareRecordAppService`（230-235）；③ `merchant/CarInvoiceManagementAppService#applyRequestedStationFilter/#applyBusinessOpenInfoFilter`、`admin/CarInvoiceAppService#applyStationIdsFilter`、`consumer/UserInvoiceAppService#applyStationIdsFilter`。

**测试要点**：跨查询数据流（前一查询结果作为后一查询参数）；游标括号嵌套；helper 方法内 wrapper 追加条件需跨方法追踪。

---

### 4.3 更新 / 删除 / 链式模式（U01~U13，LambdaUpdateWrapper / UpdateWrapper / Chain）

### U01 `LambdaUpdateWrapper` 构造三形态

```java
// ① 空构造 + 链式（30 处）
LambdaUpdateWrapper<BikeOrderRecordEntity> uw = new LambdaUpdateWrapper<BikeOrderRecordEntity>()
        .eq(BikeOrderRecordEntity::getId, id)
        .eq(BikeOrderRecordEntity::getDicPayStatus, PAY_STATUS_PENDING)
        .set(BikeOrderRecordEntity::getDicPayStatus, PAY_STATUS_SUCCESS);
return this.baseMapper.update(null, uw) > 0;
// ② 类构造（12 处）
orderService.update(new LambdaUpdateWrapper<>(OrderEntity.class)
        .set(OrderEntity::getDicRefundStatus, REFUND_STATUS)
        .eq(OrderEntity::getId, id));
// ③ Wrappers 工厂（25 处）
LambdaUpdateWrapper<AccountEntity> uw = Wrappers.lambdaUpdate();
uw.set(AccountEntity::getLastLoginTime, now).eq(AccountEntity::getId, account.getId());
accountService.update(uw);
```

**出现位置**：① `modules/BikeOrderRecordServiceImpl#updatePayStatusSuccessById`、`modules/CarOrderRecordServiceImpl`、`modules/MerchantWalletRechargeServiceImpl#closeTimedOutRecharges`、`admin/CarInvoiceAppService#lockOrdersForRed`（1839）、`consumer/CouponExpiryAsyncService`、`callback/HuifuInvoiceQueryApplyCompensateService`、`integration/BikeChargingConsumptionService`；② `consumer/BikeOrderAppService`（442/447）、`consumer/CarOrderAppService`（508/513）、`consumer/BikeOrderRefundService`、`consumer/CarOrderRefundService`、`consumer/CarInvoiceTxService`（653 变量形式）、`admin/CarDeviceInfoAppService#saveName`、`merchant-admin/CarOccupyRecordAppService`（210）、`integration/CarStatusConsumptionService`；③ `admin/AuthAppService`（82）、`admin/CarStationInfoAppService#updateDicStatus`（192）、`admin/CouponAppService#delete`（163）、`modules/UserDiscountInfoServiceImpl`（90/111）、`modules/CarOrderRecordServiceImpl`（37）。

**测试要点**：三种构造 + 三种消费（`update(wrapper)` / `update(entity, wrapper)` / `mapper.update(null, wrapper)`）的交叉组合；`Wrappers.lambdaUpdate()` 无泛型参数形态。

---

### U02 `set` 系列：长链多列（1~15 个 set）

```java
LambdaUpdateWrapper<CarOrderRecordEntity> uw = new LambdaUpdateWrapper<CarOrderRecordEntity>()
        .eq(CarOrderRecordEntity::getId, orderId)
        .eq(CarOrderRecordEntity::getDicStatus, STATUS_CHARGING)
        .set(CarOrderRecordEntity::getDicStatus, STATUS_WAIT_SETTLE)
        .set(CarOrderRecordEntity::getEndTime, endTime)
        .set(CarOrderRecordEntity::getTotalAmount, BigDecimal.ZERO)
        /* ... 最长 15 个 set */
```

**出现位置**：`modules/CarOrderRecordServiceImpl#closePendingChargeOrdersPayTimedOut`（**15 个 set + 4 条件**）、`modules/BikeOrderRecordServiceImpl`（13 个 set）、`consumer/CarOccupyAppService#markOccupyPaid`（6 set + 2 eq）。

**测试要点**：单链多 set 列清单生成；状态机模式（前置状态 eq + 目标状态 set）——**条件含旧值、set 含新值，不能把条件列误当 set 列**。

---

### U03 `set(boolean, ...)` 条件 set / `set(SFunction, null)` 置空

```java
orderInvoiceApplyService.update(new LambdaUpdateWrapper<OrderInvoiceApplyEntity>()
        .eq(OrderInvoiceApplyEntity::getId, applyId)
        .set(StrUtil.isNotBlank(seqId), OrderInvoiceApplyEntity::getSeqId, seqId)   // 条件 set
        .set(OrderInvoiceApplyEntity::getRespDesc, respDesc)
        .set(OrderInvoiceApplyEntity::getDicApplyStatus, STATUS_PROCESSING));

// 置空列
.set(OrderInvoiceApplyEntity::getSeqId, null)
.set(CarOrderRecordEntity::getInvoiceLockTime, null)
```

**出现位置**：条件 set 约 25 处：`modules/CarOrderInvoiceLifecycleService`（193/200/201/339/356-358/387/395-397/452/457-459）、`admin/MerchantWalletWithdrawAppService`（167/666/683/691）、`admin/UserAppService`（1012/1023）、`modules/UserPaymentTradeRecordServiceImpl`（条件为 `DicConstants.X.equals(tradeStatus)` 表达式）、`callback/HuifuCallbackAppService`（304 链式内）；置空约 10 处：`modules/CarOrderInvoiceLifecycleService`（257/391/392/419-424/475-480）、`admin/CarStationPriceTaskRecordAppService`（167/168/245/246）。

**测试要点**：`set(boolean, col, val)` 布尔参数为条件开关；`set(col, null)` 生成 `SET col = NULL`。

---

### U04 `setSql` 原生 SQL 四变体（约 30 处）

```java
// ① 列间赋值（关单退款）
.setSql("refund_amount = prepay_amount")
// ② 原子自增
.setSql("use_total = use_total + 1")
.setSql("callback_count = COALESCE(callback_count, 0) + 1")
// ③ {0} 占位符参数化（余额扣减 + 防超扣配对）
.setSql("frozen_amount = COALESCE(frozen_amount, 0) - {0}", allocated)
.apply("COALESCE(frozen_amount, 0) >= {0}", allocated)
// ④ 字符串拼接 + CASE WHEN（最复杂）
.setSql("use_count = COALESCE(use_count, 0) + 1")
.setSql("dic_status = CASE WHEN dic_amount_cap_type = {限量类型} AND COALESCE(total_discount_amount, 0) + {金额} >= amount_cap THEN {失效状态} ELSE dic_status END")
```

**出现位置**：① `modules/BikeOrderRecordServiceImpl`（66/155/191/218）、`modules/CarOrderRecordServiceImpl`（71/130/159/227）、`modules/CarThirdOrderRecordServiceImpl`（72/92/112）、`admin/CarDeviceInfoAppService#saveName`（`setSql("smy_port_id = id")` 169）；② `admin/CouponUserService`（170/178）、`consumer/CouponUserService`（238/246/322/331/464）、`consumer/CouponFissionService`（88）、`merchant/CouponUserService`（191/199）、`callback/MerchantCallbackAppService`（74）、`callback/HuifuCallbackAppService`（302 链式内）；③ `consumer/GiftFundPayService`（229-231/314-315/366-367）；④ `modules/UserDiscountInfoServiceImpl#tryRecordUsage`（86-100，与 `apply({0})` 混用）。

**测试要点**：`setSql` 值本身是 SQL 片段需透传；`{0}` 与 `?` 参数化语义区分；字符串拼接表达式（`" + discountSql + "`）识别为同一 set 项；`setSql` 与 `apply` 配对（余额保护模式）。

---

### U05 更新 wrapper 上的 `apply` / `last`（5 处）

```java
// apply：上限保护
wrapper.apply("(dic_amount_cap_type != {0} OR COALESCE(total_discount_amount, 0) < amount_cap)", limitedCapType)
// last：防超发兜底（条件追加到 SQL 尾部）
wrapper.setSql("last_total = last_total - " + num);
wrapper.last("AND (last_total - " + num + " >= 0)");
```

**出现位置**：`modules/UserDiscountInfoServiceImpl`（93）、`consumer/GiftFundPayService`（233）、`consumer/CouponUserService`（321，字符串 UpdateWrapper + apply）、`modules/StoreCouponServiceImpl#deduction`（69-77，`last("AND (... >= 0)")`）。

**测试要点**：`last` 追加的"AND ..."在语义上是 WHERE 条件但出现在语句尾部——插件需原样透传。

---

### U06 更新条件面：状态机 CAS / 范围 / 批量 in / 逻辑删除显式条件

```java
// 状态机 CAS：前置状态条件 + 目标状态 set
.eq(OrderEntity::getId, orderId)
.eq(OrderEntity::getDicStatus, STATUS_CHARGING)
.set(OrderEntity::getDicStatus, STATUS_WAIT_SETTLE);
// 批量 in + deleted 显式条件
carOrderRecordMapper.update(null, new LambdaUpdateWrapper<CarOrderRecordEntity>()
        .in(CarOrderRecordEntity::getId, orderIds)
        .eq(CarOrderRecordEntity::getDeleted, 0)
        .set(CarOrderRecordEntity::getDicInvoiceStatus, STATUS)
        .set(CarOrderRecordEntity::getInvoiceLockTime, now));
// 范围条件
.ge(OrderEntity::getStartTime, startTimeBefore.minusDays(1))
.lt(OrderEntity::getStartTime, startTimeBefore)
// boolean 条件
.eq(bizType != null, MerchantPaymentTradeRecordEntity::getBizType, bizType)
```

**出现位置**：`modules/CarOrderRecordServiceImpl#updateStopChargeToWait`（189）、`modules/CarThirdOrderRecordServiceImpl#updateStopChargeToWait`（118）、`modules/CarOrderInvoiceLifecycleService`（208-221/403-424/469-480，in + eq deleted + set）、`admin/CarInvoiceAppService`（1839-1945 多段）、`modules/MerchantPaymentTradeRecordServiceImpl`（34 boolean）、`consumer/UserInvoiceAppService`（387-391）、`integration/BikeStatusConsumptionService`（69/176，含 ne）。

**测试要点**：更新链条件面与查询面相同（eq/in/ge/lt/ne/apply/last）；显式 `.eq(getDeleted, 0)` 与全局逻辑删除自动追加并存。

---

### U07 消费方式五种

```java
// ① service.update(wrapper)（约 30 处，boolean 返回）
if (!bikeStationInfoService.update(Wrappers.<BikeStationInfoEntity>lambdaUpdate()
        .set(BikeStationInfoEntity::getDicStatus, dto.getDicStatus())
        .eq(BikeStationInfoEntity::getId, dto.getId()))) { throw ...; }
// ② service.update(entity, wrapper) 双参非 null（全项目唯一，upsert 语义）
boolean saved = stationBarrierPushConfigRelService.update(
        entity,
        Wrappers.<BizStationBarrierPushConfigRelEntity>lambdaUpdate()
                .eq(BizStationBarrierPushConfigRelEntity::getStationId, stationId))
        || stationBarrierPushConfigRelService.save(entity);
// ③ mapper.update(null, wrapper) / baseMapper.update(null, wrapper)（约 45 处，int 返回）
return this.baseMapper.update(null, uw) > 0;
// ④ lambdaUpdate() 链式终结点 .update()（约 40 处）
boolean updated = merchantWalletWithdrawService.lambdaUpdate()
        .set(MerchantWalletWithdrawEntity::getDicWithdrawStatus, STATUS)
        .set(StrUtil.isNotBlank(transactionId), MerchantWalletWithdrawEntity::getTransactionId, transactionId)
        .eq(MerchantWalletWithdrawEntity::getId, withdraw.getId())
        .ne(MerchantWalletWithdrawEntity::getDicWithdrawStatus, TERMINAL_STATUS)
        .update();
// ⑤ 链式删除（全项目唯一）：lambdaUpdate()...remove()
merchantSubAccountStationService.lambdaUpdate()
        .eq(MerchantSubAccountStationEntity::getMerchantId, parentId)
        .eq(MerchantSubAccountStationEntity::getSubMerchantId, subAccountId)
        .remove();
```

**出现位置**：① `admin/BikeStationInfoAppService`（179）、`admin/CarStationInfoAppService`（192）、`admin/BikeStationShareSchemeAppService`（6 处）、`admin/CarStationShareSchemeAppService`（6 处）、`merchant-admin/CarOccupyRecordAppService`、`modules/BikeDevicePortInfoServiceImpl#updatePortStatus`、`modules/UserDiscountInfoServiceImpl#tryRecordUsage`；② `admin/StationBarrierPushConfigRelAppService#saveOrUpdate`（168-172）；③ `modules/BikeOrderRecordServiceImpl`（8 处）、`modules/CarOrderRecordServiceImpl`（10 处）、`modules/CarThirdOrderRecordServiceImpl`（5 处）、`admin/CarInvoiceAppService`（6 处）、`consumer/CarInvoiceTxService`（636/665/672）、`callback/HuifuInvoiceAutoReissueService`（145）；④ `admin/MerchantWalletWithdrawAppService`（9 处）、`admin/UserAppService`（1012/1023）、`consumer/WalletAppService`（233/243/335/368）、`consumer/PrepayAppService`（178/338/399）、`consumer/GiftFundPayService`（4 处）、`merchant/MerchantWalletSettlementAppService`（572/587）、`integration/MerchantBalanceWithdrawalService`（140/181）、`modules/UserPaymentTradeRecordServiceImpl`、`modules/MerchantWalletWithdrawAccountingServiceImpl`（3 处）；⑤ `merchant/SubAccountAppService#doConfigStation`（207-208）。

**测试要点**：`update(entity, wrapper)` 需识别第一参是否 null；`mapper.update` 返回 int、`service.update` 返回 boolean；链式 `.update()`/`.remove()` 终结点；`update(X, wrapper) || save(X)` upsert 模式。

---

### U08 同 wrapper 先 `selectCount` 预检后追加 `set` 复用更新（4 处）

```java
LambdaUpdateWrapper<StoreCouponUserEntity> wrapper = new LambdaUpdateWrapper<>();
wrapper.eq(...).lt(...);
Long count = baseMapper.selectCount(wrapper);
if (count <= 0) { return 0; }
wrapper.set(StoreCouponUserEntity::getDicStatus, STATUS_EXPIRED)   // set 后加
        .set(StoreCouponUserEntity::getUseTime, now);
int updated = baseMapper.update(null, wrapper);
```

**出现位置**：`modules/CouponUserQueryServiceImpl#overdueTask`（106-117）、`modules/UserDiscountInfoServiceImpl#closeExpiredDiscounts`（111-122）、`modules/StoreCouponServiceImpl#batchUpdateActivityStatus`（100-116）、`modules/MerchantWalletRechargeServiceImpl#closeTimedOutRecharges`（37-46，`count(wrapper)`）。

**测试要点**：**转换时不能假设"无 where 即危险"**——wrapper 在运行时才被追加条件与 set；同一 wrapper 先查后更（条件段在静态代码中位于 set 之前）。

---

### U09 `UpdateWrapper`（字符串版）四种形态（7 文件，集中在优惠券/裂变域）

```java
// ① 分步声明 + setSql + 字符串列 eq + last（防超发）
UpdateWrapper<StoreCouponEntity> wrapper = new UpdateWrapper<>();
wrapper.setSql("last_total = last_total - " + num);
wrapper.last("AND (last_total - " + num + " >= 0)");
wrapper.eq("id", id);
update(wrapper);
// ② 内联 + 字符串列 eq + setSql / apply（原子自增 + 上限）
storeCouponService.update(new UpdateWrapper<StoreCouponEntity>()
        .eq("id", couponUser.getCouponId())
        .setSql("use_total = use_total + 1"));
storeCouponService.update(new UpdateWrapper<StoreCouponEntity>()
        .eq("id", coupon.getId())
        .apply("fission_reward_claim_count < fission_reward_total")
        .setSql("fission_reward_claim_count = fission_reward_claim_count + 1"));
// ③ mapper.update(null, new UpdateWrapper<...>())（字符串列）
couponFissionMapper.update(null, new UpdateWrapper<CouponFissionEntity>()
        .eq("share_token", shareToken)
        .setSql("claim_count = claim_count + 1"));
// ④ wrapper.lambda() 动态转换为 lambda 形态（3 处）
UpdateWrapper<StoreCouponEntity> wrapper = new UpdateWrapper<>();
wrapper.lambda().eq(StoreCouponEntity::getId, id).set(StoreCouponEntity::getDicStatus, status);
if (condition) { wrapper.lambda().set(StoreCouponEntity::getDicActivityStatus, activityStatus); }
storeCouponService.update(wrapper);
```

**出现位置**：① `modules/StoreCouponServiceImpl#deduction`（69-77）；② `admin/CouponUserService#useCoupons`（168/176）、`consumer/CouponUserService`（236/244/319/329）、`consumer/CouponFissionService`（86）、`merchant/CouponUserService`（189/197）；③ `consumer/CouponUserService#claimSuccessfully`（462）；④ `admin/CouponAppService#updateStatus`（186-196）`#audit`（213-224）、`merchant/CouponAppService#updateStatus`（163-172）。

**测试要点**：字符串列解析（`eq("id", ...)`）；**全项目字符串版无 `set("col", val)`，全部用 `setSql`**；`wrapper.lambda()` 动态转换需同时支持两种形态；`last("AND ...")` 透传。

---

### U10 手动 CAS 乐观更新（余额快照条件，3 处）

```java
boolean walletUpdated = userWalletService.lambdaUpdate()
        .set(UserWalletEntity::getAvailableBalance, availableAfter)
        .set(UserWalletEntity::getFrozenBalance, frozenAfter)
        .eq(UserWalletEntity::getId, latestWallet.getId())
        .eq(UserWalletEntity::getAvailableBalance, latestWallet.getAvailableBalance())   // 快照值条件
        .eq(UserWalletEntity::getFrozenBalance, latestWallet.getFrozenBalance())
        .update();
```

**出现位置**：`consumer/WalletAppService#xxx`（335-340）、`consumer/PrepayAppService`（178-184/399-405）。

**测试要点**：`eq` 条件值为**读时快照**（变量引用而非常量）；项目未用 `@Version` 乐观锁，此模式为替代方案。

---

### U11 删除方式全集（LambdaDeleteWrapper 0 使用）

```java
service.removeById(id)                                       // 约 26 处
service.removeByIds(ids)                                     // admin/CarStationOccupyInfoAppService#209
service.remove(wrapper)                                      // wrapper 为 LambdaQueryWrapper 或 MPJLambdaWrapper
mapper.delete(wrapper)                                       // modules/StationCouponServiceImpl、bridge/StationThirdExtendServiceImpl
service.lambdaUpdate().eq(...).eq(...).remove()              // 链式删除（唯一 1 处）
```

**出现位置**：`removeById` `admin/AccountAppService`（227）、`admin/BikeDeviceInfoAppService`（155）、`admin/CarStationPriceTaskRecordAppService`（202）、`admin/MenuAppService`（276）、`consumer/UserPlateAppService`（171）、`merchant/SubAccountAppService`（156）、`merchant-admin/SubAccountService`（146）、`infra/UnifiedTaskService`（133）、`modules/CouponTemplateServiceImpl`；`remove(wrapper)` `admin/BikeDeviceInfoAppService`（158，**MPJLambdaWrapper**）、`admin/CarStationInfoAppService#syncStationTags`（481-482）、`admin/MerchantAppService`（144，new LambdaQueryWrapper）、`admin/RoleAppService`（176/179/190/204）、`modules/CarOrderInvoiceLifecycleService`（547）；`mapper.delete(wrapper)` `modules/StationCouponServiceImpl`（25/32）、`bridge/StationThirdExtendServiceImpl`（69）。

**测试要点**：**删除不依赖 LambdaDeleteWrapper**——插件必须支持"查询 wrapper 生成 DELETE"（含 MPJLambdaWrapper 传入 `remove`）；逻辑删除拦截器自动追加 `deleted = 0`。

---

### U12 批量与实体更新（saveBatch / updateBatchById / saveOrUpdate / updateById）

```java
service.saveBatch(list);                                   // 约 20 处
service.updateBatchById(updates);                          // admin/CarStationPriceTaskRecordAppService#329
service.saveOrUpdate(entity);                              // admin/BikeStationPricePeriodAppService#52
service.saveOrUpdateBatch(list);                           // admin/CarStationPricePeriodAppService#124
// 实体更新（update-strategy: always 全局配置下全字段参与 SET）
BeanUtil.copyProperties(dto, entity, CopyOptions.create().setIgnoreNullValue(true));
if (!carDeviceInfoService.updateById(entity)) { throw ...; }
// 分批 in() 更新（避免 SQL 过长）
ListUtil.partition(ids, BATCH_SIZE).forEach(batchIds ->
        shareRecordService.lambdaUpdate().in(...).set(...).update());
```

**出现位置**：`saveBatch` `admin/AccountAppService`（304）、`admin/BikeDeviceInfoAppService`（126/144）、`admin/BikeStationShareSchemeAppService`（119/206）、`admin/CarStationPriceTaskRecordAppService`（297/332/353）、`admin/CouponAppService`（423）、`consumer/CouponUserService`（89）；`updateById` `admin/CarDeviceInfoAppService#update`（197）、`admin/CouponAppService#update`（238）；分批更新 `merchant/MerchantWalletSettlementAppService`（587-591）。

**测试要点**：批量方法 SQL 形态（F-15 批量操作预览语料）；`updateById` 在 `update-strategy: always` 下含 null 列 SET（全局配置影响最终 SQL，见 X11）。

> ✅ **已修复（M19.5，300 测试全绿）**：**单实体形态 `service.save(entity)`**（`BatchOperationConverter` 类注释声称支持 `save(entity)`，但操作注册表 `BATCH_OPERATIONS` 缺失 `save` 条目——注释与实现不一致）与 **`service.getById(id)` 单主键查询**（插件无 SELECT 类实体单查操作类型）——M19.5 补注册 `save` → INSERT 全列模板，新增 `Operation.SELECT`（getById 主键等值 / listByIds/selectByIds 主键 IN）+ `deleteByIds`（P1-02）。修复记录见 `docs/开发计划/20260813-1536-用户实测两类未识别形态修复-开发计划.md`。

---

### U13 `lambdaUpdate()` 链式更新（20 文件，约 87 处，见 U07-④ 代码）

与 U07-④ 同语料。补充代表性：`admin/AuthAppService`、`admin/IcCardInfoAppService`（123/147）、`consumer/WalletAppService`、`consumer/PrepayAppService`、`consumer/CarOccupyAppService#markOccupyPaid`（211）、`callback/HuifuCallbackAppService`（302）、`callback/MerchantCallbackAppService`（74）、`merchant/AccountManagementAppService`、`merchant/GiftFundAppService`、`integration/MerchantBalanceWithdrawalService`、`modules/UserPaymentTradeRecordServiceImpl`（16/26）、`modules/MerchantWalletWithdrawAccountingServiceImpl`（51/89/203）。

**测试要点**：`lambdaUpdate()` 链与 `lambdaQuery()` 链的**方法名归属区分**（同为 `set/eq/...` 链，终结点 `.update()`/`.remove()` vs `.list()`/`.one()`）。

> ✅ **已修复（M19.5，300 测试全绿）**：`boolean updated = service.lambdaUpdate()...update();` **赋值形态**受 `WrapperAnchorDetector` 锚点去重误抑制影响（无 gutter 图标）；M19.5 修复（仅对 Wrapper 变量去重），standalone 形态（终结点直接作为语句）本不受影响。修复记录见 `docs/开发计划/20260813-1536-用户实测两类未识别形态修复-开发计划.md`。

### 4.4 Mapper XML / 注解 / 配置模式（X01~X12）

### X01 XML 动态标签全集（20 个 XML 全量统计）

| 标签 | 次数 | 形态要点 |
|------|:----:|----------|
| `<if test>` | 123 | test 表达式：`xxx != null`、`xxx != null and xxx != ''`、`xxx.size() > 0`、`xxx.size() &gt; 0`（转义与裸 `>` 混用）、`(a!=null and a!='') or (b!=null and b!='')`、`p.xxx` 嵌套对象路径、`dicBizTypes.contains(bizParking)` 集合方法调用 |
| `<foreach>` | 50 | 全部为 IN 列表；**一律 `open="(" separator="," close=")"` + item，无 index 属性**；collection 取值：方法参数名（`stationIds` 32 / `portIds` 4 / `deviceIds` 2 / `dicBizTypes` 1 / `dicChargeTypes` 1 / `tagIds` 1）、嵌套属性（`p.stationIds` 3 / `query.bikeStationIds` 1 / `query.carStationIds` 1） |
| `<bind>` | 110 | **全部**用于把 `@com.xxx.DicConstants@DIC_XXX` 静态常量绑为局部变量再以 `#{name}` 引用（状态字典值） |
| `<include>` / `<sql>` | 20 / 9 | 7 个片段定义（columns 字段列表 ×3、where 片段 ×2、统计列片段 ×1、范围片段 ×1）；`SubAccountStationScope` 被 include 7 次、`OrderCountAggColumns` 6 次 |
| `<choose>/<when>/<otherwise>` | 10 / 14 / 6 | 场站单选 vs 多选二选一；`otherwise` 用 `AND 1=0` 兜底（OrderManagementMapper）；**动态切换 JOIN 分支**（MerchantWalletSettlementDetailMapper 按 keyword 决定 join 哪张表） |
| `<where>` / `<set>` / `<trim>` | 5 / **0** / **0** | 项目不使用 `<set>`/`<trim>`（更新全部走 wrapper / setSql） |
| CDATA | 7 | `<=` `>=`、PostGIS `location <-> pt`、`t.start_time <= #{p.endTime}` |
| XML 注释 | 33 | 含设计说明性长注释（UNION ALL 分页下推、子账户数据隔离 EXISTS 方案说明） |

**代表性 XML 片段（表名列名已占位）**：

```xml
<!-- statistics 风格：bind 常量 + ${} 列别名 + choose 单选/多选 -->
<bind name="orderCount" value="@com.xxx.common.constant.DicConstants@DIC_XXX_ORDER_COUNT"/>
select count(t.id) "${orderCount}",
       TRIM_SCALE(COALESCE(sum(t.charge_degree),0)) "${chargeDegreeSum}"
from xxx_order_record t
<where>
    t.deleted = 0
    <if test="p.startTime != null"> and t.start_time >= #{p.startTime} </if>
    <choose>
        <when test="p.stationId != null"> and t.station_id = #{p.stationId} </when>
        <when test="p.stationIds != null and p.stationIds.size() > 0">
            and t.station_id in
            <foreach collection="p.stationIds" item="sid" open="(" separator="," close=")">#{sid}</foreach>
        </when>
    </choose>
</where>
```

**出现位置**：20 个 XML 全部位于 `cyzc-modules/src/main/resources/mapper/{biz,sys}`（Bike*/Car* 设备、订单、场站、结算、优惠券、共享记录等 20 个 Mapper）。

**测试要点**：OGNL test 子集解析（含 `&gt;` 转义）；foreach 无 index；bind 静态常量引用（`@类@常量` 语法）；`${}` 列别名场景（见 X02）。

---

### X02 `#{}` 与 `${}` 使用面

- `#{}`：约 400+ 次，含 `#{p.xxx}`、`#{query.xxx}`、`#{record.xxx}` 嵌套参数路径；`<bind>` 变量引用。
- `${}`：**22 次，全部出现在 5 个 `statistics` 风格方法**，100% 为"bind 常量 → 结果集列别名"场景（`"${orderCount}"` 等）；**无** order by 字段、表名、列名、in 拼接等任何其他 `${}` 场景；**全项目无 `${ew.customSqlSegment}` / `${ew.sqlSegment}`**（Wrapper 参数注入走 MPJ/MP 内置方法）。

**出现位置**：`BikeOrderRecordMapper.statistics`、`BizCarOccupyRecordMapper.statistics`、`CarOrderRecordMapper.statistics`、`MerchantWalletSettlementMapper.statistics`、`SysMerchantShareRecordMapper.statistics`（最多 8 个 `${}` 别名）。

**测试要点**：`${}` 别名解析需解析 `<bind name=... value="@全限定类名@静态常量"/>` 的 OGNL 静态常量引用链条；插件按"${} = 注入风险"做规则在本项目会 0 命中（仅列别名）。

---

### X03 resultMap 用法（8 个，仅 sys 结算模块）

```xml
<resultMap id="BaseResultMap" type="com.xxx.entity.MerchantWalletSettlementDetailEntity">
    <id column="id" property="id" jdbcType="BIGINT"/>
    <result column="merchant_id" property="merchantId" jdbcType="BIGINT"/>
    <!-- 26 个 result，jdbcType 覆盖 BIGINT/VARCHAR/DATE/SMALLINT/NUMERIC/TIMESTAMP/INTEGER/DECIMAL -->
</resultMap>
<resultMap id="stationAggMap" extends="aggMap" type="com.xxx.bo.XxxBO"/>   <!-- extends 继承 -->
```

**出现位置**：`MerchantWalletSettlementDetailMapper.xml`（5 个：BaseResultMap + deviceAggMap + aggMap + stationAggMap(extends=aggMap) + monthAggMap）、`MerchantWalletSettlementMapper.xml`（3 个：overviewMap + aggMap + monthAggMap）。

**测试要点**：`<id>`+`<result>`（column/property/jdbcType）映射；`extends` 继承；**无 association/collection/constructor/typeHandler**；其余 12 个 XML 全靠 `resultType` + 全局 `map-underscore-to-camel-case: true` 自动驼峰映射。

---

### X04 自定义 SQL 形态（69 条语句）

- **多表 join**：LEFT JOIN / INNER JOIN；最复杂为 MerchantWalletSettlementDetailMapper 的 `<if test="keyword != null"><choose>` **动态选择 JOIN 三表/两表分支**；SysMerchantShareRecordMapper 条件 LEFT JOIN（`<if test="(query.a != null ...) or (query.b != null ...)">`）。
- **子查询**：派生表 + `ROW_NUMBER() OVER (PARTITION BY ...)` 窗口函数取每端口最新（Bike/CarOrderMonitorRecordMapper、Bike/CarOrderRecordMapper#getLatestChargingUserByPortIds）；`GROUP BY MAX(id)` 去重取最新（StationThirdExtendMapper）；**相关子查询 UPDATE**（StoreCouponMapper 3 个 `SET use_total = (SELECT COUNT(*) ...)` 计数器校正）；EXISTS 子查询内嵌 foreach（CarStationInfoMapper#nearBaseWhere）。
- **UNION ALL**：3 处——两订单表聚合（Bike/CarOrderRecordMapper#selectFeeAgg）、**合并分页 + 合并 count（OrderManagementMapper，手写 `LIMIT #{limit} OFFSET #{offset}`，未用 MP 分页）**。
- **聚合**：`COUNT(*) FILTER (WHERE ...)`（PG 方言）、`DATE_TRUNC('month', ...)::DATE`、`EXTRACT(EPOCH FROM ...)`、`TRIM_SCALE`。
- **PostGIS**：`ST_SetSRID`/`ST_MakePoint`/`ST_DWithin`/`location <-> pt` 距离排序（BikeStationInfoMapper、CarStationInfoMapper）。
- **批量 insert/update**：XML 中**无** foreach 批量 insert（批量插入走自定义注入器，见 X09）；**无 useGeneratedKeys/keyProperty、无多语句**。
- **空 XML**：`BizCarMerchantDaySummaryRecordMapper.xml`、`BizCarStationDaySummaryRecordMapper.xml` 仅 namespace 无语句（纯注解/内置方法）。

**测试要点**：动态 JOIN 分支下"表集合"随条件变化；手写 LIMIT/OFFSET 与拦截器分页两种形态并存；CDATA 内 `<=`/`<->` 操作符提取；空 XML 与无 XML 的 Mapper 接口需容错。

---

### X05 Mapper 接口注解 SQL（4 个接口，无 `<script>`）

```java
// @Insert：Java 文本块多行 SQL + ON CONFLICT upsert（31 列全字段）
@Insert("""
        INSERT INTO xxx_business_open_info (col1, col2, ...)
        VALUES (#{record.col1}, #{record.col2}, ...)
        ON CONFLICT (xxx_col) DO UPDATE SET
        col1 = COALESCE(EXCLUDED.col1, xxx_business_open_info.col1), ...
        """)
int insertOrUpdate(@Param("record") XxxEntity record);
// @Update：原子加减 + 防超扣条件（多参）
@Update("UPDATE xxx_wallet SET available_amount = available_amount + #{amount} WHERE merchant_id = #{merchantId}")
int addAmount(@Param("merchantId") Long merchantId, @Param("amount") BigDecimal amount);
// @Delete：定时清理（物理删除）
@Delete("DELETE FROM xxx_log WHERE create_time < #{expireTime}")
int deleteExpired(@Param("expireTime") LocalDateTime expireTime);
```

**出现位置**：`modules/mapper/base/LogCleanupMapper`（@Delete ×4）、`modules/mapper/biz/CarHuifuInvoiceBusinessOpenInfoMapper`（@Insert ×1 upsert，接口 extends **MPJBaseMapper**）、`modules/mapper/sys/MerchantWalletMapper`（@Update ×3 钱包原子加减）、`modules/mapper/sys/UserWalletMapper`（@Insert ×1 `ON CONFLICT DO NOTHING`）。

**测试要点**：注解 SQL + 文本块多行；`ON CONFLICT` upsert；`#{}` 参数引用（`#{record.xxx}` 实体属性路径与 `#{param}` 单参）；**无 @Options/@Results/@SelectKey/`<script>`**。

---

### X06 实体注解盘点（108 个实体，80 个 extends BaseEntity）

```java
@TableName("xxx_table")                       // 103 处显式表名；4 处命名参数（autoResultMap=true ×3 配 JSON typeHandler）
@TableId(type = IdType.AUTO)                  // 28 处全部 AUTO（BaseEntity 上，其余实体继承不重复声明）
@TableField(fill = FieldFill.INSERT)          // 11 处（createTime 类）
@TableField(fill = FieldFill.INSERT_UPDATE)   // 11 处（updateTime 类）
@TableField(typeHandler = JacksonTypeHandler.class)      // 5 处（Object/JSON 字段）
@TableField(typeHandler = PostgreSqlJsonbTypeHandler.class) // 4 处（jsonb 字段，配 autoResultMap）
@TableField(updateStrategy = FieldStrategy.NEVER)         // 2 处（BaseEntity.createTime 等）
@TableField(updateStrategy = FieldStrategy.ALWAYS)        // 2 处（BizTaskRecordEntity 两列）
@TableField(select = false, updateStrategy = FieldStrategy.NOT_NULL) // 3 处（密码/登录密码字段）
@TableField(exist = false)                    // 1 处（聚合标签字段，不对应物理列）
@TableField(value = "\"current\"")            // 1 处（保留字列名，带双引号）
@TableLogic                                 // 1 处（BaseEntity.deleted，Integer）
@Version                                    // 0 处
```

**字段类型分布（全实体）**：String 2802 / Integer 1336 / BigDecimal 1245 / Long 1050 / LocalDateTime 637 / List 197 / LocalDate 48 / Boolean 47 / Map 20 / JSONObject 2；**无枚举字段、无自定义 IdType**。

**BaseEntity 模板**：

```java
@TableId(type = IdType.AUTO) private Long id;
@TableField(fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER) private LocalDateTime createTime;
@TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updateTime;
@TableLogic private Integer deleted;
```

**出现位置**：`cyzc-modules/src/main/java/com/cyzc/platform/modules/entity/**`（含 `entity/base/BaseEntity.java`）；cyzc-api 层无 @TableName 实体。

**测试要点**：`@TableName` 显式表名为主（无 keepGlobalPrefix/schema/excludeProperty）；`@TableId` 全 AUTO；`autoResultMap = true` 命名参数形式；typeHandler（Jackson/jsonb）字段回查；保留字列名带引号（`"current"`）；`exist=false` 非表字段；fill 字段与 MetaObjectHandler（X10）配合。

---

### X07 逻辑删除与全局字段策略

- `@TableLogic` 仅 BaseEntity.deleted（Integer）；全局 `logic-delete-field: deleted`、`logic-delete-value: 1`、`logic-not-delete-value: 0`。
- 更新侧显式 `.eq(Entity::getDeleted, 0)` 约 10 处（发票域批量更新：`modules/CarOrderInvoiceLifecycleService` 6 处、`admin/CarInvoiceAppService` 多处、`consumer/CarInvoiceTxService`、`consumer/UserInvoiceAppService`）。
- **全局 `update-strategy: always`**：`updateById` 时全字段参与 SET（含 null）。

**测试要点**：插件模拟"最终 SQL"需注入 `AND deleted = 0`（查询/删除）与 deleted 值（插入）；XML 手写 `deleted = 0` 与 MP 自动追加并存；updateById 的 SET 列清单受 `update-strategy: always` 影响。

---

### X08 MP 拦截器与配置类（cyzc-infra/cyzc-infra-mybatis，4 个 Java 文件）

```java
@Configuration
@MapperScan("com.cyzc.platform.modules.mapper.**")
public class MyBatisPlusConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));   // 唯一拦截器
        return interceptor;
    }
    @Bean
    public ISqlInjector sqlInjector() { return new BatchInsertSqlInjector(); }
}
```

- **PaginationInnerInterceptor**：`DbType.POSTGRE_SQL`；未设置 maxLimit/overflow（默认不限制）。
- **无** TenantLineInnerInterceptor（无 TenantLineHandler）、OptimisticLockerInnerInterceptor、BlockAttackInnerInterceptor、IllegalSQLInnerInterceptor。
- **@InterceptorIgnore：0 处**；**IdentifierGenerator 自定义：无**；**SqlHelper：0 处**；jsqlparser 仅依赖引入未使用。

**测试要点**：分页拦截器改写 SQL（LIMIT/OFFSET 形态）；无租户拦截器（无需租户列注入）；`@MapperScan` 通配符。

---

### X09 自定义 SqlInjector：批量插入（`insertBatchSomeColumn`）

```java
// BatchInsertSqlInjector extends DefaultSqlInjector：追加 InsertBatchSomeColumnDefaulting
// （field -> field.getFieldFill() != FieldFill.UPDATE 过滤填充字段）
// 运行时字符串拼 <script> 生成批量 INSERT：
// INSERT INTO xxx (%s) VALUES <foreach collection="collection" item="item" separator=",">
//   每值 %s：<choose><when test="item.xxx != null">#{item.xxx}</when><otherwise>DEFAULT</otherwise></choose>
// NoKeyGenerator；保留实体声明的 jdbcType/typeHandler 参数片段（field.getInsertSqlProperty("item.")）
```

**出现位置**：`cyzc-infra/cyzc-infra-mybatis`（`BatchInsertSqlInjector` + `InsertBatchSomeColumnDefaulting extends AbstractMethod`）；使用方：各 `insertBatchSomeColumn(list)` 调用（Mapper extends `BatchInsertBaseMapper`）。

**测试要点**：**批量 INSERT 的列清单在 Java 侧动态生成**（静态 XML 扫描会漏掉）；空字段走 `DEFAULT` 的 `<choose><otherwise>`；依赖 `TableFieldInfo.getInsertSqlProperty` 的 MP 内部格式（版本敏感 3.5.15）。

---

### X10 MetaObjectHandler 自动填充

```java
// insertFill：strictInsertFill "createTime" (LocalDateTime, now 截秒) / "updateTime" / "deleted" (Integer, 0)
// updateFill：setFieldValByName "updateTime" (now)
```

**测试要点**：INSERT 填充 createTime/updateTime/deleted 三字段；UPDATE 自动追加 updateTime——插件生成完整 SQL 时需决定是否模拟填充列（与实际执行 SQL 的差异点）。

---

### X11 全局配置（application*.yaml，dev/test/prod 差异仅 log-impl）

```yaml
mybatis-plus:
  mapper-locations: classpath*:mapper/**/*.xml
  global-config:
    db-config:
      update-strategy: always          # 全字段 SET（含 null）
      id-type: auto
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
  configuration:
    log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl   # dev；test/prod 为 NoLoggingImpl
    map-underscore-to-camel-case: true
    cache-enabled: true
    lazy-loading-enabled: false
```

- 无 `type-aliases-package`（全部全限定名）；无 tenant 配置；数据源为 `spring.datasource.dynamic`。

**测试要点**：`map-underscore-to-camel-case: true` 是列名推断的默认策略（F-13）；`update-strategy: always` 影响 updateById 的 SET 列（F-09）；`id-type: auto` 配合 `@TableId(AUTO)`。

---

### X12 PostgreSQL 方言面（全项目出现，F-14 语料）

| 类别 | 形态 | 位置 |
|------|------|------|
| jsonb | `station_ids @> CAST({0} AS jsonb)`、`<@`、`jsonb_typeof(...) = 'array'` | apply（P10/Q11）、MerchantWalletSettlementDetailMapper |
| FILTER | `COUNT(*) FILTER (WHERE ...)` | BikeDeviceInfoMapper、CarDeviceInfoMapper |
| 时间 | `DATE_TRUNC('month', ...)::DATE`、`DATE(...)::timestamp`、`EXTRACT(EPOCH FROM ...)` | sys 结算 XML、Q03 |
| 类型强转 | `::geography`/`::bigint`、`::timestamp` | BikeStationInfoMapper、Q03 |
| PostGIS | `ST_SetSRID`/`ST_MakePoint`/`ST_DWithin`/`location <-> pt` | BikeStationInfoMapper、CarStationInfoMapper |
| 窗口函数 | `ROW_NUMBER() OVER (PARTITION BY ...)` | 4 个 OrderMonitor/OrderRecord Mapper |
| 元组比较 | `(rel.apply_id, rel.id) > ({0}, {1})` | P10（admin/CarInvoiceAppService） |
| upsert | `ON CONFLICT ... DO UPDATE/NOTHING` | X05 注解 SQL |

**测试要点**：转换器需容忍"非标准 SQL"节点，不能按 MySQL 方言断言；方言差异提示（F-14）。

---

## 5. API 方法覆盖矩阵

> ✅=项目实际使用并给出语料；❌=项目中未出现（插件需自行造用例或用兼容性降级）；P/Q/U/X 为对应模式编号。

### 5.1 MPJ 链式 API（2026-08-13 复核版）

| 分类 | 方法 | 使用 | 代表模式 |
|------|------|:----:|----------|
| 构造 | `new MPJLambdaWrapper<>()` / `<T>()` / `<T>(Class)` / `<T>(Class, alias)` / 全限定名 | ✅ | C1~C5 |
| 列 | `select`（varargs） | ✅ | P04/P20 |
| 列 | `selectAll(Class)` | ✅ | P01/P02 |
| 列 | `selectAs(SFunction, SFunction)` | ✅ | P01/P02/P04 |
| 列 | `selectAs(String, SFunction)`（字符串列/别名列） | ✅ | P11 |
| 列 | `selectCollection(Class, SFunction)` / `(Class, SFunction, map)` | ✅ | P06/P07 |
| 列 | 子映射 `id` / `result` / `collection` | ✅ | P06/P07 |
| 列 | `selectAssociation(Class, SFunction)` | ✅ | P08 |
| 列 | `selectFunc(String, func->…, alias)` | ✅ | P09 |
| 列 | `selectMax` / `selectCount` | ✅ | P18 |
| 列 | `selectMin` / `selectSum` | ❌（SUM 以 selectFunc 形式出现） | P09 |
| 去重 | `distinct` | ✅ | P05 |
| 联表 | `leftJoin`（三参 / 带别名四参 / lambda on） | ✅ | P01/P11/P13 |
| 联表 | `innerJoin`（三参 / 带别名四参） | ✅ | P05/P11/P17/P18 |
| 联表 | `rightJoin` | ❌ | – |
| 条件 | `eq` / `ne` / `gt` / `ge` / `lt` / `le` | ✅ | P01/P02/P14 |
| 条件 | `in` / `notIn` | ✅(in) / ❌(notIn) | P02/P17/P18 |
| 条件 | `like` / `notLike` / `likeLeft` / `likeRight` | ✅(like/likeRight) / ❌(notLike/likeLeft) | P02/P11 |
| 条件 | `isNull` / `isNotNull` | ✅ | P04/P17/P18 |
| 条件 | `between` / `notBetween` | ❌ | – |
| 条件 | `inSql` / `notInSql` / `exists` / `notExists` | ❌ | – |
| 逻辑 | `and(lambda)` / `or(lambda)` / `or()` | ✅ | P13/P14 |
| 逻辑 | `nested` | ❌（以 lambda 嵌套等价实现） | P14 |
| 逻辑 | `func`（裸链式） | ❌（仅作为 selectFunc 参数名） | – |
| 分组 | `groupBy`（多列、含关联表列） | ✅ | P18 |
| 分组 | `having` | ❌ | – |
| 排序 | `orderByAsc` / `orderByDesc`（含 `orderByAsc("alias", …)`） | ✅ | P02/P07/P11 |
| 尾缀 | `last` | ✅ | P12 |
| 片段 | `apply`（含参数化 `{0}`/`{1}`、boolean 重载） | ✅ | P10 |
| 片段 | `set` / `setSql`（MPJ 更新） | ❌（MPJ 联表更新未使用） | – |

### 5.2 原生查询 Wrapper 矩阵（com.baomidou）

| 分类 | 方法 | 使用 | 代表模式 |
|------|------|:----:|----------|
| 构造 | `new LambdaQueryWrapper<>()` / `<T>()` / 内联 / `Wrappers.<T>lambdaQuery()` / 工厂返回 | ✅ | C8~C11 |
| 字符串 | `Wrappers.<T>query()`（QueryWrapper） | ✅（唯一 1 文件） | Q03/Q18 |
| 列 | `select(SFunction...)` / `select(SFunction)` | ✅ | Q02 |
| 列 | `select(String...)`（SQL 表达式/别名） | ✅（QueryWrapper） | Q03 |
| 列 | `select(Class, SFunction...)` / `selectCount` | ❌ | – |
| 条件 | `eq` / `ne` / `gt` / `ge` / `lt` / `le`（含 boolean 重载） | ✅ | Q04 |
| 条件 | `in`（Collection/varargs/boolean/字符串列） | ✅ | Q06 |
| 条件 | `notIn`（含 boolean） | ✅（3 处） | Q07 |
| 条件 | `like` / `likeRight`（含 boolean） | ✅ / ❌(likeLeft/notLike) | Q05 |
| 条件 | `isNull` / `isNotNull` | ✅ | Q07 |
| 条件 | `between`（含 boolean） | ✅ | Q08 |
| 条件 | `inSql` / `notInSql` | ❌ | – |
| 逻辑 | `and(lambda)` / `and(boolean, lambda)` / `or()` / `or(lambda)` | ✅ | Q13 |
| 逻辑 | `nested` / `func` / `not` | ❌ | – |
| 逻辑 | `exists(boolean, String, Object...)` / `exists(Wrapper)` / 链式 `exists()` | ✅（21 处，三形态） | Q14 |
| 分组 | `groupBy`（字符串列） | ✅（1 处） | Q09 |
| 分组 | `having` | ❌ | – |
| 排序 | `orderByAsc` / `orderByDesc` | ✅ | Q10 |
| 排序 | 裸 `orderBy` / 字符串列排序 | ❌ | – |
| 尾缀 | `last` | ✅（59 处） | Q12 |
| 片段 | `apply`（raw / `{0}` / boolean / 短路） | ✅（20 处） | Q11 |
| 消费 | `list` / `getOne` / `getOne(w, false)` / `getOneOpt` / `count` / `page` / `remove` | ✅ | Q15 |
| 消费 | `getById(id)` / `listByIds(ids)` / `selectByIds(ids)`（单主键查询，无 wrapper） | ✅（M19.5：SELECT 主键等值/IN 模板，实体经 service qualifier 泛型解析） | U12 |
| 消费 | `mapper.selectList` / `selectOne` / `selectCount` / `selectPage` / `delete` | ✅ | Q15 |
| 消费 | `selectMaps` / `selectObjs` / `selectMap` / `getMap` / `listMaps` / `listObjs` | ❌ | – |
| 链式 | `lambdaQuery()...one()/.oneOpt()/.list()/.count()/.exists()/.page()/.last()` | ✅（35 文件） | Q17 |
| 分页 | `new Page<>(pageNum, pageSize)`（两参唯一形态，无 searchCount 定制） | ✅ | Q16 |

### 5.3 更新 / 删除 Wrapper 矩阵

| 分类 | 方法 | 使用 | 代表模式 |
|------|------|:----:|----------|
| 构造 | `new LambdaUpdateWrapper<T>()` / `(Entity.class)` / `Wrappers.lambdaUpdate()` | ✅ | U01 |
| 更新 | `set(SFunction, Object)` / `set(boolean, ...)` / `set(col, null)` | ✅ | U02/U03 |
| 更新 | `setSql(String)` / `setSql(String, Object...)`（`{0}`） | ✅ | U04 |
| 更新 | `setEntity(entity)` | ❌ | – |
| 条件 | 更新 wrapper 上的 eq/in/ge/lt/ne/apply/last（同查询面） | ✅ | U05/U06 |
| 消费 | `service.update(wrapper)` / `update(entity, wrapper)` / `mapper.update(null, wrapper)` | ✅ | U07 |
| 消费 | `lambdaUpdate()...update()` / `...remove()` 链式 | ✅（20 文件） | U07/U13 |
| 字符串 | `new UpdateWrapper<>()` + 字符串列 eq + setSql + apply + last + `wrapper.lambda()` 转换 | ✅（7 文件） | U09 |
| 删除 | `removeById` / `removeByIds` / `remove(wrapper)` / `mapper.delete(wrapper)` / `lambdaUpdate().remove()` | ✅（无 LambdaDeleteWrapper） | U11 |
| 批量 | `saveBatch` / `updateBatchById` / `saveOrUpdate` / `saveOrUpdateBatch` / `insertBatchSomeColumn` | ✅ | U12/X09 |
| 实体单条 | `save(entity)`（INSERT） / `getById(id)`（SELECT 主键等值） / `listByIds(ids)` / `selectByIds(ids)`（SELECT 主键 IN） / `deleteByIds(ids)`（DELETE 主键 IN） | ✅（M19.5：`save` 补注册（注释一致化）；新增 Operation.SELECT 模板） | U12 |
| 实体 | `updateById`（`update-strategy: always` 语义） | ✅ | U12 |
| 乐观锁 | `@Version` / OptimisticLockerInnerInterceptor | ✅（T-M22-02，M22：updateById/实体更新/wrapper 更新自动注入 `SET version = version+1` + `AND version = ?`；显式涉及 version 列不重复注入） | U10 |
| 防全表 | `selectCount(wrapper)` 预检后复用追加 set | ✅（4 处） | U08 |

### 5.4 Mapper XML / 注解矩阵

| 类别 | 项 | 使用 | 代表模式 |
|------|------|:----:|----------|
| 动态标签 | `<if>` / `<foreach>` / `<bind>` / `<include>` / `<sql>` / `<choose>/<when>/<otherwise>` / `<where>` | ✅ | X01 |
| 动态标签 | `<set>` / `<trim>` / `<bind>`(非常量) | ❌ | – |
| 参数 | `#{}` 嵌套路径 / `<bind>` 引用 | ✅ | X02 |
| 参数 | `${}` 表名/列名/排序注入 | ❌（仅 bind 列别名 22 次） | X02 |
| 参数 | `${ew.customSqlSegment}` / `${ew.sqlSegment}` | ❌ | – |
| 映射 | resultMap（id/result/jdbcType/extends，无 association/collection） | ✅（8 个） | X03 |
| 映射 | resultType 自动驼峰 | ✅（其余全部） | X03/X11 |
| SQL 形态 | 多表 join / 子查询（ROW_NUMBER、相关子查询）/ UNION ALL / 手写 LIMIT OFFSET / PostGIS / CDATA | ✅ | X04 |
| 注解 | `@Select/@Insert/@Update/@Delete` + `#{}`（无 `<script>`） | ✅（4 接口 10 方法） | X05 |
| 注解 | `@Options` / `@Results` / `@Result` / `@SelectKey` | ❌ | – |
| 实体 | `@TableName`（103 显式表名 + autoResultMap） | ✅ | X06 |
| 实体 | `@TableId(type = AUTO)` 全部 | ✅ | X06 |
| 实体 | `@TableField`（fill/typeHandler/updateStrategy/exist/value 保留字） | ✅ | X06 |
| 实体 | `@TableLogic`（BaseEntity.deleted + 全局配置） | ✅ | X07 |
| 实体 | `@Version` | ✅（T-M22-02，M22） | – |
| 配置 | `PaginationInnerInterceptor(DbType.POSTGRE_SQL)` 唯一拦截器 | ✅ | X08 |
| 配置 | Tenant / OptimisticLocker / BlockAttack / @InterceptorIgnore | ❌ | – |
| 配置 | 自定义 SqlInjector（insertBatchSomeColumn + DEFAULT） | ✅ | X09 |
| 配置 | MetaObjectHandler（insert 3 字段 / update 1 字段） | ✅ | X10 |
| 配置 | 全局 update-strategy: always / id-type: auto / logic-delete / map-underscore | ✅ | X11 |
| 方言 | PostgreSQL（jsonb/FILTER/DATE_TRUNC/PostGIS/ROW_NUMBER/ON CONFLICT/元组比较） | ✅ | X12 |

## 6. 插件兼容性测试建议

### 6.1 与插件功能编号（F-xx）的映射

| 插件功能 | 测试入口（本文档） |
|----------|--------------------|
| F-01 LambdaQueryWrapper 转 SELECT | Q01~Q19 全部 |
| F-02 参数替换（`?` → 实际值） | P09（`%s`/`{0}` 参数）、P10（apply 元组参数）、P14（`new StringBuilder(...).reverse()` 表达式）、P18（in 大集合/`StrUtil.EMPTY`）、Q04（常量/复合表达式）、Q06（in 集合四形态）、Q11（`CAST({0} AS jsonb)`）、U04（`setSql {0}`）、X02（`#{}` 嵌套路径） |
| F-03 SQL 格式化 | P02（多子句）、P06/P07（selectCollection 子查询）、P09（复杂函数列）、U02（15 个 set 长链）、X04（UNION ALL/子查询） |
| F-09 LambdaUpdateWrapper 转 UPDATE | U01~U13 全部 |
| F-10 LambdaDeleteWrapper 转 DELETE | ⚠️ **项目中 0 使用**——真实删除全走 `remove(wrapper)`（含 MPJLambdaWrapper）/`mapper.delete(wrapper)`/`lambdaUpdate().remove()`（U11），插件需支持"查询 wrapper 生成 DELETE"，否则覆盖不到任何真实代码 |
| F-11 MPJLambdaWrapper 转联表 SELECT | P01~P20 全部 |
| F-12 MPJQueryWrapper 字符串版 | ⚠️ **项目中 0 使用**，需自造用例或标记"未在真实项目验证" |
| F-13 表名/列名解析 | X06（@TableName 107/@TableId 28/@TableField 40 含保留字 value `"current"`）、X03（resultMap）、X02（bind 常量链）、X11（map-underscore-to-camel-case 全局策略）、C4/C11 别名 |
| F-14 多数据库方言 | X12（PostgreSQL 方言面全清单）、Q03（`::timestamp`）、Q11（jsonb） |
| F-15 批量操作预览 | U12（saveBatch/updateBatchById/saveOrUpdateBatch）、X09（insertBatchSomeColumn 动态生成） |
| F-16 配置页面 | X11 全局配置项影响生成 SQL（update-strategy: always / logic-delete / id-type） |
| F-17 字符串版 QueryWrapper / UpdateWrapper | Q03/Q18（QueryWrapper 唯一文件）、U09（UpdateWrapper 四种形态含 `wrapper.lambda()` 转换） |
| F-18 apply / last 自定义片段 | P10（MPJ apply 三形态）、P12（MPJ last）、Q11（原生 apply 四形态）、Q12（原生 last 59 处）、U04（setSql）、U05（更新 wrapper 上 apply/last） |
| F-19 命名空间与内联 SQL 注释 | X04（XML 注释 33 处含设计说明）、X01（CDATA） |
| F-20 缓存与性能 | 不涉及（语料面：Q06 大集合 in、X01 复杂 XML） |

### 6.2 高风险 / 边界用例（按优先级测试）

1. **MPJ 与原生 wrapper 类型区分（最高风险）**：44 个文件同文件混用 `MPJLambdaWrapper` 与原生 `LambdaQueryWrapper`/`lambdaQuery()`（如 `merchant/ChargingPortManagementService`、`merchant/OrderManagementService`、`admin/CarInvoiceAppService`、`modules/UserVinServiceImpl` 同方法内两态并存）；文本 token 相似（select/eq/and/last/between/exists/distinct 两库都有），**必须按构造类型与包归属定位**，否则 `between`/`last`/`setSql`/`exists` 极易误归因。
2. **跨方法 Wrapper 追踪**：MPJ 工厂方法 3 级链（P03：`baseWithdrawQueryWrapper→baseWithdrawJoinWrapper→applyWithdrawFilters→list/queryStatistics`）、wrapper 入参就地修改（P16）、wrapper 传另一 Service/模块消费（C7：`modules/CarChargingSummaryDayServiceImpl`、integration 跨模块）、同 wrapper 先 `selectCount` 预检后追加 `set` 更新（U08）——PSI 需解析返回值并跟随续链、跨对象追踪。
3. **boolean 条件重载泛滥**：eq/ne/gt/ge/lt/le/like/likeRight/in/notIn/between/apply/exists/and/set 全部出现 boolean 重载（Q04/Q06/Q08/Q11/Q13/U03），条件表达式形态多样（判空/`StringUtils.hasText`/`&&` 复合/`== null` 取反/`ObjUtil.isAllNotEmpty`/方法返回值/`DicConstants.X.equals(...)`）——**首个布尔参数不得当列名**，位置错误会整条 SQL 语义反转。
4. **`{0}` 参数化与原生片段透传**：apply 元组比较 `(rel.apply_id, rel.id) > ({0}, {1})`（P10）、`CAST({0} AS jsonb)` 上下文类型（Q11）、`setSql("...{0}", val)`（U04）、exists 子查询 `{0}`（Q14）、last 拼接 `"limit 1"` vs `"LIMIT " + N`（P12/Q12）——占位符替换与 `?` 混排参数序。
5. **selectCollection / selectAssociation 复杂形态**：三层嵌套 collection + 简写混用同一链（P07）、双 selectCollection + 多 leftJoin（P06）、`orderByAsc` 引用 join 表列与子表列（P06/P07/P18）。
6. **表别名四件套联动**（P11）：构造别名（`apply_info`）、join 别名（`rel`/`auditAccount`）、字符串列 `selectAs("auditAccount.username", ...)`、`orderByAsc("rel", ...)` 四处一致解析。
7. **字符串列 QueryWrapper / UpdateWrapper**（Q03/Q18/U09）：`select("DATE(create_time)::timestamp as ...")`、`eq("merchant_id", ...)`、`groupBy("DATE(create_time), amount_direction")`、`setSql("use_total = use_total + 1")`、`eq("id", ...)`、`wrapper.lambda()` 动态转换——裸字符串列需兜底"未知列"提示。
8. **嵌套括号与循环 OR**：游标 keyset（P14/Q19 3~4 层 or 嵌套）、for 循环内动态 `or()` 拼接（Q13）、无参 `or()` 语义——括号配对与 OR 优先级最易丢内层括号。
9. **无 where 更新场景判定**：U08 的"先 count 预检后复用 wrapper 追加 set"**不能静态判"全表更新"报错**；但 U02 的定时批量关单（15 set 无主键条件）是**真实多行批量更新**——防护应按"是否有任何条件"而非"是否按主键"。
10. **链式终结点全集**：`lambdaQuery()` 的 `.one()/.oneOpt()/.list()/.count()/.exists()/.page()/.last("LIMIT 1")` 与 `lambdaUpdate()` 的 `.update()/.remove()` 归属区分（Q17/U07/U13）。
11. **`getOne(wrapper, false)` / `getOneOpt`**：二参重载与 Optional 返回识别（Q15）。
12. **结果类型非 VO**：`selectJoinList(Long.class/BigDecimal.class/Entity.class, ...)`（P20）——转换引擎不能假设目标必为 VO；映射到 `DropListVO`（common 层）与全限定名 SFunction 目标（P08）。
13. **selectFunc**（P09）：`%s` 列占位 + `{0}`/`{1}` 参数 + `func.accept(多列).values(多值)`，CASE WHEN/COALESCE 嵌套，10 处密集。
14. **全限定名**：构造无 import（P19）与 SFunction 目标全限定名（P08）两类位置。
15. **PostgreSQL 方言断言**：jsonb/`::`强转/FILTER/DATE_TRUNC/PostGIS `<->`/元组比较/`ON CONFLICT`（X12）——不能按 MySQL 方言解析。
16. **XML 解析链**：`<bind>` 静态常量 OGNL 引用（`@类@常量`）+ `${}` 列别名（X02）、test 表达式 `&gt;` 转义与裸 `>` 混用（X01）、动态 JOIN 分支（X04）、CDATA 内操作符、空 XML 与无 XML 的 Mapper 接口容错（X04）。
17. **逻辑删除与全局策略注入**：查询/删除自动追加 `deleted = 0`、插入填充 deleted/updateTime（X07/X10/X11）；`update-strategy: always` 下 updateById 全字段 SET 含 null。
18. **`update(entity, wrapper) || save(entity)` upsert**（U07-②）与 `mapper.update(null, wrapper)` 的 null 第一参识别。
19. **链式赋值形态锚点（2026-08-13 用户实测新增，M19.5 已修复）**：`XxxEntity e = service.lambdaQuery()...one()`、`long n = ...count()`、`boolean b = service.lambdaUpdate()...update()` 等**结果变量赋值**形态——`WrapperAnchorDetector.isInsideWrapperVariableInitializer` 对**任意变量**初始化一律去重（未检查变量是否 Wrapper 类型），gutter 锚点全灭；现有测试仅覆盖 standalone 形态（`testServiceChainCallAnchors`、`testServiceLambdaQueryChainRendersSelect`）与 locator 路径（count 赋值测试走 `preview` 而非 anchor，未暴露）。修复：仅当变量为 Wrapper 类型（或基类型降级）才去重 + 回归测试三形态（实体/boolean/long 结果变量）。
20. **实体单条 CRUD 识别（2026-08-13 用户实测新增，M19.5 已修复）**：`service.save(entity)`（INSERT 单实体）、`service.getById(id)`（SELECT 主键等值）、`service.listByIds(ids)`（SELECT 主键 IN）——`BatchOperationConverter` 操作注册表缺 `save`（类注释声称支持但 map 未注册，注释与实现不一致）；`getById`/`listByIds` 属新 SELECT 操作类型（主键等值/IN 模板；实体经 service qualifier 泛型解析，T-M15-08 的 removeById 链路已具备）。修复：注册 `save` → INSERT（复用 saveBatch 全列模板）+ 新增 `Operation.SELECT`（getById/listByIds/selectByIds）+ `deleteByIds`（P1-02 同点）+ 锚点自动生效（`batchOperationMethodNames` 同一集合）。

### 6.3 待补充（本清单之外）

- 原生 `LambdaQueryWrapper` 在 modules/infra/integration 层（项目全量 92 文件中的非 app 层 22 文件）的逐文件位置——语法形态与 app 层一致（Q01~Q19 已覆盖），如需逐文件清单可补充。
- 多数据库方言实测 SQL 输出对比（F-14）——需连接真实数据库验证（本项目仅 PostgreSQL）。
- MPJ `rightJoin`/`notIn`/`between`/`having`/`selectJoinPage` 等未使用 API 的插件侧自造用例。
- 原生 `selectMaps/selectObjs/selectMap`、`nested/func/inSql/notInSql`、`setEntity`、`@Version` 等未使用 API 的自造用例。

---

## 7. 附录：源文件清单

> 路径已省略公共前缀 `src/main/java/com/cyzc/platform/`（cyzc-app 各子模块含模块名层级）。

### 7.1 MPJ 使用文件（61）

**app-admin（20）**
`admin/app/service/BikeDeviceInfoAppService.java`、`BikeDeviceProductInfoAppService.java`、`BikeOrderRecordAppService.java`、`BikeStationInfoAppService.java`、`CarDeviceInfoAppService.java`、`CarInvoiceAppService.java`、`CarOccupyRecordAppService.java`、`CarOrderRecordAppService.java`、`CarStationInfoAppService.java`、`CarStationPriceTaskRecordAppService.java`、`GiftFundAppService.java`、`HelpCenterInfoAppService.java`、`HuifuInvoiceAppService.java`、`IcCardInfoAppService.java`、`MerchantRoleAppService.java`、`MerchantShareRecordAppService.java`、`MerchantWalletSettlementAppService.java`、`MerchantWalletWithdrawAppService.java`、`UserAppService.java`、`UserDiscountAppService.java`

**app-merchant（8）**
`merchant/app/controller/UserController.java`、`merchant/app/service/ChargingPortManagementService.java`、`DeviceManagementService.java`、`HomeService.java`、`IcCardInfoAppService.java`、`OrderManagementService.java`、`StationInfoService.java`、`SubAccountAppService.java`（MPJLambdaQueryWrapper）

**app-merchant-admin（11）**
`merchantadmin/app/service/AuthAppService.java`、`BikeDeviceInfoAppService.java`、`BikeOrderRecordAppService.java`、`BikeStationInfoAppService.java`、`CarDeviceInfoAppService.java`、`CarOccupyRecordAppService.java`、`CarOrderRecordAppService.java`、`CarStationInfoAppService.java`、`MerchantShareRecordAppService.java`、`MerchantWalletSettlementAppService.java`、`RoleAppService.java`

**app-consumer（4）**
`consumer/app/service/BikeOrderAppService.java`、`CarDeviceAppService.java`、`CarOccupyAppService.java`、`CarStationAppService.java`

**app-bridge（7，其中 3 个 wrapper 用户 + 4 个仅 StrUtils）**
`bridge/app/service/openapi/impl/CommonThirdPlatformOpenApiServiceImpl.java`、`bridge/app/service/platform/PlatformService.java`、`bridge/app/service/push/business/template/AbstractOperationStatsInfoPushService.java`（全限定名构造）；仅 StrUtils：`bridge/app/service/push/business/impl/AMap*ThirdPlatformBusinessServiceImpl.java`（4 个）

**modules（14）**
`modules/service/impl/biz/BikeDevicePortInfoServiceImpl.java`、`BikeStationShareSchemeMerchantInfoServiceImpl.java`、`BizCarStationOccupyInfoServiceImpl.java`、`BizStationBarrierPushConfigRelServiceImpl.java`、`CarChargingSummaryDayServiceImpl.java`、`CarDeviceInfoServiceImpl.java`、`CarDevicePortInfoServiceImpl.java`、`CarStationPriceTaskRecordServiceImpl.java`、`CarStationShareSchemeMerchantInfoServiceImpl.java`、`IcCardInfoServiceImpl.java`；`modules/service/impl/sys/GiftFundServiceImpl.java`、`MerchantTeamInfoServiceImpl.java`、`SysMerchantShareRecordServiceImpl.java`、`UserVinServiceImpl.java`

**integration（1）**
`integration/service/HuifuInvoiceReloginSmsReminderService.java`

### 7.2 原生 LambdaQueryWrapper / QueryWrapper（app 层 70，全项目 92）

**app-admin（约 33）**
`admin/app/auth/StpInterfaceImpl.java`；`admin/app/service/AccountAppService.java`、`AuthAppService.java`、`BankAppService.java`、`BannerAppService.java`、`BikeDeviceProductInfoAppService.java`、`BikeOrderRecordAppService.java`、`CarDeviceProductInfoAppService.java`、`CarInvoiceAppService.java`、`CarMerchantDaySummaryRecordAppService.java`、`CarOccupyRecordAppService.java`、`CarOrderDaySummaryService.java`、`CarOrderRecordAppService.java`、`CarStationDaySummaryRecordAppService.java`、`CarStationInfoAppService.java`、`CarStationTagAppService.java`、`CouponAppService.java`、`CouponUserService.java`、`DictItemAppService.java`、`DictTypeAppService.java`、`HelpCenterInfoAppService.java`、`HuifuInvoiceAppService.java`、`MenuAppService.java`、`MerchantAppService.java`、`MerchantMenuAppService.java`、`MerchantRoleAppService.java`、`MerchantShareRecordAppService.java`、`MerchantSubAccountAppService.java`、`MerchantWalletWithdrawAppService.java`、`PermissionAppService.java`、`RoleAppService.java`、`UserAppService.java`、`UserDiscountAppService.java`

**app-merchant（14，含唯一 QueryWrapper 文件）**
`merchant/app/controller/CarStationInfoController.java`；`merchant/app/service/AccountManagementAppService.java`（**QueryWrapper**）、`CarInvoiceManagementAppService.java`、`CarOccupyOrderService.java`、`ChargingPortManagementService.java`、`CouponAppService.java`、`CouponUserService.java`、`DeviceManagementService.java`、`DictItemAppService.java`、`GiftFundAppService.java`、`OrderManagementService.java`、`StationInfoManagementService.java`、`StationInfoService.java`、`SubAccountAppService.java`

**app-merchant-admin（9）**
`merchantadmin/app/service/BikeOrderRecordAppService.java`、`CarInvoiceAppService.java`、`CarMerchantDaySummaryRecordAppService.java`、`CarOccupyRecordAppService.java`、`CarOrderRecordAppService.java`、`CarStationDaySummaryRecordAppService.java`、`DictItemAppService.java`、`RoleAppService.java`、`SubAccountService.java`

**app-consumer（13）**
`consumer/app/service/BannerAppService.java`、`BikeDeviceAppService.java`、`BikeOrderAppService.java`、`CarInvoiceTxService.java`、`CarOrderAppService.java`、`CouponFissionService.java`、`CouponUserService.java`、`DictItemAppService.java`、`HelpCenterAppService.java`、`UserDiscountAppService.java`、`UserInvoiceAppService.java`、`UserPlateAppService.java`、`UserVinAppService.java`

**app-callback（2）**
`callback/service/HuifuInvoiceAutoReissueService.java`、`HuifuInvoiceQueryApplyCompensateService.java`

**app-bridge（1）**
`bridge/app/service/station/impl/StationThirdExtendServiceImpl.java`

**modules / infra / integration（约 22，代表性）**
`modules/service/impl/biz/*ServiceImpl.java`（CouponUserQueryServiceImpl、CouponTemplateServiceImpl、UserDiscountInfoServiceImpl、BikeOrderRecordServiceImpl、CarOrderRecordServiceImpl、CarThirdOrderRecordServiceImpl、BikeDeviceInfoServiceImpl、CarDevicePortInfoServiceImpl、BizCarOccupyRecordServiceImpl、StationCouponServiceImpl、StoreCouponServiceImpl、CarOrderInvoiceLifecycleService、UserPlateServiceImpl、UserVinServiceImpl、BikeStationPricePeriodInfoServiceImpl、CarStationPricePeriodInfoServiceImpl 等）；`modules/service/impl/sys/MerchantWalletRechargeServiceImpl.java`、`MerchantRoleRelServiceImpl.java`、`MerchantRoleMenuRelServiceImpl.java`、`MerchantPaymentTradeRecordServiceImpl.java`、`MerchantMenuServiceImpl.java`、`UserVinServiceImpl.java` 等

### 7.3 更新 / 删除 wrapper 文件（LambdaUpdateWrapper 36 + UpdateWrapper 7）

**LambdaUpdateWrapper（36）**
- app-admin（9）：`AuthAppService`、`BikeDeviceProductInfoAppService`、`CarDeviceInfoAppService`、`CarDeviceProductInfoAppService`、`CarInvoiceAppService`、`CarOccupyRecordAppService`、`CouponUserService`、`MenuAppService`、`MerchantMenuAppService`
- app-callback（2）：`HuifuInvoiceAutoReissueService`、`HuifuInvoiceQueryApplyCompensateService`
- app-consumer（9）：`BikeOrderAppService`、`BikeOrderRefundService`、`CarInvoiceTxService`、`CarOrderAppService`、`CarOrderRefundService`、`ConsumerTokenAppService`、`CouponExpiryAsyncService`、`CouponUserService`、`UserInvoiceAppService`
- app-merchant（1）：`CouponUserService`；app-merchant-admin（1）：`CarOccupyRecordAppService`
- integration（4）：`BikeChargingConsumptionService`、`BikeStatusConsumptionService`、`CarChargingConsumptionService`、`CarStatusConsumptionService`
- modules（10）：`biz/BikeDevicePortInfoServiceImpl`、`biz/BikeOrderRecordServiceImpl`、`biz/CarOrderInvoiceLifecycleService`、`biz/CarOrderRecordServiceImpl`、`biz/CarThirdOrderRecordServiceImpl`、`biz/CouponUserQueryServiceImpl`、`biz/StoreCouponServiceImpl`、`biz/UserDiscountInfoServiceImpl`、`sys/MerchantPaymentTradeRecordServiceImpl`、`sys/MerchantWalletRechargeServiceImpl`

**UpdateWrapper（字符串版，7，均在优惠券/裂变域）**
`modules/biz/StoreCouponServiceImpl`、`admin/CouponAppService`、`admin/CouponUserService`、`consumer/CouponFissionService`、`consumer/CouponUserService`、`merchant/CouponAppService`、`merchant/CouponUserService`

**lambdaUpdate() 链（20）**
app-admin（4）：`AuthAppService`、`IcCardInfoAppService`、`MerchantWalletWithdrawAppService`、`UserAppService`；app-callback（2）：`HuifuCallbackAppService`、`MerchantCallbackAppService`；app-consumer（4）：`CarOccupyAppService`、`GiftFundPayService`、`PrepayAppService`、`WalletAppService`；app-merchant（6）：`AccountManagementAppService`、`GiftFundAppService`、`IcCardInfoAppService`、`MerchantWalletRechargeCallbackHandler`、`MerchantWalletSettlementAppService`、`SubAccountAppService`；integration（1）：`MerchantBalanceWithdrawalService`；modules（3）：`biz/UserDiscountInfoServiceImpl`、`sys/MerchantWalletWithdrawAccountingServiceImpl`、`sys/UserPaymentTradeRecordServiceImpl`

**lambdaQuery() 链（35 文件，约 177 处调用）**
app 层各模块 Service（`CarInvoiceManagementAppService`、`AccountManagementAppService`、`MerchantWalletWithdrawAppService`、`UserInvoiceAppService`、`CarInvoiceTxService`、`GiftFundAppService`、`CarStationInfoAppService`、`StationInfoManagementService` 等）+ modules（`CarOrderInvoiceLifecycleService`、`CouponUserQueryServiceImpl`、`CarOrderRecordServiceImpl` 等）+ integration（`MerchantBalanceWithdrawalService` 等）；显式 `LambdaQueryChainWrapper` 类型声明仅 `modules/biz/CarDevicePortInfoServiceImpl`。

### 7.4 Mapper XML（20，位于 `cyzc-modules/src/main/resources/mapper/`）

**biz（17）**：`BikeDeviceInfoMapper.xml`（2 方法）、`BikeDevicePortInfoMapper.xml`（3）、`BikeOrderMonitorRecordMapper.xml`（1）、`BikeOrderRecordMapper.xml`（11）、`BikeStationInfoMapper.xml`（2）、`BizCarMerchantDaySummaryRecordMapper.xml`（**空**）、`BizCarOccupyRecordMapper.xml`（4）、`BizCarStationDaySummaryRecordMapper.xml`（**空**）、`CarDeviceInfoMapper.xml`（1）、`CarDevicePortInfoMapper.xml`（3）、`CarOrderMonitorRecordMapper.xml`（1）、`CarOrderRecordMapper.xml`（11）、`CarStationInfoMapper.xml`（4）、`OrderManagementMapper.xml`（2，UNION ALL 手写分页）、`StationThirdExtendMapper.xml`（2）、`StoreCouponMapper.xml`（3，相关子查询 UPDATE）、`StoreCouponUserMapper.xml`（4）

**sys（3）**：`MerchantWalletSettlementDetailMapper.xml`（7，resultMap ×5 + 动态 JOIN 分支）、`MerchantWalletSettlementMapper.xml`（4，resultMap ×3）、`SysMerchantShareRecordMapper.xml`（4，if ×25 最多 + 8 个 `${}` 别名）

### 7.5 基础设施（cyzc-infra/cyzc-infra-mybatis，4 个 Java 文件）

`config/MyBatisPlusConfig.java`（PaginationInnerInterceptor + SqlInjector）、`handler/MyMetaObjectHandler.java`、`injector/BatchInsertSqlInjector.java`、`injector/method/InsertBatchSomeColumnDefaulting.java`；配置：各环境 `application*.yaml`（mybatis-plus 配置段见 X11）。

---

### 5.6 官方 API 覆盖矩阵（MP 3.5.15 / MPJ 1.5.6 文档全集，2026-08-13 补充）

> 基于官方文档（MP `guides/wrapper`、`guides/data-interface`；MPJ `mybatis-plus-join.github.io`）与 3.5.15/1.5.6 源码核实。
> ✅=插件已支持；🟡=部分支持（形态有限制）；⬜=评估后暂不支持（文档化边界）。

**MP 条件构造器（AbstractWrapper 全集）**：

| 方法 | 状态 | 说明 |
|------|:----:|------|
| `eq/ne/gt/ge/lt/le`（含 boolean 重载） | ✅ | – |
| `between/notBetween` | ✅ | – |
| `like/notLike/likeLeft/likeRight/notLikeLeft/notLikeRight` | ✅ | notLikeLeft/Right 为官方 API 补充 |
| `in/notIn`（Collection/varargs） | ✅ | – |
| `inSql/notInSql` | ✅ | `col IN (sql 片段)` 原样透传 |
| `eqSql/gtSql/geSql/ltSql/leSql` | ✅ | `col op (sql)`（3.4.3.2+/3.5.6） |
| `isNull/isNotNull` | ✅ | – |
| `exists/notExists`（含 `{0}` 参数） | ✅ | – |
| `allEq`（6 重载，Map 参数） | ✅ | T-M21-04（M21）：`Map.of`/`Map.ofEntries` 字面量展开多条 eq、`null2IsNull=true` 且值可判 null → `IS NULL`、变量 Map → 可读占位条件组、boolean 重载 |
| `and(Consumer)` / `or()` / `or(Consumer)` / `nested` / `not(Consumer)` | ✅ | nested/not 为官方 API 补充；lambda 内 or() 已展平双层括号 |
| `apply`（raw/`{0}`/boolean） | ✅ | – |
| `last` / `comment` / `first` | ✅ | 全部支持（M15 增补：rawFirst 前缀 + comment 注释） |
| `groupBy` / `having` | ✅ | P1-06（M19.5）：having 多条件 → AND 组（M1 起仅单条件） |
| `orderByAsc/orderByDesc/orderBy(condition, isAsc, cols)` | ✅ | orderBy 双布尔前缀解析（官方 API 补充） |
| `func(Consumer)` | ✅ | 条件直接展开（官方 API 补充） |
| `select`（varargs/字符串列/List） | ✅ | – |
| `select(Class, Predicate)` / `selectFilter` | 🟡 | T-M21-01（M21）：白/黑名单静态近似（lambda 体字符串字面量启发式）；复杂谓词回退全列——谓词 lambda 不可精确静态求值 |
| `set/setSql/setIncrBy/setDecrBy`（UpdateWrapper） | ✅ | setIncrBy/DecrBy 为官方 API 补充 |
| `setEntity(entity)` | ✅ | T-M21-05（M21）：实体全列 `= ?` 近似（运行时字段值不可得） |
| `lambda()` 转换 | ✅ | T-M15-02 |
| `eqOrIsNull` | ✅ | T-M20-09（M20）：3.5.17 新增——值静态可判 null → `IS NULL`，否则 `= ?`（boolean 重载同） |

**链式调用（ChainWrapper）**：

| API | 状态 | 说明 |
|-----|:----:|------|
| `service.lambdaQuery()/lambdaUpdate()` | ✅ | T-M15-01 |
| `service.query()/update()`（字符串链） | ✅ | P1-03（M19.5）：终结点同 lambda 链（字符串列链）；`isServiceChainStart` 守卫区分 `service.update(wrapper)` 消费调用 |
| 终结点 `one/oneOpt/list/count/exists/page/remove/update` | ✅ | – |
| 终结点 `first/mapOne/mapFirst/mapList/mapPage` | ✅ | 官方 API 补充（链式剥离） |
| 链上 `update(T entity)` | ✅ | P1-04（M19.5）：实体字段展开 SET（复用 updateEntityConsumed 分支） |
| `ChainWrappers` 静态工厂 | ✅ | P1-05（M19.5）：queryChain/lambdaQueryChain/lambdaUpdateChain/lambdaDeleteChain/updateChain/deleteChain + 锚点 |

**IService / BaseMapper 消费方法**：

| 类别 | 状态 | 说明 |
|------|:----:|------|
| `selectList/selectOne/selectCount/selectPage/selectMaps/selectObjs/selectMapsPage` | ✅ | 均渲染 SELECT（消费方法名不改变 SQL 形态） |
| `getOne/getOne(w,false)/getOneOpt/list/page/count/exists/remove/update` | ✅ | – |
| `removeById(s)/removeByMap/deleteById(s)/delete(wrapper)` | ✅ | T-M15-08 + M10 |
| `update(entity, wrapper)` 双参 | ✅ | T-M15-04 实体字段展开 |
| `saveBatch/updateBatchById/saveOrUpdate(Batch)/insertBatchSomeColumn` | ✅ | T-M15-08 |
| `selectByIds/deleteByIds`（3.5.9+ 新名） | ✅ | P1-02（M19.5）：与 removeByIds 同族；selectByIds 并入 Operation.SELECT（与 getById/listByIds 同模板） |
| ResultHandler 流式重载 | ✅ | T-M20-06（M20）：selectList/selectMaps/selectObjs(w, rh) 消费识别，SQL 形态同无 handler 版本（第二参忽略） |

**MPJLambdaWrapper**：

| API | 状态 | 说明 |
|-----|:----:|------|
| `select(SFunction.../String...)` | ✅ | 字符串列 1.4.x+ |
| `selectAll()` / `selectAll(Class)` / `selectAll(Class, prefix)` / `selectAll(Class, exclude...)` | ✅ | exclude/prefix 为官方 API 补充 |
| `selectAs`（4 形态含字符串列/index） | ✅ | – |
| `selectAsClass(Class, Class)` | ✅ | 属性名交集映射（官方 API 补充） |
| `selectSum/Max/Min/Avg/Len/Count`（含别名） | ✅ | selectLen → LENGTH |
| `selectFunc`（String 模板） | ✅ | `%s`/`{0}` 占位 |
| `selectFunc(BaseFuncEnum)` 8 形态 | ✅ | T-M21-06（M21）：枚举 → 内置函数模板映射（SUM/COUNT/MAX/MIN/AVG/LEN→LENGTH/CONCAT；未知枚举忽略） |
| `selectSub`（子查询列） | ✅ | `(SELECT ...) AS alias` 子查询列（M15 增补；子查询内列对列比较近似无别名） |
| `selectCollection/selectAssociation`（5 形态） | ✅ | T-M21-03（M21）：prefix 别名形态子查询渲染；free 形态（无子表类）按文档化边界不产生子查询（结果映射类调用） |
| `distinct` | ✅ | – |
| `leftJoin/innerJoin/rightJoin/fullJoin`（20 重载） | ✅ | T-M21-02（M21）：4/5 参 rightAlias、子表 MConsumer（1.5.2+，`LEFT JOIN (SELECT ...) alias ON ...`）、ext MFunction（ON 追加条件）已实现 |
| `join(String keyWord, ...)` | ✅ | 自定义关键字（官方 API 补充） |
| 字符串 SQL join（`leftJoin("sql", args)`） | ✅ | `{i}` 参数替换（官方 API 补充） |
| `applyFunc` | ✅ | `%s` 列 + `{0}` 参数（官方 API 补充） |
| `eqIfExists` 等 IfExists 条件族 | ✅ | 归一化为普通条件（官方 API 补充；T-M21-09：inIfNotEmpty/notInIfNotEmpty 后缀归一） |
| 子查询条件（`eq/ne/gt/ge/lt/le/in/notIn(col, Class, wrapper)`） | ✅ | 独立解析器渲染子查询（M15 增补） |
| `setAlias` | ✅ | 主表别名方法（M15 增补，优先级：构造别名 > setAlias > 默认 t） |
| `union/unionAll` | ✅ | `UNION (子查询)`（M15 增补） |
| `from(子查询主表)` | ✅ | T-M21-07（M21）：`FROM (SELECT ...) 主别名`（子查询 setAlias/条件/LIMIT 全支持，对照官方结构） |
| `disableLogicDel` / `enableLogicDel` / `disableSubLogicDel` / `enableSubLogicDel` / `logicDelToOn` / `logicDelToWhere` | ✅ | T-M20-05（M20）：六方法按源码顺序求值——主表/副表独立开关，logicDelToOn/ToWhere 控制副表 deleted 位置（ON / WHERE 末尾），副表默认 ON 注入（`alias.deleted = 0`） |
| `around`（1.4.11+） | ✅ | T-M21-09（M21）：对照源码核实为 `around(condition, firstSql, lastSql)` 双段形态——firstSql 置 SQL 起始、lastSql 渲染于 ORDER BY 后 LIMIT 前 |
| `setTableName(Function)` / `pageByMain()` | ✅ | T-M21-09（M21）：运行时语义静态不可求值 → 文档化 no-op（预览不受影响） |
| `fill` 关联填充 | ⬜ | 评估中（M22） |
| Chain 消费 `one(Class)/list(Class)/page(P, Class)/first/map*` | ✅ | – |

**MPJ 其他**：

| API | 状态 | 说明 |
|-----|:----:|------|
| `MPJQueryWrapper`（字符串字段版） | ✅ | T-M15-02 注册 QUERY 转换器 |
| `MPJLambdaQueryWrapper` | ✅ | P17 |
| `selectJoinList/One/ListPage/Count` | ✅ | – |
| `selectJoinMap(s)/selectJoinPage/selectJoinMapsPage` | ✅ | 渲染同为 SELECT（实参锚点定位 + 默认 SELECT 消费） |
| `deleteJoin` / `updateJoin` / `updateJoinAndNull`（消费） | ✅ | deleteJoin(wrapper)→DELETE；updateJoin(entity, wrapper)→实体字段展开 SET（M15 增补） |
| `UpdateJoinWrapper/DeleteJoinWrapper` | ✅ | T-M21-08（M21）：直接构造 + JoinWrappers.update/delete 工厂 + UpdateJoinConverter/DeleteJoinConverter（`UPDATE t LEFT JOIN t1 SET ... WHERE` / `DELETE t,t1 FROM ...`；delete(Class...)/deleteAll()/setUpdateEntity(AndNull)/updateAndNull 终结点）；全逻辑删除转 UPDATE 为文档化边界 |
| `selectJoinOnePage` | ⬜ | 官方确认不存在此 API |

**已评估暂不支持的边界（⬜）**：fill 关联填充（M22）、`select(Class, Predicate)` 谓词**精确**求值（静态近似已交付，🟡）、`selectJoinOnePage`（官方不存在）。M20/M21 已交付项见上方矩阵对应行与《全量 API 预览支持开发计划》（docs/开发计划/20260813-1730-全量API预览支持-开发计划.md）——原《低频复杂 API 后期实现计划》P2 全部 + P3-01 已提前完成，P3 剩余（fill/Kotlin 侧/Db·SimpleQuery·ActiveRecord/XML customSqlSegment/拦截器模拟）排 M22 按需立项。

---

## 8. 变更记录

> **全量变更记录已统一迁移至 [`docs/变更记录.md`](../变更记录.md)（M23.9 起，唯一权威位置）**。
> 本 §8 保留章节占位以兼容历史引用（此前交付记录均写"清单 §8"）；此后交付记录只追加变更记录总册。
