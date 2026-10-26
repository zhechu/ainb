package cn.enlev.ainb.module.infra.enums;

import cn.enlev.ainb.framework.common.exception.ErrorCode;

/**
 * Infra 错误码枚举类
 *
 * infra 系统，使用 1-001-000-000 段
 */
public interface ErrorCodeConstants {

    // ========== 参数配置 1-001-000-000 ==========
    ErrorCode CONFIG_NOT_EXISTS = new ErrorCode(1_001_000_001, "参数配置不存在");
    ErrorCode CONFIG_KEY_DUPLICATE = new ErrorCode(1_001_000_002, "参数配置 key 重复");
    ErrorCode CONFIG_CAN_NOT_DELETE_SYSTEM_TYPE = new ErrorCode(1_001_000_003, "不能删除类型为系统内置的参数配置");
    ErrorCode CONFIG_GET_VALUE_ERROR_IF_VISIBLE = new ErrorCode(1_001_000_004, "获取参数配置失败，原因：不允许获取不可见配置");

    // ========== 定时任务 1-001-001-000 ==========
    ErrorCode JOB_NOT_EXISTS = new ErrorCode(1_001_001_000, "定时任务不存在");
    ErrorCode JOB_HANDLER_EXISTS = new ErrorCode(1_001_001_001, "定时任务的处理器已经存在");
    ErrorCode JOB_CHANGE_STATUS_INVALID = new ErrorCode(1_001_001_002, "只允许修改为开启或者关闭状态");
    ErrorCode JOB_CHANGE_STATUS_EQUALS = new ErrorCode(1_001_001_003, "定时任务已经处于该状态，无需修改");
    ErrorCode JOB_UPDATE_ONLY_NORMAL_STATUS = new ErrorCode(1_001_001_004, "只有开启状态的任务，才可以修改");
    ErrorCode JOB_CRON_EXPRESSION_VALID = new ErrorCode(1_001_001_005, "CRON 表达式不正确");
    ErrorCode JOB_HANDLER_BEAN_NOT_EXISTS = new ErrorCode(1_001_001_006, "定时任务的处理器 Bean 不存在，注意 Bean 默认首字母小写");
    ErrorCode JOB_HANDLER_BEAN_TYPE_ERROR = new ErrorCode(1_001_001_007, "定时任务的处理器 Bean 类型不正确，未实现 JobHandler 接口");

    // ========== API 错误日志 1-001-002-000 ==========
    ErrorCode API_ERROR_LOG_NOT_FOUND = new ErrorCode(1_001_002_000, "API 错误日志不存在");
    ErrorCode API_ERROR_LOG_PROCESSED = new ErrorCode(1_001_002_001, "API 错误日志已处理");

    // ========= 文件相关 1-001-003-000 =================
    ErrorCode FILE_PATH_EXISTS = new ErrorCode(1_001_003_000, "文件路径已存在");
    ErrorCode FILE_NOT_EXISTS = new ErrorCode(1_001_003_001, "文件不存在");
    ErrorCode FILE_IS_EMPTY = new ErrorCode(1_001_003_002, "文件为空");
    ErrorCode FILE_PATH_INVALID = new ErrorCode(1_001_003_003, "文件路径不正确");

    // ========== 代码生成器 1-001-004-000 ==========
    ErrorCode CODEGEN_TABLE_EXISTS = new ErrorCode(1_001_004_002, "表定义已经存在");
    ErrorCode CODEGEN_IMPORT_TABLE_NULL = new ErrorCode(1_001_004_001, "导入的表不存在");
    ErrorCode CODEGEN_IMPORT_COLUMNS_NULL = new ErrorCode(1_001_004_003, "导入的字段不存在");
    ErrorCode CODEGEN_TABLE_NOT_EXISTS = new ErrorCode(1_001_004_004, "表定义不存在");
    ErrorCode CODEGEN_COLUMN_NOT_EXISTS = new ErrorCode(1_001_004_005, "字段义不存在");
    ErrorCode CODEGEN_SYNC_COLUMNS_NULL = new ErrorCode(1_001_004_006, "同步的字段不存在");
    ErrorCode CODEGEN_SYNC_NONE_CHANGE = new ErrorCode(1_001_004_007, "同步失败，不存在改变");
    ErrorCode CODEGEN_TABLE_INFO_TABLE_COMMENT_IS_NULL = new ErrorCode(1_001_004_008, "数据库的表注释未填写");
    ErrorCode CODEGEN_TABLE_INFO_COLUMN_COMMENT_IS_NULL = new ErrorCode(1_001_004_009, "数据库的表字段({})注释未填写");
    ErrorCode CODEGEN_MASTER_TABLE_NOT_EXISTS = new ErrorCode(1_001_004_010, "主表(id={})定义不存在，请检查");
    ErrorCode CODEGEN_SUB_COLUMN_NOT_EXISTS = new ErrorCode(1_001_004_011, "子表的字段(id={})不存在，请检查");
    ErrorCode CODEGEN_MASTER_GENERATION_FAIL_NO_SUB_TABLE = new ErrorCode(1_001_004_012, "主表生成代码失败，原因：它没有子表");
    ErrorCode CODEGEN_MASTER_TABLE_NAME_DUPLICATE = new ErrorCode(1_001_004_013,
            "主子表规范化类名({})重复，请调整主表或子表类名");
    ErrorCode CODEGEN_MASTER_TABLE_FIELD_DUPLICATE = new ErrorCode(1_001_004_014,
            "主子表属性名({})重复，请调整主表字段、子表类名或关联关系");

    // ========== 文件配置 1-001-006-000 ==========
    ErrorCode FILE_CONFIG_NOT_EXISTS = new ErrorCode(1_001_006_000, "文件配置不存在");
    ErrorCode FILE_CONFIG_DELETE_FAIL_MASTER = new ErrorCode(1_001_006_001, "该文件配置不允许删除，原因：它是主配置，删除会导致无法上传文件");

    // ========== 数据源配置 1-001-007-000 ==========
    ErrorCode DATA_SOURCE_CONFIG_NOT_EXISTS = new ErrorCode(1_001_007_000, "数据源配置不存在");
    ErrorCode DATA_SOURCE_CONFIG_NOT_OK = new ErrorCode(1_001_007_001, "数据源配置不正确，无法进行连接");

    // ========== 学生 1-001-201-000 ==========
    ErrorCode DEMO01_CONTACT_NOT_EXISTS = new ErrorCode(1_001_201_000, "示例联系人不存在");
    ErrorCode DEMO02_CATEGORY_NOT_EXISTS = new ErrorCode(1_001_201_001, "示例分类不存在");
    ErrorCode DEMO02_CATEGORY_EXITS_CHILDREN = new ErrorCode(1_001_201_002, "存在存在子示例分类，无法删除");
    ErrorCode DEMO02_CATEGORY_PARENT_NOT_EXITS = new ErrorCode(1_001_201_003,"父级示例分类不存在");
    ErrorCode DEMO02_CATEGORY_PARENT_ERROR = new ErrorCode(1_001_201_004, "不能设置自己为父示例分类");
    ErrorCode DEMO02_CATEGORY_NAME_DUPLICATE = new ErrorCode(1_001_201_005, "已经存在该名字的示例分类");
    ErrorCode DEMO02_CATEGORY_PARENT_IS_CHILD = new ErrorCode(1_001_201_006, "不能设置自己的子示例分类为父示例分类");
    ErrorCode DEMO03_STUDENT_NOT_EXISTS = new ErrorCode(1_001_201_007, "学生不存在");
    ErrorCode DEMO03_COURSE_NOT_EXISTS = new ErrorCode(1_001_201_008, "学生课程不存在");
    ErrorCode DEMO03_GRADE_NOT_EXISTS = new ErrorCode(1_001_201_009, "学生班级不存在");
    ErrorCode DEMO03_GRADE_EXISTS = new ErrorCode(1_001_201_010, "学生班级已存在");

