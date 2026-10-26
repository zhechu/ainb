package cn.enlev.ainb.module.system.enums;

import cn.enlev.ainb.framework.common.exception.ErrorCode;

/**
 * System 错误码枚举类
 *
 * system 系统，使用 1-002-000-000 段
 */
public interface ErrorCodeConstants {

    // ========== AUTH 模块 1-002-000-000 ==========
    ErrorCode AUTH_LOGIN_BAD_CREDENTIALS = new ErrorCode(1_002_000_000, "登录失败，账号密码不正确");
    ErrorCode AUTH_LOGIN_USER_DISABLED = new ErrorCode(1_002_000_001, "登录失败，账号被禁用");
    ErrorCode AUTH_LOGIN_CAPTCHA_CODE_ERROR = new ErrorCode(1_002_000_004, "验证码不正确，原因：{}");
    ErrorCode AUTH_THIRD_LOGIN_NOT_BIND = new ErrorCode(1_002_000_005, "未绑定账号，需要进行绑定");
    ErrorCode AUTH_MOBILE_NOT_EXISTS = new ErrorCode(1_002_000_007, "手机号不存在");
    ErrorCode AUTH_REGISTER_CAPTCHA_CODE_ERROR = new ErrorCode(1_002_000_008, "验证码不正确，原因：{}");

    // ========== 菜单模块 1-002-001-000 ==========
    ErrorCode MENU_NAME_DUPLICATE = new ErrorCode(1_002_001_000, "已经存在该名字的菜单");
    ErrorCode MENU_PARENT_NOT_EXISTS = new ErrorCode(1_002_001_001, "父菜单不存在");
    ErrorCode MENU_PARENT_ERROR = new ErrorCode(1_002_001_002, "不能设置自己为父菜单");
    ErrorCode MENU_NOT_EXISTS = new ErrorCode(1_002_001_003, "菜单不存在");
    ErrorCode MENU_EXISTS_CHILDREN = new ErrorCode(1_002_001_004, "存在子菜单，无法删除");
    ErrorCode MENU_PARENT_NOT_DIR_OR_MENU = new ErrorCode(1_002_001_005, "父菜单的类型必须是目录或者菜单");
    ErrorCode MENU_COMPONENT_NAME_DUPLICATE = new ErrorCode(1_002_001_006, "已经存在该组件名的菜单");

    // ========== 角色模块 1-002-002-000 ==========
    ErrorCode ROLE_NOT_EXISTS = new ErrorCode(1_002_002_000, "角色不存在");
    ErrorCode ROLE_NAME_DUPLICATE = new ErrorCode(1_002_002_001, "已经存在名为【{}】的角色");
    ErrorCode ROLE_CODE_DUPLICATE = new ErrorCode(1_002_002_002, "已经存在标识为【{}】的角色");
    ErrorCode ROLE_CAN_NOT_UPDATE_SYSTEM_TYPE_ROLE = new ErrorCode(1_002_002_003, "不能操作类型为系统内置的角色");
    ErrorCode ROLE_IS_DISABLE = new ErrorCode(1_002_002_004, "名字为【{}】的角色已被禁用");
    ErrorCode ROLE_ADMIN_CODE_ERROR = new ErrorCode(1_002_002_005, "标识【{}】不能使用");

