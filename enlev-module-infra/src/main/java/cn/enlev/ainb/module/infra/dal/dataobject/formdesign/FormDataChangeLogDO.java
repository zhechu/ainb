package cn.enlev.ainb.module.infra.dal.dataobject.formdesign;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 表单数据变更日志 DO
 *
 * <h3>一次更新一条记录，而不是一个字段一条</h3>
 *
 * 它的用途是详情页上的**变更时间线**：「张三 在 12:00 把金额从 10 改成 20、
 * 备注从 A 改成 B」。做成一字段一条的话，一次改 10 个字段就是 10 条记录，
 * 渲染时还得按「时间 + 人」重新归组 —— 而归组的依据（同一次提交）恰恰是
 * 这里天然就有的东西。
 *
 * 代价是「谁改过金额这个字段」不好查（要在 JSON 里搜）。按目前的用途取这一边：
 * 真出现按字段追溯的需求时，再加一张按字段展开的表比反过来容易。
 *
 * <h3>不加 @TenantIgnore</h3>
 *
 * 与版本快照（那是表单**配置**，全租户共享）不同，这里记的是租户的**业务数据**，
 * 必须按租户隔离 —— 少了它，A 租户能看到 B 租户的数据改成了什么。
 *
 * @author 走进新时代
 */
@TableName("infra_form_design_data_log")
@KeySequence("infra_form_design_data_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class FormDataChangeLogDO extends BaseDO {

    /**
     * 编号
     */
    @TableId
    private Long id;
    /**
     * 表单编码
     */
    private String formCode;
    /**
     * 业务数据的主键值
     *
     * 用字符串存：主键可能是雪花 long、自增 long，也可能是 UUID 字符串。
     * 存成 bigint 的话，UUID 主键的表单就记不了日志，而那种表单占比不低。
     */
    private String dataId;
    /**
     * 变更内容 JSON 数组
     *
     * 形如 {@code [{"field":"amount","title":"金额","oldValue":"10","newValue":"20"}]}。
     */
    private String changes;

}