    // ========== 数据模型 1-001-202-000 ==========
    ErrorCode DATA_MODEL_NOT_EXISTS = new ErrorCode(1_001_202_000, "数据模型不存在");
    ErrorCode DATA_MODEL_TABLE_EXISTS = new ErrorCode(1_001_202_001, "表({})已存在，请更换表名");
    ErrorCode DATA_MODEL_TABLE_NOT_EXISTS = new ErrorCode(1_001_202_002, "表({})不存在");
    ErrorCode DATA_MODEL_TABLE_NAME_INVALID = new ErrorCode(1_001_202_003, "表名({})不合法：只允许字母、数字、下划线，且以字母或下划线开头");
    ErrorCode DATA_MODEL_TABLE_NAME_KEYWORD = new ErrorCode(1_001_202_004, "表名({})是数据库关键字，请更换");
    ErrorCode DATA_MODEL_COLUMN_NAME_INVALID = new ErrorCode(1_001_202_005, "字段名({})不合法：只允许字母、数字、下划线，且以字母或下划线开头");
    ErrorCode DATA_MODEL_COLUMN_NAME_KEYWORD = new ErrorCode(1_001_202_006, "字段名({})是数据库关键字，请更换");
    ErrorCode DATA_MODEL_COLUMN_NAME_DUPLICATE = new ErrorCode(1_001_202_007, "字段名({})重复");
    ErrorCode DATA_MODEL_COLUMN_EMPTY = new ErrorCode(1_001_202_008, "至少需要一个字段");
    ErrorCode DATA_MODEL_PRIMARY_KEY_REQUIRED = new ErrorCode(1_001_202_009, "必须且只能指定一个主键");
    ErrorCode DATA_MODEL_COLUMN_TYPE_INVALID = new ErrorCode(1_001_202_010, "字段({})的类型({})不支持");
    ErrorCode DATA_MODEL_COLUMN_LENGTH_INVALID = new ErrorCode(1_001_202_011, "字段({})的长度配置不合法");
    ErrorCode DATA_MODEL_TABLE_NOT_MANAGED = new ErrorCode(1_001_202_012, "表({})不在数据模型管理范围内，禁止操作");
    ErrorCode DATA_MODEL_TABLE_IS_FRAMEWORK = new ErrorCode(1_001_202_013, "表({})是系统内置表，禁止通过数据模型修改");
    ErrorCode DATA_MODEL_DDL_EXECUTE_FAIL = new ErrorCode(1_001_202_014, "DDL 执行失败：{}");
    ErrorCode DATA_MODEL_DROP_COLUMN_HAS_DATA = new ErrorCode(1_001_202_015, "字段({})所在表存在 {} 行数据，删除字段会丢失数据；确认请勾选「我已知晓风险」");
    ErrorCode DATA_MODEL_DROP_TABLE_HAS_DATA = new ErrorCode(1_001_202_016, "表({})存在 {} 行数据，删除表会丢失全部数据；确认请勾选「我已知晓风险」");
    ErrorCode DATA_MODEL_PRIMARY_KEY_CHANGE_DENY = new ErrorCode(1_001_202_017, "不支持修改主键字段({})，请重建表");
    ErrorCode DATA_MODEL_DATA_SOURCE_NOT_MYSQL = new ErrorCode(1_001_202_018, "数据模型当前仅支持 MySQL，该数据源类型为({})");
    ErrorCode DATA_MODEL_DATA_NOT_EXISTS = new ErrorCode(1_001_202_019, "数据不存在");
    ErrorCode DATA_MODEL_OPERATOR_INVALID = new ErrorCode(1_001_202_020, "查询操作符({})不支持");
    ErrorCode DATA_MODEL_CONDITION_VALUE_INVALID = new ErrorCode(1_001_202_021, "查询条件({})的值不合法：{}");
    ErrorCode DATA_MODEL_INDEX_NAME_INVALID = new ErrorCode(1_001_202_022, "索引名({})不合法：只允许字母、数字、下划线，且以字母或下划线开头");
    ErrorCode DATA_MODEL_INDEX_NAME_DUPLICATE = new ErrorCode(1_001_202_023, "索引名({})重复");
    ErrorCode DATA_MODEL_INDEX_COLUMN_EMPTY = new ErrorCode(1_001_202_024, "索引({})至少需要指定一个列");
    ErrorCode DATA_MODEL_INDEX_COLUMN_INVALID = new ErrorCode(1_001_202_025, "索引({})的列({})不存在于字段列表中");
    ErrorCode DATA_MODEL_COLUMN_COMMENT_EMPTY = new ErrorCode(1_001_202_026, "字段({})的描述不能为空");
    ErrorCode DATA_MODEL_SYSTEM_COLUMN_DROP_DENY = new ErrorCode(1_001_202_027, "系统字段({})不允许删除。改表是全量同步：提交的字段列表里没有的列会被删掉，请先调「获取表结构」取回全部字段（含系统字段），在其上增删改后整体提交");
    ErrorCode DATA_MODEL_COLUMN_DEFAULT_INVALID = new ErrorCode(1_001_202_028, "字段({})的默认值「{}」不合法：{}类型只能填数字");
    // ========== 以下为建模规范类校验（P0b），均只作用于新建路径，导入存量表不受限 ==========
    ErrorCode DATA_MODEL_TABLE_NAME_NOT_SNAKE_CASE = new ErrorCode(1_001_202_029, "表名({})不符合命名规范：必须全小写，由字母、数字、下划线组成，以字母开头、以字母或数字结尾");
    ErrorCode DATA_MODEL_COLUMN_NAME_NOT_SNAKE_CASE = new ErrorCode(1_001_202_030, "字段名({})不符合命名规范：必须全小写，由字母、数字、下划线组成，以字母开头、以字母或数字结尾");
    ErrorCode DATA_MODEL_TABLE_PREFIX_REQUIRED = new ErrorCode(1_001_202_031, "表名({})必须以「{}」开头，用于与框架表、第三方表区分");
    ErrorCode DATA_MODEL_INDEX_NAME_PREFIX_INVALID = new ErrorCode(1_001_202_032, "索引名({})不符合规范：唯一索引须以 uk_ 开头，普通索引须以 idx_ 开头");
    ErrorCode DATA_MODEL_TABLE_COMMENT_INVALID = new ErrorCode(1_001_202_033, "表描述({})不合法：至少 2 个字符，且不能是纯数字或纯标点");
    ErrorCode DATA_MODEL_COLUMN_COMMENT_INVALID = new ErrorCode(1_001_202_034, "字段({})的描述({})不合法：至少 2 个字符、不能与字段名相同、不能是纯数字或纯标点");
    ErrorCode DATA_MODEL_COLUMN_COUNT_EXCEED = new ErrorCode(1_001_202_035, "字段数({})超过上限({})，字段过多通常说明该拆表了");
    ErrorCode DATA_MODEL_TABLE_COUNT_EXCEED = new ErrorCode(1_001_202_036, "数据模型数量已达上限({})，请先清理不用的模型");
    // 🔴 物理表已存在、但没有登记进数据模型（典型来源：删模型时选了「保留真实表」）。
    //    这种情况原先与「已登记」共用 DATA_MODEL_TABLE_EXISTS，提示是「请更换表名」——
    //    那是错的建议：换名会白白多出一张结构相同的表，而原表还在那里。
    //    对 AI 尤其糟糕，它会照着提示去建 biz_xxx_2。
    ErrorCode DATA_MODEL_TABLE_EXISTS_UNREGISTERED = new ErrorCode(1_001_202_037,
            "表({})已存在于数据库中，但尚未登记进数据模型。请用「导入已有表」把它登记进来直接复用，不要改名新建 —— 改名会多出一张结构相同的表");
    // 🔴 这几类在 MySQL 里不能直接做键，但**两种类型报的是不同的错、补救方向也不同**（均经真库实测，
    //    见 DataModelMysqlIntegrationTest#testJsonColumnEndToEnd）：
    //    · TEXT / LONG_TEXT → 1170「BLOB/TEXT column 'x' used in key specification without a key length」。
    //      带前缀长度（KEY idx (col(255))）**能**绕开，只是 MysqlDdlBuilder#buildIndexDefinition 从不生成
    //      前缀长度，主键更无从给长度 —— 所以在平台的 DDL 方言里不可做键。
    //    · JSON → 3152「JSON column 'x' supports indexing only via generated columns on a specified
    //      JSON path.」。前缀长度**绕不开**（实测 KEY (j(255)) 照样 3152），唯一出路是生成列 + JSON path，
    //      而 TableColumnModel 与 DDL 生成都表达不了生成列 —— 所以是彻底的不可做键。
    //    提前拦的理由与 DATA_MODEL_INDEX_COLUMN_INVALID 同一条：MySQL 报错时 CREATE TABLE
    //    可能已经执行了一半，而 DdlExecutor 只取 ex.getMessage()，用户拿到的是 Spring 那句
    //    「bad SQL grammar [SQL]」—— 半张表，加一句连类型名都不含的包装话。
    ErrorCode DATA_MODEL_COLUMN_NOT_KEYABLE = new ErrorCode(1_001_202_038,
            "字段({})的类型({})不能用作主键或索引列：MySQL 不允许对它直接建键（TEXT/LONG_TEXT 报 1170，JSON 报 3152），请改用 STRING/INT/LONG/DATETIME 等可建键的类型");
    // 🔴 B4-2：结构指纹对不上，说明「你看到的那张表」与「现在这张表」不是同一张。
    //    ALTER 照样能执行成功（diff 是拿实时结构算的），但登记在这张模型上的
    //    columnMeta / listConfig / formConfig、以及绑到它上面的表单字段映射，
    //    描述的已经是另一张表了 —— 正是「接口返回成功、配置却悄悄失效」那一类。
    //    出路是重新读一次结构（读的时候会把新指纹带回来），在其上重做修改再提交。
    ErrorCode DATA_MODEL_SCHEMA_DRIFT = new ErrorCode(1_001_202_039,
            "表({})的结构与你手里那份不一致，本次改表已中止、未执行任何 DDL（基线 {} ≠ 实测 {}）。"
                    + "这通常是有人绕过数据模型直接改了库（DBA 手工执行 SQL、迁移脚本、其它工具），"
                    + "或者另一个窗口刚改过这张表。"
                    + "请重新打开改表页面取回最新结构，在其上重做本次修改再提交 —— "
                    + "按你手里那份过期的结构生成 DDL，改出来的表和你以为的不是同一张");
    // 🔴 B4-3：删列一律要显式确认，**不看表里有没有数据**。
    //    此前空表删列是静默放行的（validateLossyChange 只在 rows > 0 时拦），
    //    而改表是全量同步语义 —— 「只想加一个字段」的直觉写法（只提交那个新字段）
    //    等于把其它所有业务列都删掉。空表上没有数据可丢，所以没有任何东西会报错，
    //    表结构被清成一列、接口返回成功。DDL 不可回滚。
    //    与 DATA_MODEL_DROP_COLUMN_HAS_DATA 分开而不是复用：那句的补救是「勾选我已知晓风险」，
    //    这句的补救是「先确认你确实要删这些列」，指引不同；共用一句会让人照着错的那条走。
    ErrorCode DATA_MODEL_DROP_COLUMN_NEED_CONFIRM = new ErrorCode(1_001_202_040,
            "本次改表会删除字段：{}。删除后无法回滚，请确认后重试（confirmDropColumns=true）。"
                    + "⚠️ 改表是【全量同步】语义 —— 提交的字段列表里没有的列一律会被删掉，"
                    + "如果你只是想加字段，请先调「获取表结构」取回全部字段，在其上增改后整体提交");
    // 🔴 B4-4：记录级乐观锁。名字里刻意带 RECORD ——
    //    表单那边已有 FORM_DESIGN_VERSION_CONFLICT(1_001_204_067)，说的是「表单绑定的业务数据行」
    //    的乐观锁（TableBinding.versionColumn），与 infra_form_design 自己这一行是两回事。
    //    同名会让用户看到「版本冲突」时根本分不清是表单定义撞了还是单据数据撞了。
    //    ⚠️ MyBatis-Plus 的拦截器在冲突时【不抛异常】，只是让 UPDATE 命中 0 行 ——
    //    所以必须由调用方检查影响行数后抛这一条，否则并发覆盖是静默的。
    ErrorCode DATA_MODEL_RECORD_VERSION_CONFLICT = new ErrorCode(1_001_202_041,
            "这个数据模型在你编辑期间已被他人修改，你的修改没有保存。请刷新后在最新数据上重新修改 —— "
                    + "直接覆盖会把对方的改动悄悄抹掉");
    // 🔴 绊线：更新对象没带 version ⟹ MP 的拦截器【静默跳过】、根本不加版本条件
    //    （3.5.16 的 doOptimisticLocker 取到 null 就直接 return），乐观锁等于没开，
    //    而且【不报错】。拦截器提供了 setException 能把这种情况变成失败，
    //    但那是单例级、跨全仓的开关，故改在这里按路径显式判、用本模块自己的错误码报。
    ErrorCode DATA_MODEL_RECORD_VERSION_REQUIRED = new ErrorCode(1_001_202_042,
            "本次更新没有带上版本号，已拒绝执行：缺少它乐观锁就形同没开（MyBatis-Plus 会静默跳过版本条件），"
                    + "并发覆盖会照旧发生且不报错。请先读回这条记录、把 version 一并带上再提交");

