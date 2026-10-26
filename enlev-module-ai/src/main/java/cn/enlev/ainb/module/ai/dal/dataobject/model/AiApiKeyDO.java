package cn.enlev.ainb.module.ai.dal.dataobject.model;

import cn.enlev.ainb.module.ai.enums.model.AiPlatformEnum;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import cn.enlev.ainb.module.ai.framework.type.AiApiKeyEncryptTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * AI API 秘钥 DO
 *
 * @author 芋道源码
 */
// ⚠️ autoResultMap 必须开：不开的话 @TableField 的 typeHandler
// 【只在写入时生效、查询时不生效】，表现为写进去是密文、读出来还是密文，
// 而且不报任何错 —— 直到模型调用被厂商以「密钥无效」拒绝才发现
@TableName(value = "ai_api_key", autoResultMap = true)
@KeySequence("ai_api_key_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiApiKeyDO extends BaseDO {

    /**
     * 编号
     */
    @TableId
    private Long id;
    /**
     * 名称
     */
    private String name;
    /**
     * 密钥
     *
     * 🔴 落库加密。{@code ${ENV_VAR}} 形式的占位符原样存 ——
     * 那是个环境变量引用，不是密钥本身。
     */
    @TableField(typeHandler = AiApiKeyEncryptTypeHandler.class)
    private String apiKey;
    /**
     * 平台
     *
     * 枚举 {@link AiPlatformEnum}
     */
    private String platform;
    /**
     * API 地址
     */
    private String url;
    /**
     * 状态
     *
     * 枚举 {@link CommonStatusEnum}
     */
    private Integer status;

}