    // ========== 用户模块 1-002-003-000 ==========
    ErrorCode USER_USERNAME_EXISTS = new ErrorCode(1_002_003_000, "用户账号已经存在");
    ErrorCode USER_MOBILE_EXISTS = new ErrorCode(1_002_003_001, "手机号已经存在");
    ErrorCode USER_EMAIL_EXISTS = new ErrorCode(1_002_003_002, "邮箱已经存在");
    ErrorCode USER_NOT_EXISTS = new ErrorCode(1_002_003_003, "用户不存在");
    ErrorCode USER_IMPORT_LIST_IS_EMPTY = new ErrorCode(1_002_003_004, "导入用户数据不能为空！");
    ErrorCode USER_PASSWORD_FAILED = new ErrorCode(1_002_003_005, "用户密码校验失败");
    ErrorCode USER_IS_DISABLE = new ErrorCode(1_002_003_006, "名字为【{}】的用户已被禁用");
    ErrorCode USER_COUNT_MAX = new ErrorCode(1_002_003_008, "创建用户失败，原因：超过租户最大租户配额({})！");
    ErrorCode USER_IMPORT_INIT_PASSWORD = new ErrorCode(1_002_003_009, "初始密码不能为空");
    ErrorCode USER_MOBILE_NOT_EXISTS = new ErrorCode(1_002_003_010, "该手机号尚未注册");
    ErrorCode USER_REGISTER_DISABLED = new ErrorCode(1_002_003_011, "注册功能已关闭");
    // 导入预检相关。1-002-003-012 起，前面的号段已被上游占用
    ErrorCode USER_IMPORT_TOKEN_EXPIRED = new ErrorCode(1_002_003_012,
            "这份预检结果已经过期（超过 10 分钟），请重新上传文件");
    ErrorCode USER_IMPORT_TOKEN_CONSUMED = new ErrorCode(1_002_003_013,
            "这份预检结果已经导入过了。重复提交不会再导一次，请重新上传文件");
    ErrorCode USER_IMPORT_HEADER_MISSING = new ErrorCode(1_002_003_014,
            "没有读到表头行。请确认第一行是列标题，且用的是模板里的列名");
    ErrorCode USER_IMPORT_ALL_OR_NOTHING_ABORTED = new ErrorCode(1_002_003_015,
            "勾选了「全对才导」，而还有 {} 行没有通过校验，因此整批都没有导入");

    // ========== 组织模块 1-002-004-000 ==========
    ErrorCode DEPT_NAME_DUPLICATE = new ErrorCode(1_002_004_000, "已经存在该名字的组织");
    ErrorCode DEPT_PARENT_NOT_EXITS = new ErrorCode(1_002_004_001,"父级组织不存在");
    ErrorCode DEPT_NOT_FOUND = new ErrorCode(1_002_004_002, "当前组织不存在");
    ErrorCode DEPT_EXITS_CHILDREN = new ErrorCode(1_002_004_003, "存在子组织，无法删除");
    ErrorCode DEPT_PARENT_ERROR = new ErrorCode(1_002_004_004, "不能设置自己为父组织");
    ErrorCode DEPT_CODE_DUPLICATE = new ErrorCode(1_002_004_005, "已经存在该编码的组织");
    ErrorCode DEPT_NOT_ENABLE = new ErrorCode(1_002_004_006, "组织({})不处于开启状态，不允许选择");
    ErrorCode DEPT_PARENT_IS_CHILD = new ErrorCode(1_002_004_007, "不能设置自己的子组织为父组织");

    // ========== 岗位模块 1-002-005-000 ==========
    ErrorCode POST_NOT_FOUND = new ErrorCode(1_002_005_000, "当前岗位不存在");
    ErrorCode POST_NOT_ENABLE = new ErrorCode(1_002_005_001, "岗位({}) 不处于开启状态，不允许选择");
    ErrorCode POST_NAME_DUPLICATE = new ErrorCode(1_002_005_002, "已经存在该名字的岗位");
    ErrorCode POST_CODE_DUPLICATE = new ErrorCode(1_002_005_003, "已经存在该标识的岗位");

