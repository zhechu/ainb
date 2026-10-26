package cn.enlev.ainb.module.system.dal.dataobject.user;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 当前用户的个性化偏好。
 *
 * <p>偏好使用 JSON 保存，便于前端增加布局配置时保持向后兼容。</p>
 */
@TableName("system_user_preferences")
@KeySequence("system_user_preferences_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class UserPreferenceDO extends TenantBaseDO {

    /** 主键 */
    @TableId
    private Long id;

    /** 用户编号 */
    private Long userId;

    /** 主题与布局配置 JSON */
    private String themeConfig;

}