    // ========== 数据接口 1-001-203-000 ==========
    ErrorCode DATA_INTERFACE_NOT_EXISTS = new ErrorCode(1_001_203_000, "数据接口不存在");
    ErrorCode DATA_INTERFACE_CODE_DUPLICATE = new ErrorCode(1_001_203_001, "接口编码({})已存在");
    ErrorCode DATA_INTERFACE_DISABLED = new ErrorCode(1_001_203_002, "接口({})已停用");
    ErrorCode DATA_INTERFACE_TYPE_INVALID = new ErrorCode(1_001_203_003, "接口类型({})不支持");
    ErrorCode DATA_INTERFACE_SQL_EMPTY = new ErrorCode(1_001_203_004, "SQL 不能为空");
    ErrorCode DATA_INTERFACE_SQL_PARSE_FAIL = new ErrorCode(1_001_203_005, "SQL 解析失败：{}");
    ErrorCode DATA_INTERFACE_SQL_TENANT_REWRITE_FAIL = new ErrorCode(1_001_203_006, "无法为该 SQL 注入租户条件，拒绝执行：{}");
    ErrorCode DATA_INTERFACE_SQL_MULTIPLE_STATEMENTS = new ErrorCode(1_001_203_007, "不支持多条语句，请拆分成多个接口");
    ErrorCode DATA_INTERFACE_SQL_WRITE_NEED_PERMISSION = new ErrorCode(1_001_203_008, "该 SQL 含写操作({})，需要「接口管理 - 配置写操作SQL」权限");
    ErrorCode DATA_INTERFACE_SQL_WRITE_NEED_CONFIRM = new ErrorCode(1_001_203_009, "该 SQL 含写操作({})，请勾选「我知晓此接口会修改数据」");
    ErrorCode DATA_INTERFACE_SQL_FRAMEWORK_TABLE_DENY = new ErrorCode(1_001_203_010, "禁止对系统内置表({})执行写操作");
    ErrorCode DATA_INTERFACE_SQL_DDL_DENY = new ErrorCode(1_001_203_021, "数据接口不允许执行 DDL 语句（建表/改表/删表），请改用数据模型功能");
    ErrorCode DATA_INTERFACE_SQL_STATEMENT_NOT_SUPPORTED = new ErrorCode(1_001_203_022, "不支持的 SQL 语句类型：{}");
    ErrorCode DATA_INTERFACE_PARAM_REQUIRED = new ErrorCode(1_001_203_011, "参数({})不能为空");
    ErrorCode DATA_INTERFACE_PARAM_TYPE_INVALID = new ErrorCode(1_001_203_012, "参数({})的类型不合法，应为({})");
    ErrorCode DATA_INTERFACE_PARAM_NOT_DEFINED = new ErrorCode(1_001_203_013, "SQL 中使用的参数({})未定义");
    ErrorCode DATA_INTERFACE_EXECUTE_FAIL = new ErrorCode(1_001_203_014, "接口执行失败：{}");
    ErrorCode DATA_INTERFACE_DATA_SOURCE_NOT_EXISTS = new ErrorCode(1_001_203_015, "数据源配置不存在");
    ErrorCode DATA_INTERFACE_VERSION_NOT_EXISTS = new ErrorCode(1_001_203_016, "接口版本不存在");
    ErrorCode DATA_INTERFACE_HTTP_URL_INVALID = new ErrorCode(1_001_203_017, "HTTP 地址不合法：{}");
    ErrorCode DATA_INTERFACE_HTTP_URL_DENY = new ErrorCode(1_001_203_018, "禁止访问内网地址({})");
    ErrorCode DATA_INTERFACE_SCRIPT_EXECUTE_FAIL = new ErrorCode(1_001_203_019, "后处理脚本执行失败：{}");
    ErrorCode DATA_INTERFACE_SCRIPT_TIMEOUT = new ErrorCode(1_001_203_020, "后处理脚本执行超时");
    ErrorCode DATA_INTERFACE_SQL_MISSING_LIMIT = new ErrorCode(1_001_203_030, "SQL 缺少 LIMIT：{}");
    ErrorCode DATA_INTERFACE_READ_ONLY_REQUIRED = new ErrorCode(1_001_203_023, "接口({})不是查询型接口，不能用于运行时取数");
    ErrorCode DATA_INTERFACE_STATIC_JSON_INVALID = new ErrorCode(1_001_203_031, "静态数据不是合法的 JSON");
    ErrorCode DATA_INTERFACE_HTTP_REQUEST_FAIL = new ErrorCode(1_001_203_024, "HTTP 请求失败：{}");
    ErrorCode DATA_INTERFACE_HTTP_RESPONSE_TOO_LARGE = new ErrorCode(1_001_203_025, "HTTP 响应内容过大");
    ErrorCode DATA_INTERFACE_STATEMENT_TYPE_REQUIRED = new ErrorCode(1_001_203_026, "SQL 类型的接口必须选择动作类型（查询/新增/修改/删除）");
    ErrorCode DATA_INTERFACE_STATEMENT_TYPE_INVALID = new ErrorCode(1_001_203_027, "动作类型({})不合法，只能是 SELECT/INSERT/UPDATE/DELETE");
    ErrorCode DATA_INTERFACE_STATEMENT_TYPE_MISMATCH = new ErrorCode(1_001_203_028, "动作类型选的是「查询」，但 SQL 中包含 {} 语句，请改选对应的动作类型");
    ErrorCode DATA_INTERFACE_PAGE_ONLY_FOR_SELECT = new ErrorCode(1_001_203_029, "只有动作类型为「查询」的接口才能开启分页");
    ErrorCode DATA_INTERFACE_PREVIEW_WRITE_NEED_PERMISSION = new ErrorCode(1_001_203_032, "接口({})含写操作，在线测试需要「接口管理 - 配置写操作SQL」权限");
    ErrorCode DATA_INTERFACE_HTTP_CONFIG_INVALID = new ErrorCode(1_001_203_033, "HTTP 转发配置不合法：{}");