    // ========== 字典类型 1-002-006-000 ==========
    ErrorCode DICT_TYPE_NOT_EXISTS = new ErrorCode(1_002_006_001, "当前字典类型不存在");
    ErrorCode DICT_TYPE_NOT_ENABLE = new ErrorCode(1_002_006_002, "字典类型不处于开启状态，不允许选择");
    ErrorCode DICT_TYPE_NAME_DUPLICATE = new ErrorCode(1_002_006_003, "已经存在该名字的字典类型");
    ErrorCode DICT_TYPE_TYPE_DUPLICATE = new ErrorCode(1_002_006_004, "已经存在该类型的字典类型");
    ErrorCode DICT_TYPE_HAS_CHILDREN = new ErrorCode(1_002_006_005, "无法删除，该字典类型还有字典数据");
    ErrorCode DICT_TYPE_PARENT_NOT_EXISTS = new ErrorCode(1_002_006_006, "父分类不存在");
    ErrorCode DICT_TYPE_PARENT_NOT_GROUP = new ErrorCode(1_002_006_007, "父级只能是分组，不能是字典");
    ErrorCode DICT_TYPE_PARENT_IS_SELF = new ErrorCode(1_002_006_008, "不能设置自己为父分类");
    ErrorCode DICT_TYPE_PARENT_IS_CHILD = new ErrorCode(1_002_006_009, "不能设置自己的子分类为父分类");
    ErrorCode DICT_TYPE_HAS_CHILDREN_TYPE = new ErrorCode(1_002_006_010, "无法删除，该分类下还有子分类");
    ErrorCode DICT_TYPE_TREE_ENABLE_CANNOT_UPDATE = new ErrorCode(1_002_006_011, "字典创建后不允许切换树形开关，请通过导出后重建的方式调整");
    ErrorCode DICT_TYPE_IS_GROUP = new ErrorCode(1_002_006_012, "分组不能挂字典数据，请选择一个字典");
    ErrorCode DICT_TRANSFER_VERSION_UNSUPPORTED = new ErrorCode(1_002_006_013, "迁移包版本（{}）高于当前系统支持的版本（{}），请先升级系统");
    ErrorCode DICT_TRANSFER_TYPES_EMPTY = new ErrorCode(1_002_006_014, "迁移包里没有任何字典分类");
    ErrorCode DICT_TRANSFER_PARSE_FAILED = new ErrorCode(1_002_006_015, "迁移包解析失败，请确认上传的是本系统导出的 JSON 文件");
    ErrorCode DICT_TRANSFER_PARENT_UNRESOLVED = new ErrorCode(1_002_006_016, "上级分类在包内和已有分类中都找不到，或与其它分类互相引用成环");
    ErrorCode DICT_TRANSFER_TREE_ENABLE_CONFLICT = new ErrorCode(1_002_006_017, "已存在的同编码分类与包里的节点类型或树形设置不一致，无法覆盖");
    ErrorCode DICT_TRANSFER_NAME_OCCUPIED = new ErrorCode(1_002_006_018, "该名称已被其它分类占用");
    ErrorCode DICT_TRANSFER_TYPE_OCCUPIED = new ErrorCode(1_002_006_019, "该编码已被其它分类占用");
    ErrorCode DICT_TRANSFER_COPY_SUFFIX_EXHAUSTED = new ErrorCode(1_002_006_020, "自动生成副本编码失败，请手工调整包内的分类编码后重试");
    ErrorCode DICT_TRANSFER_TYPE_INVALID = new ErrorCode(1_002_006_021, "迁移包里存在分类编码或名称为空的节点");

    // ========== 字典数据 1-002-007-000 ==========
    ErrorCode DICT_DATA_NOT_EXISTS = new ErrorCode(1_002_007_001, "当前字典数据不存在");
    ErrorCode DICT_DATA_NOT_ENABLE = new ErrorCode(1_002_007_002, "字典数据({})不处于开启状态，不允许选择");
    ErrorCode DICT_DATA_VALUE_DUPLICATE = new ErrorCode(1_002_007_003, "已经存在该值的字典数据");
    ErrorCode DICT_DATA_PARENT_NOT_EXISTS = new ErrorCode(1_002_007_004, "父节点不存在");
    ErrorCode DICT_DATA_PARENT_NOT_SAME_TYPE = new ErrorCode(1_002_007_005, "父节点必须属于同一个字典");
    ErrorCode DICT_DATA_PARENT_IS_SELF = new ErrorCode(1_002_007_006, "不能设置自己为父节点");
    ErrorCode DICT_DATA_PARENT_IS_CHILD = new ErrorCode(1_002_007_007, "不能设置自己的子节点为父节点");
    ErrorCode DICT_DATA_TREE_NOT_ENABLE = new ErrorCode(1_002_007_008, "该字典不是树形字典，不能设置父节点");
    ErrorCode DICT_DATA_LEVEL_EXCEED = new ErrorCode(1_002_007_009, "层级超过上限（{}），不允许继续添加下级");
    ErrorCode DICT_DATA_HAS_CHILDREN = new ErrorCode(1_002_007_010, "无法删除，该节点下还有子节点");
    ErrorCode DICT_DATA_IMPORT_LIST_IS_EMPTY = new ErrorCode(1_002_007_011, "导入字典数据不能为空");
    ErrorCode DICT_DATA_IMPORT_LABEL_VALUE_REQUIRED = new ErrorCode(1_002_007_012, "字典标签和字典键值都不能为空");
    ErrorCode DICT_DATA_IMPORT_VALUE_REPEATED = new ErrorCode(1_002_007_013, "同一个文件里出现了重复的字典键值");
    ErrorCode DICT_DATA_IMPORT_PARENT_UNRESOLVED = new ErrorCode(1_002_007_014, "上级键值在文件和已有数据里都找不到，或与其它行互相引用成环");

