package cn.enlev.ainb.module.system.dal.redis;

import cn.enlev.ainb.module.system.dal.dataobject.oauth2.OAuth2AccessTokenDO;

/**
 * System Redis Key 枚举类
 *
 * @author 芋道源码
 */
public interface RedisKeyConstants {

    /**
     * 指定部门的所有子部门编号数组的缓存
     * <p>
     * KEY 格式：dept_children_ids:{id}
     * VALUE 数据类型：String 子部门编号集合
     */
    String DEPT_CHILDREN_ID_LIST = "dept_children_ids";

    /**
     * 角色的缓存
     * <p>
     * KEY 格式：role:{id}
     * VALUE 数据类型：String 角色信息
     */
    String ROLE = "role";

    /**
     * 用户拥有的角色编号的缓存
     * <p>
     * KEY 格式：user_role_ids:{userId}
     * VALUE 数据类型：String 角色编号集合
     */
    String USER_ROLE_ID_LIST = "user_role_ids";

    /**
     * 拥有指定菜单的角色编号的缓存
     * <p>
     * KEY 格式：user_role_ids:{menuId}
     * VALUE 数据类型：String 角色编号集合
     */
    String MENU_ROLE_ID_LIST = "menu_role_ids";

    /**
     * 拥有权限对应的菜单编号数组的缓存
     * <p>
     * KEY 格式：permission_menu_ids:{permission}
     * VALUE 数据类型：String 菜单编号数组
     */
    String PERMISSION_MENU_ID_LIST = "permission_menu_ids";

    /**
     * OAuth2 客户端的缓存
     * <p>
     * KEY 格式：oauth_client:{id}
     * VALUE 数据类型：String 客户端信息
     */
    String OAUTH_CLIENT = "oauth_client";

    /**
     * 访问令牌的缓存
     * <p>
     * KEY 格式：oauth2_access_token:{token}
     * VALUE 数据类型：String 访问令牌信息 {@link OAuth2AccessTokenDO}
     * <p>
     * 由于动态过期时间，使用 RedisTemplate 操作
     */
    String OAUTH2_ACCESS_TOKEN = "oauth2_access_token:%s";

    /**
     * 站内信模版的缓存
     * <p>
     * KEY 格式：notify_template:{code}
     * VALUE 数据格式：String 模版信息
     */
    String NOTIFY_TEMPLATE = "notify_template";

    /**
     * 邮件账号的缓存
     * <p>
     * KEY 格式：mail_account:{id}
     * VALUE 数据格式：String 账号信息
     */
    String MAIL_ACCOUNT = "mail_account";

    /**
     * 邮件模版的缓存
     * <p>
     * KEY 格式：mail_template:{code}
     * VALUE 数据格式：String 模版信息
     */
    String MAIL_TEMPLATE = "mail_template";

    /**
     * 短信模版的缓存
     * <p>
     * KEY 格式：sms_template:{id}
     * VALUE 数据格式：String 模版信息
     */
    String SMS_TEMPLATE = "sms_template";

    /**
     * 小程序订阅模版的缓存
     *
     * KEY 格式：wxa_subscribe_template:{userType}
     * VALUE 数据格式 String, 模版信息
     */
    String WXA_SUBSCRIBE_TEMPLATE = "wxa_subscribe_template";

    /**
     * 主体成员关系图的祖先闭包
     * <p>
     * KEY 格式：subject_ancestors:all（多租户下由 TenantRedisCacheManager 自动拼租户后缀）
     * VALUE 数据格式：String，Map&lt;成员寻址串, Set&lt;祖先容器寻址串&gt;&gt;
     * <p>
     * ⚠️ 整张图**一个 key**，不是每个主体一个 key。
     * 这张图只有角色继承边与用户组成员边，量级千行以内；
     * 拆成每主体一个 key 的话，一次闭包要几十次 Redis 往返，
     * 反而打不到 v9 §8.2「命中缓存 &lt; 0.5ms」的门槛。
     * <p>
     * ⚠️ **不要**加进 enlev.tenant.ignore-caches：这张图是租户内的数据，
     * 必须带租户后缀，否则会串租户
     */
    String SUBJECT_ANCESTORS = "subject_ancestors";

    /**
     * 用户的允许权限标识集合缓存（grant-enabled 模式）
     * <p>
     * KEY 格式：grant_user_permissions:{userId}
     * VALUE 数据类型：String，Set&lt;权限标识串&gt;
     * <p>
     * TTL 30s —— 授权配置变更（sys_grant 增删改）时主动 evict allEntries。
     * 失效设计：一条授权行挂在角色上，影响该角色的全部用户；
     * 无法在 O(1) 时间内算出受影响的 userId 集合，故整体清空而非逐用户清。
     */
    String GRANT_USER_PERMISSIONS = "grant_user_permissions";

    /**
     * 用户的允许菜单编号集合缓存（grant-enabled 模式）
     * <p>
     * KEY 格式：grant_user_menu_ids:{userId}
     * VALUE 数据类型：String，Set&lt;menuId&gt;
     * <p>
     * 与 {@link #GRANT_USER_PERMISSIONS} 同步失效 —— 两者由同一批 sys_grant 行驱动。
     */
    String GRANT_USER_MENU_IDS = "grant_user_menu_ids";

}