    // ========== 表单设计 1-001-204-000 ==========
    ErrorCode FORM_DESIGN_NOT_EXISTS = new ErrorCode(1_001_204_000, "表单设计不存在");
    ErrorCode FORM_DESIGN_CODE_DUPLICATE = new ErrorCode(1_001_204_001, "表单编码({})已存在");
    ErrorCode FORM_DESIGN_CODE_CANNOT_UPDATE = new ErrorCode(1_001_204_002, "表单已发布，不允许修改表单编码");
    ErrorCode FORM_DESIGN_VERSION_NOT_EXISTS = new ErrorCode(1_001_204_003, "表单版本不存在");
    ErrorCode FORM_DESIGN_NOT_PUBLISHED = new ErrorCode(1_001_204_004, "表单尚未发布，无法执行该操作");
    ErrorCode FORM_DESIGN_DELETE_FAIL_BIND_MENU = new ErrorCode(1_001_204_005,
            "表单已发布并关联菜单，不能直接删除；请先执行「下线」回收菜单后再删除");

    // 发布校验：只绑定已有表，表结构由 DBA 掌控，因此发布时必须真去核实元数据
    ErrorCode FORM_DESIGN_FIELDS_EMPTY = new ErrorCode(1_001_204_010, "表单字段不能为空");
    ErrorCode FORM_DESIGN_MAIN_TABLE_REQUIRED = new ErrorCode(1_001_204_011, "请先绑定主表");
    ErrorCode FORM_DESIGN_TABLE_NOT_EXISTS = new ErrorCode(1_001_204_012, "表({})在数据源中不存在，可能已被删除或改名");
    ErrorCode FORM_DESIGN_COLUMN_NOT_EXISTS = new ErrorCode(1_001_204_013, "表({})中不存在列({})，可能已被删除或改名");
    ErrorCode FORM_DESIGN_LIST_COLUMNS_EMPTY = new ErrorCode(1_001_204_014, "表单+列表类型必须配置列表列");
    ErrorCode FORM_DESIGN_LIST_FIELD_UNKNOWN = new ErrorCode(1_001_204_015, "列表引用的字段({})在表单字段与关联列中都不存在");
    ErrorCode FORM_DESIGN_DATA_SOURCE_NOT_EXISTS = new ErrorCode(1_001_204_016, "数据源配置不存在");
    ErrorCode FORM_DESIGN_SUB_TABLE_CROSS_DATA_SOURCE = new ErrorCode(1_001_204_017, "子表({})与主表不在同一数据源，无法保证事务一致性");
    // 雪花 ID 是 19 位 Long，主键列若是 int 会溢出。必须发布时拦住，否则要等到运行时插入才报错
    ErrorCode FORM_DESIGN_PK_TYPE_MISMATCH = new ErrorCode(1_001_204_018, "主键策略选了「{}」，但主键列({}.{})的类型是 {}，无法容纳该策略生成的值，请改用 {} 类型的列或更换主键策略");
    // 系统自动填充与表单字段映射同时指向一列，会生成 INSERT INTO t (creator, creator) 这种非法 SQL
    ErrorCode FORM_DESIGN_AUTO_FILL_COLUMN_CONFLICT = new ErrorCode(1_001_204_019, "列({})已配置为系统自动填充，不能同时在表单字段映射中使用。请把该列的填充方式改为「表单手工输入」，或移除表单中的映射");