    // ========== 通知公告 1-002-008-000 ==========
    ErrorCode NOTICE_NOT_FOUND = new ErrorCode(1_002_008_001, "当前通知公告不存在");

    // ========== 短信渠道 1-002-011-000 ==========
    ErrorCode SMS_CHANNEL_NOT_EXISTS = new ErrorCode(1_002_011_000, "短信渠道不存在");
    ErrorCode SMS_CHANNEL_DISABLE = new ErrorCode(1_002_011_001, "短信渠道不处于开启状态，不允许选择");
    ErrorCode SMS_CHANNEL_HAS_CHILDREN = new ErrorCode(1_002_011_002, "无法删除，该短信渠道还有短信模板");

    // ========== 短信模板 1-002-012-000 ==========
    ErrorCode SMS_TEMPLATE_NOT_EXISTS = new ErrorCode(1_002_012_000, "短信模板不存在");
    ErrorCode SMS_TEMPLATE_CODE_DUPLICATE = new ErrorCode(1_002_012_001, "已经存在编码为【{}】的短信模板");
    ErrorCode SMS_TEMPLATE_API_ERROR = new ErrorCode(1_002_012_002, "短信 API 模板调用失败，原因是：{}");
    ErrorCode SMS_TEMPLATE_API_AUDIT_CHECKING = new ErrorCode(1_002_012_003, "短信 API 模版无法使用，原因：审批中");
    ErrorCode SMS_TEMPLATE_API_AUDIT_FAIL = new ErrorCode(1_002_012_004, "短信 API 模版无法使用，原因：审批不通过，{}");
    ErrorCode SMS_TEMPLATE_API_NOT_FOUND = new ErrorCode(1_002_012_005, "短信 API 模版无法使用，原因：模版不存在");

    // ========== 短信发送 1-002-013-000 ==========
    ErrorCode SMS_SEND_MOBILE_NOT_EXISTS = new ErrorCode(1_002_013_000, "手机号不存在");
    ErrorCode SMS_SEND_MOBILE_TEMPLATE_PARAM_MISS = new ErrorCode(1_002_013_001, "模板参数({})缺失");
    ErrorCode SMS_SEND_TEMPLATE_NOT_EXISTS = new ErrorCode(1_002_013_002, "短信模板不存在");

    // ========== 短信验证码 1-002-014-000 ==========
    ErrorCode SMS_CODE_NOT_FOUND = new ErrorCode(1_002_014_000, "验证码不存在");
    ErrorCode SMS_CODE_EXPIRED = new ErrorCode(1_002_014_001, "验证码已过期");
    ErrorCode SMS_CODE_USED = new ErrorCode(1_002_014_002, "验证码已使用");
    ErrorCode SMS_CODE_EXCEED_SEND_MAXIMUM_QUANTITY_PER_DAY = new ErrorCode(1_002_014_004, "超过每日短信发送数量");
    ErrorCode SMS_CODE_SEND_TOO_FAST = new ErrorCode(1_002_014_005, "短信发送过于频繁");

    // ========== 租户信息 1-002-015-000 ==========
    ErrorCode TENANT_NOT_EXISTS = new ErrorCode(1_002_015_000, "租户不存在");
    ErrorCode TENANT_DISABLE = new ErrorCode(1_002_015_001, "名字为【{}】的租户已被禁用");
    ErrorCode TENANT_EXPIRE = new ErrorCode(1_002_015_002, "名字为【{}】的租户已过期");
    ErrorCode TENANT_CAN_NOT_UPDATE_SYSTEM = new ErrorCode(1_002_015_003, "系统租户不能进行修改、删除等操作！");
    ErrorCode TENANT_NAME_DUPLICATE = new ErrorCode(1_002_015_004, "名字为【{}】的租户已存在");
    ErrorCode TENANT_WEBSITE_DUPLICATE = new ErrorCode(1_002_015_005, "域名为【{}】的租户已存在");

    // ========== 租户套餐 1-002-016-000 ==========
    ErrorCode TENANT_PACKAGE_NOT_EXISTS = new ErrorCode(1_002_016_000, "租户套餐不存在");
    ErrorCode TENANT_PACKAGE_USED = new ErrorCode(1_002_016_001, "租户正在使用该套餐，请给租户重新设置套餐后再尝试删除");
    ErrorCode TENANT_PACKAGE_DISABLE = new ErrorCode(1_002_016_002, "名字为【{}】的租户套餐已被禁用");
    ErrorCode TENANT_PACKAGE_NAME_DUPLICATE = new ErrorCode(1_002_016_003, "已经存在该名字的租户套餐");

