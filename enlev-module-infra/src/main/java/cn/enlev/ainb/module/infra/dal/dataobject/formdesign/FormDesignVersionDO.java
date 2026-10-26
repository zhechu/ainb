package cn.enlev.ainb.module.infra.dal.dataobject.formdesign;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import cn.enlev.ainb.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 表单设计版本快照 DO
 *
 * 每次发布时把「发布的那份配置」存为一个版本，因此：
 * - 版本 N 的快照 = 第 N 次发布的状态
 * - 主表当前记录 = 最新草稿状态
 * - 回滚 = 把某版本快照写回主表草稿，并再产生一个新版本（回滚本身也留痕）
 *
 * 存整份快照而非字段级 diff：配置项多且结构会演进，存快照最简单可靠，
 * 比对交给前端做。此范式与 {@link cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceVersionDO} 一致。
 *
 * 每个表单只保留最新 100 个版本，超出时【物理删除】version 最小的记录 ——
 * 逻辑删除会堆积且与 uk_design_version 唯一索引冲突。
 *
 * @author 走进新时代
 */
@TableName("infra_form_design_version")
@KeySequence("infra_form_design_version_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TenantIgnore
public class FormDesignVersionDO extends BaseDO {

    /**
     * 版本保留数量上限
     */
    public static final int MAX_KEEP_VERSIONS = 100;

    /**
     * 编号
     */
    @TableId
    private Long id;
    /**
     * 表单设计编号
     */
    private Long designId;
    /**
     * 版本号，从 1 递增
     */
    private Integer version;
    /**
     * 该版本的完整配置快照 JSON
     *
     * 内容为发布那一刻的：name / code / category / formType / formConf / formFields
     * / listConf / dataSourceConfigId / mainTable / mainPk / tableBinding。
     */
    private String snapshot;
    /**
     * 变更说明
     */
    private String changeSummary;

}