    // 安全：禁止把框架表绑定为可写主表，防止绕过业务校验直改系统表
    ErrorCode FORM_DESIGN_FRAMEWORK_TABLE_DENY = new ErrorCode(1_001_204_020, "禁止将系统内置表({})绑定为可写表");
    ErrorCode FORM_DESIGN_IDENTIFIER_INVALID = new ErrorCode(1_001_204_021, "非法的表名或列名：{}");
    ErrorCode FORM_DESIGN_OPERATOR_INVALID = new ErrorCode(1_001_204_022, "不支持的查询操作符：{}");

    // 多表绑定校验：错配会在运行时插入/查询才暴露，必须发布时拦住
    ErrorCode FORM_DESIGN_FK_TYPE_MISMATCH = new ErrorCode(1_001_204_023, "从表({}.{})的外键类型是 {}，与主表主键({})的类型 {} 不兼容，无法存放主表主键值");
    ErrorCode FORM_DESIGN_TABLE_DUPLICATE_CATEGORY = new ErrorCode(1_001_204_024, "表({})同时出现在多个分类中（副表/子表/关联表），请只保留一处");
    ErrorCode FORM_DESIGN_SELF_REFERENCE_DENY = new ErrorCode(1_001_204_025, "主表({})不能同时作为副表或明细表。⚠️ 这两类是【要往那张表写数据】的，而那张表就是主表 —— 一次提交会对同一张表发两条互不知情的写入。若只是想显示上级记录的字段（部门树、物料 BOM），请改用「只读关联」并起一个别名");
    ErrorCode FORM_DESIGN_FK_REQUIRED = new ErrorCode(1_001_204_026, "从表({})必须配置外键列，它是与主表关联的唯一纽带");
    ErrorCode FORM_DESIGN_SUB_TABLE_FIELD_REQUIRED = new ErrorCode(1_001_204_027, "子表({})必须指定表单中对应的子表控件字段，否则提交时找不到明细数据");
    ErrorCode FORM_DESIGN_SUB_TABLE_MAPPING_EMPTY = new ErrorCode(1_001_204_028, "子表({})没有配置任何字段映射，明细的业务字段既存不进去也查不出来。请在「表单设计」步骤展开子表控件({})，为其中的字段选择物理列");
    ErrorCode FORM_DESIGN_SUB_TABLE_WIDGET_MISSING = new ErrorCode(1_001_204_029, "表单中找不到 field 为({})的子表控件，子表({})的配置不会生效。请检查子表控件的字段名，或删除该子表配置");
    ErrorCode FORM_DESIGN_SUB_TABLE_WIDGET_UNBOUND = new ErrorCode(1_001_204_033, "表单里的子表控件({})没有配置对应的明细表，提交的明细数据会丢失。请在数据绑定步骤添加一对多明细表，并把「子表控件字段」填成它");
    ErrorCode FORM_DESIGN_PROTECTED_COLUMN_MAPPED = new ErrorCode(1_001_204_032, "列({})由后端按登录上下文维护，不允许被表单字段映射。表单提交的值不能决定这一列，否则会造成跨租户数据越权");
    ErrorCode FORM_DESIGN_DICT_SOURCE_NOT_EXISTS = new ErrorCode(1_001_204_034, "字段({})引用的数据字典({})不存在。字典被删除后，该字段的选项会变成空白且没有任何报错提示");
    ErrorCode FORM_DESIGN_DICT_SOURCE_NOT_ENABLE = new ErrorCode(1_001_204_035, "字段({})引用的数据字典({})已停用，请改选其它字典或重新启用它");
    ErrorCode FORM_DESIGN_DICT_SOURCE_IS_GROUP = new ErrorCode(1_001_204_036, "字段({})引用的({})是字典分组，分组本身不挂字典数据，请改选一个具体的字典");
    ErrorCode FORM_DESIGN_DICT_SOURCE_LAZY = new ErrorCode(1_001_204_037, "字段({})引用的数据字典({})是懒加载模式，不会下发到前端缓存，无法作为选项数据源。请把该字典改为全量下发，或改选其它字典");
    ErrorCode FORM_DESIGN_WIDGET_UNSUPPORTED = new ErrorCode(1_001_204_038, "表单里的控件({})不被本模块支持，它的数据无法正确落库。请改用「{}」，或删除该控件");
    ErrorCode FORM_DESIGN_WIDGET_UNKNOWN = new ErrorCode(1_001_204_100, "表单里出现了未登记的控件类型({})。控件契约里没有它，运行时渲染不出来也存不进库。若这是新增控件，请先把它登记进 form-widget-contracts.json");
    ErrorCode FORM_DESIGN_FIELD_KEY_DUPLICATE = new ErrorCode(1_001_204_101, "字段标识({})重复了，共出现 {} 次。字段映射按 field 组织，重复会让后写的覆盖先写的 —— 填了两个不同的值，入库只剩一个，且不报错");
    ErrorCode FORM_DESIGN_REQUIRED_MISSING = new ErrorCode(1_001_204_041, "以下字段是必填的，请填写后再提交：{}");
    ErrorCode FORM_DESIGN_LOGIC_CONFLICT = new ErrorCode(1_001_204_042, "字段({})被联动规则同时设为「必填」和「不可填写」，没有任何途径填写它。请修改该表单的联动规则");
    ErrorCode FORM_DESIGN_EXTENSION_REJECTED = new ErrorCode(1_001_204_043, "{}");
    ErrorCode FORM_DESIGN_EXTENSION_MISSING = new ErrorCode(1_001_204_044, "表单的扩展模式是「服务端扩展点」，但没有找到 formCode 为({})的 FormDesignExtension 实现。请实现并注册该扩展点，或把扩展模式改为其它值");
    ErrorCode FORM_DESIGN_LOGIC_RULE_CYCLE = new ErrorCode(1_001_204_045, "联动规则存在环形赋值：{}。这会让规则反复互相触发而算不出稳定结果，请打断其中一环");
    ErrorCode FORM_DESIGN_LOGIC_RULE_FIELD_NOT_EXISTS = new ErrorCode(1_001_204_107, "联动规则({})引用了不存在的字段({})。运行时这条规则会被静默跳过，界面上看不出任何异常");
    ErrorCode FORM_DESIGN_FORMULA_FIELD_MISSING = new ErrorCode(1_001_204_046, "公式字段({})引用了不存在的字段({})。该公式会永远算出空值，且不会有任何报错");
    ErrorCode FORM_DESIGN_FORMULA_CYCLE = new ErrorCode(1_001_204_047, "公式字段循环引用：{}。这会让计算顺序不确定，同一份数据两次提交可能算出不同结果");
    ErrorCode FORM_DESIGN_RELATION_FORM_NOT_EXISTS = new ErrorCode(1_001_204_048, "关联字段({})指向的表单({})不存在。运行时选不出任何数据，界面上只是一个点开没有内容的下拉框");
    ErrorCode FORM_DESIGN_RELATION_FORM_NOT_PUBLISHED = new ErrorCode(1_001_204_049, "关联字段({})指向的表单「{}」还没有发布，没有可查询的数据。请先发布它");
    ErrorCode FORM_DESIGN_RELATION_SELF_REFERENCE = new ErrorCode(1_001_204_050, "关联字段({})不能关联本表单自己，那会在带出字段时形成环");
    ErrorCode FORM_DESIGN_RELATION_TARGET_FIELD_MISSING = new ErrorCode(1_001_204_051, "关联字段({})的带出目标字段({})在本表单里不存在，带出的值无处可放");
    ErrorCode FORM_DESIGN_RELATION_CYCLE = new ErrorCode(1_001_204_054, "关联字段({})形成了循环关联：{}。自引用能在发布时拦住，但绕一圈回来的环拦不住 —— 环一旦形成，带出的值会在两张表单之间来回指");
    ErrorCode FORM_DESIGN_SYSTEM_VALUE_NO_AUTO_FILL = new ErrorCode(1_001_204_052, "系统值字段({})的来源是({})，但数据绑定里没有配置对应的自动填充列。请在「数据绑定」步骤配置它，或去掉该字段的列映射（纯展示不需要映射）");
    ErrorCode FORM_DESIGN_SYSTEM_VALUE_COLUMN_MISMATCH = new ErrorCode(1_001_204_053, "系统值字段({})映射到了列({})，但它的来源对应的自动填充列是({})。映射到别的列，这个字段显示的就不是它声称的那个值");
    ErrorCode FORM_DESIGN_BUSINESS_KEY_DUPLICATE = new ErrorCode(1_001_204_056, "{}");
    ErrorCode FORM_DESIGN_VALIDATION_FAILED = new ErrorCode(1_001_204_057, "{}");
    ErrorCode FORM_DESIGN_VALIDATE_PATTERN_NOT_EXISTS = new ErrorCode(1_001_204_058, "字段({})引用的内置正则({})不存在。运行时会跳过这条校验，格式校验形同没配");
    ErrorCode FORM_DESIGN_VALIDATE_REGEX_INVALID = new ErrorCode(1_001_204_059, "字段({})的自定义正则语法错误：{}。语法错的正则编译不出来，运行时会放过所有输入");
    ErrorCode FORM_DESIGN_VALIDATE_RULE_INCOMPLETE = new ErrorCode(1_001_204_060, "跨字段校验规则({})缺少校验条件或错误提示。缺条件则这条规则恒定通过，缺提示则用户被拦下时看不到原因");
    ErrorCode FORM_DESIGN_VALIDATE_RULE_FIELD_NOT_EXISTS = new ErrorCode(1_001_204_061, "跨字段校验规则({})引用了不存在的字段({})。该字段的值永远取不到，规则的判定结果不可预期");
    ErrorCode FORM_DESIGN_UNIQUE_VIOLATION = new ErrorCode(1_001_204_062, "以下内容在系统里已存在，不能重复：{}");
    ErrorCode FORM_DESIGN_LINK_URL_UNSAFE = new ErrorCode(1_001_204_063, "超链接({})的地址使用了不被允许的协议：{}。只允许 http/https/mailto/tel —— javascript: 与 data: 会在用户点击时执行任意脚本");
    ErrorCode FORM_DESIGN_FILE_TRANSFER_FAIL = new ErrorCode(1_001_204_064, "字段({})里的图片保存失败，本次提交已中止：{}。继续下去只能把图片的 base64 原文写进数据库 —— 它既存不进普通字符串列，也会让列表查询与导出异常");
    ErrorCode FORM_DESIGN_FILE_COLUMN_TOO_SHORT = new ErrorCode(1_001_204_065, "字段({})存放的是文件地址，但映射到的列({}.{})长度只有 {}，放不下一个文件 URL（至少需要 {}）。请改映射到更长的列或 text 列");
    ErrorCode FORM_DESIGN_VALUE_SEPARATOR_CONFLICT = new ErrorCode(1_001_204_066, "字段({})的选项值里含有分隔符「{}」。这类多值字段落库时用它拼成一串，值里再出现它会让读回来的项数比存进去的多，且不会有任何报错。请改掉该选项的值");
    ErrorCode FORM_DESIGN_VERSION_CONFLICT = new ErrorCode(1_001_204_067, "这条数据在你编辑期间已被他人修改，你的修改没有保存。请刷新后在最新数据上重新修改 —— 直接覆盖会把对方的改动悄悄抹掉");
    ErrorCode FORM_DESIGN_VERSION_MISSING = new ErrorCode(1_001_204_068, "本次提交缺少版本号({})，无法判断数据是否已被他人修改。请重新打开该记录再提交");
    ErrorCode FORM_DESIGN_VERSION_COLUMN_NOT_EXISTS = new ErrorCode(1_001_204_069, "启用了乐观锁，但主表({})里没有版本列({})。缺了它，更新时的版本条件无从附加，并发覆盖照旧发生");
    ErrorCode FORM_DESIGN_VERSION_COLUMN_NOT_INT = new ErrorCode(1_001_204_070, "乐观锁的版本列({}.{})类型是 {}，必须是整数列。它靠「列 = 列 + 1」自增，非整数列上这条表达式的结果不可预期");
    ErrorCode FORM_DESIGN_VERSION_COLUMN_MAPPED = new ErrorCode(1_001_204_071, "乐观锁的版本列({})被表单字段({})映射了。版本号由后端自增维护，让表单值也去写它，两者会互相覆盖 —— 请去掉该字段的列映射");
    ErrorCode FORM_DESIGN_BUTTON_KEY_REQUIRED = new ErrorCode(1_001_204_072, "自定义按钮({})没有填标识。前端按标识做列表 key，缺了它按钮的渲染顺序不稳定");
    ErrorCode FORM_DESIGN_BUTTON_KEY_DUPLICATE = new ErrorCode(1_001_204_073, "自定义按钮的标识({})重复了。前端按它做列表 key，重复会让渲染错位 —— 删掉一个按钮，界面上消失的可能是另一个");
    ErrorCode FORM_DESIGN_BUTTON_POSITION_INVALID = new ErrorCode(1_001_204_074, "自定义按钮({})的位置({})不被支持。只能是 TOOLBAR（工具栏）/ ROW（列表行内）/ DETAIL（详情）—— 填别的值按钮根本画不出来，且不报错");
    ErrorCode FORM_DESIGN_BUTTON_ACTION_INVALID = new ErrorCode(1_001_204_075, "自定义按钮({})的动作类型({})不被支持。当前只支持 NAVIGATE（跳转）—— 填别的值按钮点了没反应，且不报错");
    ErrorCode FORM_DESIGN_BUTTON_URL_REQUIRED = new ErrorCode(1_001_204_076, "自定义按钮({})是跳转动作，但没有填地址。点了不会有任何反应");
    ErrorCode FORM_DESIGN_TAB_KEY_REQUIRED = new ErrorCode(1_001_204_077, "详情页页签({})没有填标识。前端按标识做页签的 name，缺了它切换行为不可预期");
    ErrorCode FORM_DESIGN_TAB_KEY_DUPLICATE = new ErrorCode(1_001_204_078, "详情页页签的标识({})重复了。前端按它做页签的 name，重复会让切换错乱 —— 点第二个页签，显示的是第三个的内容");
    ErrorCode FORM_DESIGN_TAB_TYPE_INVALID = new ErrorCode(1_001_204_079, "详情页页签({})的类型({})不被支持。当前只支持 CHANGE_LOG（变更记录）—— 填别的值页签会画出来但里面什么都没有");
    ErrorCode FORM_DESIGN_TAB_CHANGE_LOG_DISABLED = new ErrorCode(1_001_204_080, "详情页配了「{}」页签，但这张表单没有启用数据变更日志，页签会永远是空的。请在数据绑定步骤打开「记录变更日志」，或删掉该页签");
    ErrorCode FORM_DESIGN_PRINT_FIELD_NOT_EXISTS = new ErrorCode(1_001_204_081, "打印模板里的占位符「{}」找不到对应字段。它不会报错，只会在打印出来的单据上留下一片空白。可用的是主表字段、主键与审计字段；子表明细不能用占位符（它是多行数据，插不进一个位置）");
    ErrorCode FORM_DESIGN_VALUE_TOO_LONG = new ErrorCode(1_001_204_082, "{}");
    ErrorCode FORM_DESIGN_BUTTON_NOT_EXISTS = new ErrorCode(1_001_204_083, "这张表单上没有标识为「{}」的按钮。⚠️ 接口编码只能从已发布的表单配置里读出来 —— 这条限制是故意的，否则任何对某张表单有权限的人都能调任意写型数据接口");
    ErrorCode FORM_DESIGN_BUTTON_NOT_INVOKE = new ErrorCode(1_001_204_084, "按钮「{}」不是「调数据接口」动作，不能用这个端点执行");
    ErrorCode FORM_DESIGN_BUTTON_INTERFACE_REQUIRED = new ErrorCode(1_001_204_085, "自定义按钮({})是「调数据接口」动作，但没有选接口。点了不会有任何反应");
    ErrorCode FORM_DESIGN_BUTTON_FIELD_FORBIDDEN = new ErrorCode(1_001_204_086, "按钮入参用到的字段({})你没有查看权限，无法执行。⚠️ 这一条不能改成「传空值继续」：接口那头若是 WHERE 条件用到它，入参为空可能让条件失效、命中全表");
    ErrorCode FORM_DESIGN_BUTTON_INTERFACE_NOT_EXISTS = new ErrorCode(1_001_204_108, "自定义按钮({})引用的数据接口({})不存在或已停用。按钮点下去只会报错，而用户不知道是接口被删了");
    ErrorCode FORM_DESIGN_BUTTON_PARAM_INVALID = new ErrorCode(1_001_204_087, "自定义按钮({})的入参映射不完整：参数名与取值字段都必须填。缺一个的话那个参数会静默传空");
    ErrorCode FORM_DESIGN_BUTTON_PARAM_FIELD_NOT_EXISTS = new ErrorCode(1_001_204_088, "自定义按钮({})的入参映射引用了不存在的字段({})。它不会报错，只会给接口传一个空值");
    ErrorCode FORM_DESIGN_TAB_RELATION_INCOMPLETE = new ErrorCode(1_001_204_089, "详情页页签「{}」是关联数据列表，但没配齐「目标表单」与「关联字段」。缺任何一个，页签里都只会是一片空白");
    ErrorCode FORM_DESIGN_TAB_RELATION_FORM_INVALID = new ErrorCode(1_001_204_090, "详情页页签「{}」引用的目标表单({})不存在或尚未发布。运行时调的是那张表单的分页接口，没发布就调不通");
    ErrorCode FORM_DESIGN_TAB_RELATION_FIELD_NOT_EXISTS = new ErrorCode(1_001_204_091, "详情页页签「{}」的关联字段({})不在目标表单({})上。⚠️ 这一条尤其要拦：运行时对认不出的字段是【静默忽略】，于是页签里会列出目标表单的全部数据，看起来像是把别人的单据也带出来了");
    ErrorCode FORM_DESIGN_SELF_JOIN_ALIAS_REQUIRED = new ErrorCode(1_001_204_092, "关联表({})就是主表本身（自关联），必须给它起一个别名。不起别名的话 SQL 会变成 FROM t LEFT JOIN t，数据库直接报「Not unique table/alias」");
    ErrorCode FORM_DESIGN_SELF_JOIN_ALIAS_CONFLICT = new ErrorCode(1_001_204_093, "自关联的别名({})与主表名相同，等于没起别名。换一个，比如 parent");
    ErrorCode FORM_DESIGN_SELF_JOIN_ON_NOT_ALIASED = new ErrorCode(1_001_204_094, "自关联({})的 ON 条件两侧都没用到别名({})。⚠️ 这会静默退化成「主表.某列 = 主表.某列」—— SQL 能跑、结果却是错的（要么一行都关联不上，要么关联到自己）。请把关联表那一侧写成 {}.列名");
    ErrorCode FORM_DESIGN_JOIN_SELECT_ALIAS_CONFLICT = new ErrorCode(1_001_204_095, "关联表({})的展示列别名({})与已有的列重名。⚠️ 两列在 SELECT 里同名时，读回来的 Map 只会留下其中一个 —— 表现是「关联列显示的是主表自己的值」，而且不报错。给它换个别名，比如 {}");
    ErrorCode FORM_DESIGN_PRINT_LOOP_STRAY = new ErrorCode(1_001_204_097, "打印模板里的明细标记 {#{}} 没有放在表格的一行里。写法是：把 {#子表字段} 放进**要重复的那一行**的任意一个单元格中，这一整行就会按明细条数重复。⚠️ 放在表格外面不会报错，只是明细一行都印不出来，而设计者会先去怀疑数据");
    ErrorCode FORM_DESIGN_PRINT_LOOP_SUB_NOT_EXISTS = new ErrorCode(1_001_204_098, "打印模板里的明细行引用了子表「{}」，但这张表单没有这个子表。那一行印不出任何明细，且不报错");
    ErrorCode FORM_DESIGN_PRINT_LOOP_FIELD_NOT_EXISTS = new ErrorCode(1_001_204_099, "打印模板里 {#{}} 所在的明细行引用了「{}」，但它不是该子表的字段。这一格会在单据上留下一片空白，且不报错");
    ErrorCode FORM_DESIGN_PUBLISH_INVALID = new ErrorCode(1_001_204_096, "{}");
    ErrorCode FORM_DESIGN_SUB_TABLE_INVALID = new ErrorCode(1_001_204_055, "{}");
    ErrorCode FORM_DESIGN_SYSTEM_VALUE_NOT_AUTO = new ErrorCode(1_001_204_109, "系统值字段({})绑定的列({})不是「系统自动填充」模式。系统值控件是只读的、提交值也会被剥离，那一列会永远为空");