    // ========== 社交用户 1-002-018-000 ==========
    ErrorCode SOCIAL_USER_AUTH_FAILURE = new ErrorCode(1_002_018_000, "社交授权失败，原因是：{}");
    ErrorCode SOCIAL_USER_NOT_FOUND = new ErrorCode(1_002_018_001, "社交授权失败，找不到对应的用户");

    ErrorCode SOCIAL_CLIENT_WEIXIN_MINI_APP_PHONE_CODE_ERROR = new ErrorCode(1_002_018_200, "获得手机号失败");
    ErrorCode SOCIAL_CLIENT_WEIXIN_MINI_APP_QRCODE_ERROR = new ErrorCode(1_002_018_201, "获得小程序码失败");
    ErrorCode SOCIAL_CLIENT_WEIXIN_MINI_APP_SUBSCRIBE_TEMPLATE_ERROR = new ErrorCode(1_002_018_202, "获得小程序订阅消息模版失败");
    ErrorCode SOCIAL_CLIENT_WEIXIN_MINI_APP_SUBSCRIBE_MESSAGE_ERROR = new ErrorCode(1_002_018_203, "发送小程序订阅消息失败");
    ErrorCode SOCIAL_CLIENT_WEIXIN_MINI_APP_ORDER_UPLOAD_SHIPPING_INFO_ERROR = new ErrorCode(1_002_018_204, "上传微信小程序发货信息失败");
    ErrorCode SOCIAL_CLIENT_WEIXIN_MINI_APP_ORDER_NOTIFY_CONFIRM_RECEIVE_ERROR = new ErrorCode(1_002_018_205, "上传微信小程序订单收货信息失败");
    ErrorCode SOCIAL_CLIENT_NOT_EXISTS = new ErrorCode(1_002_018_210, "社交客户端不存在");
    ErrorCode SOCIAL_CLIENT_UNIQUE = new ErrorCode(1_002_018_211, "社交客户端已存在配置");

    // ========== OAuth2 客户端 1-002-020-000 =========
    ErrorCode OAUTH2_CLIENT_NOT_EXISTS = new ErrorCode(1_002_020_000, "OAuth2 客户端不存在");
    ErrorCode OAUTH2_CLIENT_EXISTS = new ErrorCode(1_002_020_001, "OAuth2 客户端编号已存在");
    ErrorCode OAUTH2_CLIENT_DISABLE = new ErrorCode(1_002_020_002, "OAuth2 客户端已禁用");
    ErrorCode OAUTH2_CLIENT_AUTHORIZED_GRANT_TYPE_NOT_EXISTS = new ErrorCode(1_002_020_003, "不支持该授权类型");
    ErrorCode OAUTH2_CLIENT_SCOPE_OVER = new ErrorCode(1_002_020_004, "授权范围过大");
    ErrorCode OAUTH2_CLIENT_REDIRECT_URI_NOT_MATCH = new ErrorCode(1_002_020_005, "无效 redirect_uri: {}");
    ErrorCode OAUTH2_CLIENT_CLIENT_SECRET_ERROR = new ErrorCode(1_002_020_006, "无效 client_secret: {}");

    // ========== OAuth2 授权 1-002-021-000 =========
    ErrorCode OAUTH2_GRANT_CLIENT_ID_MISMATCH = new ErrorCode(1_002_021_000, "client_id 不匹配");
    ErrorCode OAUTH2_GRANT_REDIRECT_URI_MISMATCH = new ErrorCode(1_002_021_001, "redirect_uri 不匹配");
    ErrorCode OAUTH2_GRANT_STATE_MISMATCH = new ErrorCode(1_002_021_002, "state 不匹配");

    // ========== OAuth2 授权 1-002-022-000 =========
    ErrorCode OAUTH2_CODE_NOT_EXISTS = new ErrorCode(1_002_022_000, "code 不存在");
    ErrorCode OAUTH2_CODE_EXPIRE = new ErrorCode(1_002_022_001, "code 已过期");