    // 运行时
    ErrorCode FORM_DESIGN_RUNTIME_EXECUTE_FAIL = new ErrorCode(1_001_204_030, "表单数据操作失败：{}");
    ErrorCode FORM_DESIGN_RUNTIME_DATA_NOT_EXISTS = new ErrorCode(1_001_204_031, "数据不存在");
    ErrorCode FORM_DESIGN_RUNTIME_BATCH_TOO_LARGE = new ErrorCode(1_001_204_039, "一次最多删除 {} 条数据，请分批操作");
    ErrorCode FORM_DESIGN_FIELD_NOT_MASKED = new ErrorCode(1_001_204_110, "字段({})没有配置脱敏，无需查看明文");
    ErrorCode FORM_DESIGN_FIELD_PLAIN_FORBIDDEN = new ErrorCode(1_001_204_111, "没有查看字段({})明文的权限");
    ErrorCode FORM_DESIGN_RELATION_WIDGET_NOT_EXISTS = new ErrorCode(1_001_204_112, "这张表单上没有字段为「{}」的关联表单控件。⚠️ 目标表单只能从已发布配置里读 —— 这条限制是故意的，否则关联取数接口就成了「任意表单任意字段」的读取口，比它要替代的查询权限还宽");

    // 导入
    //
    // ⚠️ 这几个错误码的文案要能被用户**直接照着改**。
    //    导入是个「用户拿着 Excel 反复试」的场景，报一句「导入失败」
    //    会让他改不动，只能来问管理员。
    ErrorCode FORM_DESIGN_FORMULA_NODE_UNKNOWN = new ErrorCode(1_001_204_104, "公式字段({})里有求值器认不出的节点类型({})。⚠️ 这一条必须拦：求值器对认不出的节点返回空值，于是整条公式算出 0 —— 发布不报错、运行时也不报错，只是那一列永远是 0。字面量的类型是 num / str / bool，不是 value");

    ErrorCode FORM_DESIGN_MASK_RULE_INVALID = new ErrorCode(1_001_204_105, "字段({})的脱敏策略({})认不出来。⚠️ 这一条必须拦：运行时对认不出的策略按【不脱敏】处理，于是列表、详情、导出三个出口全是明文，而界面上完全看不出来。可用值：MOBILE / ID_CARD / BANK_CARD / NAME / EMAIL / CUSTOM，且必须是字符串而不是对象");

    ErrorCode FORM_DESIGN_IMPORT_FILE_INVALID = new ErrorCode(1_001_204_113, "导入文件无法解析：{}");
    ErrorCode FORM_DESIGN_IMPORT_TOKEN_INVALID = new ErrorCode(1_001_204_114, "{}");
    ErrorCode FORM_DESIGN_IMPORT_TOO_MANY_ROWS = new ErrorCode(1_001_204_102, "一次最多导入 {} 行，当前文件有 {} 行。请拆分后分次导入");
    ErrorCode FORM_DESIGN_IMPORT_NO_SUB_TABLE_FIELD = new ErrorCode(1_001_204_103, "「{}」在表绑定里不是子表控件，无法承载明细数据");

    // 菜单联动
    ErrorCode FORM_DESIGN_MENU_PARENT_REQUIRED = new ErrorCode(1_001_204_040, "请选择上级菜单");
    ErrorCode FORM_DESIGN_ROW_OUT_OF_SCOPE = new ErrorCode(1_001_204_106, "保存失败：这条数据提交后会落到你的数据权限范围之外。⚠️ 常见原因是把归属部门／负责人改成了你看不到的值 —— 那样这条记录会立刻从你的列表里消失，且你再也改不回来，所以这里直接拦住");