    // ========== 邮箱账号 1-002-023-000 ==========
    ErrorCode MAIL_ACCOUNT_NOT_EXISTS = new ErrorCode(1_002_023_000, "邮箱账号不存在");
    ErrorCode MAIL_ACCOUNT_RELATE_TEMPLATE_EXISTS = new ErrorCode(1_002_023_001, "无法删除，该邮箱账号还有邮件模板");

    // ========== 邮件模版 1-002-024-000 ==========
    ErrorCode MAIL_TEMPLATE_NOT_EXISTS = new ErrorCode(1_002_024_000, "邮件模版不存在");
    ErrorCode MAIL_TEMPLATE_CODE_EXISTS = new ErrorCode(1_002_024_001, "邮件模版 code({}) 已存在");

    // ========== 邮件发送 1-002-025-000 ==========
    ErrorCode MAIL_SEND_TEMPLATE_PARAM_MISS = new ErrorCode(1_002_025_000, "模板参数({})缺失");
    ErrorCode MAIL_SEND_MAIL_NOT_EXISTS = new ErrorCode(1_002_025_001, "邮箱不存在");

    // ========== 站内信模版 1-002-026-000 ==========
    ErrorCode NOTIFY_TEMPLATE_NOT_EXISTS = new ErrorCode(1_002_026_000, "站内信模版不存在");
    ErrorCode NOTIFY_TEMPLATE_CODE_DUPLICATE = new ErrorCode(1_002_026_001, "已经存在编码为【{}】的站内信模板");

    // ========== 站内信模版 1-002-027-000 ==========

    // ========== 站内信发送 1-002-028-000 ==========
    ErrorCode NOTIFY_SEND_TEMPLATE_PARAM_MISS = new ErrorCode(1_002_028_000, "模板参数({})缺失");

    // ========== 授权主体 1-002-030-000 ==========
    // 030 起是权限体系重构新占的号段，029 之前留给上游，避免升级时撞号
    ErrorCode SUBJECT_NOT_EXISTS = new ErrorCode(1_002_030_000, "授权主体不存在");
    ErrorCode SUBJECT_MEMBER_SELF = new ErrorCode(1_002_030_001, "主体不能是自己的成员");
    ErrorCode SUBJECT_MEMBER_CYCLE = new ErrorCode(1_002_030_002,
            "该成员关系会形成环路：{}。环会让登录时的主体解析无限递归");
    ErrorCode SUBJECT_MEMBER_DEPTH_EXCEED = new ErrorCode(1_002_030_003,
            "该成员关系会让继承链达到 {} 层，超过上限 {} 层");
    ErrorCode SUBJECT_MEMBER_DUPLICATE = new ErrorCode(1_002_030_004, "该成员关系已经存在");
    ErrorCode SUBJECT_MEMBER_TYPE_NOT_SUPPORTED = new ErrorCode(1_002_030_005,
            "{} 类型的主体不能作为成员");

    // ========== 行过滤规则 1_002_032_000 ==========
    ErrorCode RULE_NOT_EXISTS = new ErrorCode(1_002_032_000, "行过滤规则不存在");
    ErrorCode RULE_BUILT_IN_UNDELETABLE = new ErrorCode(1_002_032_001,
            "系统内置规则不能删除，可以将其停用后不再出现在下拉里");
    ErrorCode RULE_IN_USE = new ErrorCode(1_002_032_002,
            "该规则已被 {} 条授权引用，删除会让那些授权指向不存在的规则（判定时整表拒绝）。请先解除引用");
    ErrorCode RULE_BUILT_IN_UNEDITABLE = new ErrorCode(1_002_032_003,
            "系统内置规则不能编辑，只能查看；如需调整请基于内置模板新建一条自定义规则");
    ErrorCode RULE_UPDATE_ID_REQUIRED = new ErrorCode(1_002_032_004, "修改行过滤规则时规则编号不能为空");