    // 🔴 B4-4：表单【定义】这一行的记录级乐观锁。
    //    名字里刻意带 RECORD —— 上面已有 FORM_DESIGN_VERSION_CONFLICT(1_001_204_067)，
    //    那条说的是「表单绑定的业务数据行」的乐观锁（TableBinding.versionColumn），
    //    与 infra_form_design 自己这一行是两回事。同名会让用户看到「版本冲突」时
    //    根本分不清是表单定义撞了还是单据数据撞了 —— 而两者的补救动作完全不同。
    //    ⚠️ MyBatis-Plus 的拦截器在冲突时【不抛异常】，只是让 UPDATE 命中 0 行，
    //    所以必须由调用方检查影响行数后抛这一条，否则并发覆盖是静默的。
    ErrorCode FORM_DESIGN_RECORD_VERSION_CONFLICT = new ErrorCode(1_001_204_115,
            "这张表单在你编辑期间已被他人修改，你的修改没有保存。请刷新后在最新数据上重新修改 —— "
                    + "直接覆盖会把对方的改动悄悄抹掉");
    // 🔴 绊线：更新对象没带 version ⟹ MP 的拦截器【静默跳过】、根本不加版本条件
    //    （3.5.16 的 doOptimisticLocker 取到 null 就直接 return），乐观锁等于没开且不报错。
    //    本表有 7 条 update 路径，其中 publish / bindMenu / unpublish / rollback 都是
    //    「new 一个只 set 两三个字段的 DO」的写法 —— 正是最容易漏带 version 的形态。
    ErrorCode FORM_DESIGN_RECORD_VERSION_REQUIRED = new ErrorCode(1_001_204_116,
            "本次更新没有带上版本号，已拒绝执行：缺少它乐观锁就形同没开（MyBatis-Plus 会静默跳过版本条件），"
                    + "并发覆盖会照旧发生且不报错。请先读回这条记录、把 version 一并带上再提交");
    ErrorCode FORM_DESIGN_APP_NAV_PARENT_REQUIRED = new ErrorCode(1_001_204_117,
            "发布到 App 端需要选择移动端导航的上级分组：导航节点必须挂在一个分组或子分组下，"
                    + "移动端工作台才会把它渲染出来");
    // ⚠️ 这里【刻意没有】FORM_DESIGN_SCHEMA_DRIFT。表单侧的 schema_fingerprint 只在发布时记录、
    //    不做拦截，理由是拦了反而更糟：
    //    1. B4-2 的闸是「DDL 执行前复核」，而表单设计【只绑定已有表、不建表也不改表】，
    //       这条路径上根本没有 DDL，没有「已经执行了一半、回不去」的代价要防；
    //    2. FormDesignPublishValidator 已经逐列核过「映射的列必须在主表中存在」，
    //       报出来的是「哪个字段映射的哪一列没了」—— 比「两个哈希不相等」可操作得多；
    //    3. 哈希闸会对【与本表单无关】的结构变更也报警（DBA 把某个没用到的列加宽了），
    //       于是发布被一条无从修起的错误卡住。假阳性 + 无补救动作 = 比不拦更坏。
    //    留着这一列的价值是【事后取证】：用户报「表单运行时炸了」，拿它与实时结构一比，
    //    立刻知道是不是发布之后表被人绕过平台改过。

    // ========== 门户设计 1-001-205-000 ==========
    ErrorCode PORTAL_NOT_EXISTS = new ErrorCode(1_001_205_000, "门户不存在");
    ErrorCode PORTAL_CODE_DUPLICATE = new ErrorCode(1_001_205_001, "门户编码({})已存在");
    ErrorCode PORTAL_CODE_CANNOT_UPDATE = new ErrorCode(1_001_205_002, "门户已发布，不允许修改门户编码");
    ErrorCode PORTAL_VERSION_NOT_EXISTS = new ErrorCode(1_001_205_003, "门户版本不存在");
    ErrorCode PORTAL_NOT_PUBLISHED = new ErrorCode(1_001_205_004, "门户尚未发布");
    ErrorCode PORTAL_CONFIG_INVALID = new ErrorCode(1_001_205_005, "门户布局配置不合法：{}");
    ErrorCode PORTAL_RUNTIME_FORBIDDEN = new ErrorCode(1_001_205_006, "没有门户访问权限");
    ErrorCode PORTAL_LINK_INCOMPLETE = new ErrorCode(1_001_205_007, "链接类型门户必须填写链接类型和链接地址");
    ErrorCode PORTAL_TYPE_CANNOT_UPDATE = new ErrorCode(1_001_205_008, "门户类型创建后不允许修改");

    // ========== 单据模板 1-001-206-000 ==========
    ErrorCode BILL_TEMPLATE_NOT_EXISTS = new ErrorCode(1_001_206_000, "单据模板不存在");
    ErrorCode BILL_TEMPLATE_CODE_DUPLICATE = new ErrorCode(1_001_206_001, "单据模板编码({})已存在");
    ErrorCode BILL_TEMPLATE_DISABLED = new ErrorCode(1_001_206_002, "单据模板({})已停用");
    ErrorCode BILL_TEMPLATE_CONFIG_INVALID = new ErrorCode(1_001_206_003, "单据模板配置不合法：{}");
    // 已生成过号码的模板不允许删除：号码已散落在业务数据里，删掉模板会导致无法追溯规则
    ErrorCode BILL_TEMPLATE_DELETE_FAIL_USED = new ErrorCode(1_001_206_004, "单据模板({})已生成过单号，不允许删除。如需停止使用请改为「停用」");

    // 表单绑定校验，在表单发布时执行
    ErrorCode BILL_TEMPLATE_FIELD_UNBOUND = new ErrorCode(1_001_206_010, "单据字段({})未绑定单据模板");
    ErrorCode BILL_TEMPLATE_FIELD_NO_MAPPING = new ErrorCode(1_001_206_011, "单据字段({})未配置字段映射，生成的单号无处存放");
    ErrorCode BILL_TEMPLATE_REF_FIELD_UNKNOWN = new ErrorCode(1_001_206_012, "单据规则引用的字段({})在当前作用域内不存在");

    // 运行时
    ErrorCode BILL_TEMPLATE_GENERATE_FAIL = new ErrorCode(1_001_206_020, "单号生成失败：{}");

}