    // ========== 权限资源 1_002_031_000 ==========
    ErrorCode RESOURCE_NOT_EXISTS = new ErrorCode(1_002_031_000, "权限资源不存在");
    ErrorCode RESOURCE_PERMISSION_DUPLICATE = new ErrorCode(1_002_031_001,
            "权限标识【{}】已存在（资源 id={}）。同一权限串存在多行资源会导致授权判定不确定");
    ErrorCode RESOURCE_TYPE_INVALID = new ErrorCode(1_002_031_002, "资源类型不合法：{}");
    ErrorCode RESOURCE_DERIVED_READONLY = new ErrorCode(1_002_031_003,
            "该资源由菜单（id={}）自动维护，权限标识/名称/类型/父节点改了会在下次菜单同步时被覆盖。"
                    + "请到「菜单管理」修改对应菜单");
    ErrorCode RESOURCE_DERIVED_UNDELETABLE = new ErrorCode(1_002_031_004,
            "该资源由菜单（id={}）自动维护，删了会在下次菜单同步时重建。要移除请到「菜单管理」删除对应菜单");
    ErrorCode RESOURCE_HAS_GRANT = new ErrorCode(1_002_031_005,
            "该资源上还有 {} 条授权，删除会产生孤儿授权。请先在角色的「资源授权」里解除");
    ErrorCode RESOURCE_HAS_CHILDREN = new ErrorCode(1_002_031_006,
            "还有 {} 个子资源没有一起选中，删除会留下孤儿节点。请把子资源一并勾选，或先删子资源");
    // ⚠️ 这条拦的是「静默改写」：判定侧的 RecordSharePolicyEnum#parse 解析不出来时返回的是
    //    OWNER_ONLY 而不是 null（那是刻意的安全兜底），所以拿它当校验会让非法值"parse 成功"
    //    并原样落库 —— 界面上配了个错值、保存还成功了、实际按 OWNER_ONLY 执行
    ErrorCode RESOURCE_SHARE_POLICY_INVALID = new ErrorCode(1_002_031_007,
            "非法的分享策略：{}，可选值 OWNER_ONLY / ANY_VIEWER / DISABLED");

    // ========== 授权行 1-002-033-000 ==========
    ErrorCode GRANT_NOT_EXISTS = new ErrorCode(1_002_033_000, "授权行不存在");
    ErrorCode GRANT_BATCH_KEY_DUPLICATE = new ErrorCode(1_002_033_001,
            "批量提交内部存在重复的授权键（resourceId={}，action={}）："
                    + "同一主体在同一资源+动作+上下文下只能有一行，请合并成一条再提交");
    // 🔴 这条拦的是「机器替用户做了安全决策」：同键多行且 effect 不一致时，
    // 收敛（只留 min(id)）等于替管理员决定那条 DENY 该不该删 —— 删了就是放权。
    // 详见 GrantAdminServiceImpl#validateConvergeable
    ErrorCode GRANT_DUPLICATE_CONFLICT = new ErrorCode(1_002_033_002,
            "库里存在同键但内容不一致的多行授权（ids={}），自动收敛会改变安全语义"
                    + "（任意一行 DENY 一票否决所有 ALLOW）。请先人工删除多余的行再重试");
    ErrorCode GRANT_UPDATE_ID_REQUIRED = new ErrorCode(1_002_033_003, "修改授权行时授权编号不能为空");
    // 🔴 主体寻址参数写错时的拒绝，/effective-tree、/list-by-subject、
    // GrantDecisionService#explain 三处共用。码号沿用原来那条（名字去掉了 EFFECTIVE，
    // 因为它原先只服务生效权限树），而 /list-by-subject 走的是另一套写法 ——
    // 类型解析不出来就 return 空列表，类型对但少给标识就直接 new SubjectKey(...)，
    // 于是 SubjectKey 紧凑构造器的 IllegalArgumentException("主体 SYSTEM 必须有 code")
    // 与 Objects.requireNonNull 的 NullPointerException 一路漏到兜底处理器。
    // 实测 ?subjectType=SYSTEM 与 ?subjectType=ROLE 各得到一条 {"code":500,"msg":"系统异常"}，
    // 并往 infra_api_error_log 各写一条 3010 字节的堆栈。
    //
    // 两种写法都错，而且错法相反：空列表把参数错误伪装成「这个主体没有任何授权」这个
    // 合法业务结论（在权限地图上就读成「这个角色什么权限都没有」），500 则把一次参数
    // 失误伪装成一次线上故障
    ErrorCode GRANT_SUBJECT_TYPE_INVALID = new ErrorCode(1_002_033_004,
            "无法定位授权主体：类型 {} 不可识别");
    // ⚠️ 与上面拆成两条，不是嫌一条不够用 —— 是原来那一条的消息模板套不上这两种情况。
    // 它写的是「类型 {} 不可识别，或缺少定位该主体所需的编号/编码」，
    // 而「少给标识」那一支只能把 "SYSTEM 缺少 subjectCode" 整个塞进 {}，
    // 实测拼出来是「类型 SYSTEM 缺少 subjectCode 不可识别，或缺少……」：
    // 一句话里同时说「缺少」和「不可识别」，用户读完不知道自己到底哪里错了。
    // 拆开后每条模板只描述一种失败，参数只放裸值 —— 与下面 RECORD_SHARE_* 三条同口径
    ErrorCode GRANT_SUBJECT_IDENTIFIER_REQUIRED = new ErrorCode(1_002_033_006,
            "无法定位授权主体：{} 类型的主体必须给 {}");
    ErrorCode GRANT_MIGRATE_CONFIRM_REQUIRED = new ErrorCode(1_002_033_005,
            "请显式传 confirm=true 确认执行数据权限迁移");

    // ========== 槽位映射 1-002-034-000 ==========
    // 🔴 这四条原先是 throw new IllegalArgumentException(...)。GlobalExceptionHandler 没有
    //    IllegalArgumentException 的 handler，于是它一路落到 defaultExceptionHandler：
    //    精心写出来的消息被换成「系统异常」，还往 infra_api_error_log 插一条带完整堆栈的
    //    错误日志 —— 一次「列名选错了」的配置失误，在运维那边长得和一次线上故障一模一样。
    //    而这四条恰恰都是【必须让人读懂】的：它们说的全是「你这一格填的东西在库里不存在」，
    //    而槽位映射配错的后果是引用它的规则恒假（一行都看不到且不报错），
    //    读不懂拒绝原因的人只会以为自己配好了
    ErrorCode SLOT_MAPPING_DUPLICATE = new ErrorCode(1_002_034_000,
            "该资源下 slot={} 的映射已存在，请直接修改那一行");
    ErrorCode SLOT_MAPPING_UPDATE_ID_REQUIRED = new ErrorCode(1_002_034_001, "修改槽位映射时编号不能为空");
    ErrorCode SLOT_MAPPING_TABLE_NOT_IN_DB = new ErrorCode(1_002_034_002,
            "表 {} 不在当前库里，无法绑定槽位映射");
    ErrorCode SLOT_MAPPING_COLUMN_NOT_EXISTS = new ErrorCode(1_002_034_003,
            "表 {} 上没有列 {}，请重新选择");

    // ========== 记录分享 1-002-035-000 ==========
    // 与上面同一批：原先都是 IllegalArgumentException，消息会被兜底处理器换成「系统异常」。
    // 这三条尤其不能吞 —— 它们说的是「你少给了定位这个主体所需的编号」，
    // 读成「系统异常」之后，用户的下一步是重试同一个请求，而不是去补那个字段
    ErrorCode RECORD_SHARE_SUBJECT_TYPE_INVALID = new ErrorCode(1_002_035_000,
            "不认识的主体类型：{}");
    ErrorCode RECORD_SHARE_SUBJECT_REF_ID_REQUIRED = new ErrorCode(1_002_035_001,
            "{} 类型的主体必须给 subjectRefId");
    ErrorCode RECORD_SHARE_SUBJECT_CODE_REQUIRED = new ErrorCode(1_002_035_002,
            "{} 类型的主体必须给 subjectCode");

    // ========== 分端导航树 1-002-036-000 ==========
    // 036 是「移动端导航动态配置」新占的号段（见「移动端优化方案」§4.4）。
    // 与授权体系（030~035）无关：system_nav_node 是纯导航树，不承载授权判定
    ErrorCode NAV_NODE_NOT_EXISTS = new ErrorCode(1_002_036_000, "导航节点不存在");
    ErrorCode NAV_NODE_PARENT_NOT_EXISTS = new ErrorCode(1_002_036_001, "父导航节点不存在");
    ErrorCode NAV_NODE_PARENT_ERROR = new ErrorCode(1_002_036_002, "不能设置自己为父节点");
    ErrorCode NAV_NODE_PARENT_IS_CHILD = new ErrorCode(1_002_036_003, "不能设置自己的子节点为父节点");
    ErrorCode NAV_NODE_NAME_DUPLICATE = new ErrorCode(1_002_036_004, "同级下已存在该名字的导航节点");
    ErrorCode NAV_NODE_EXISTS_CHILDREN = new ErrorCode(1_002_036_005, "存在子节点，无法删除");
    ErrorCode NAV_NODE_TERMINAL_INVALID = new ErrorCode(1_002_036_006, "不支持的端标识：{}");

}
