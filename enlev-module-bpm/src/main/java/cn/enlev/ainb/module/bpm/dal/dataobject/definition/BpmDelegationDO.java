package cn.enlev.ainb.module.bpm.dal.dataobject.definition;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 流程委托 DO
 *
 * <h3>⚠️ 晚绑定：委托不改变任务归属（v9 §7.2）</h3>
 *
 * 委托有两种实现方式，差别是决定性的：
 *
 * <table border="1">
 *   <tr><th></th><th>早绑定（改任务）</th><th>晚绑定（本实现）</th></tr>
 *   <tr><td>做法</td><td>建委托时把 assignee 改成受托人</td>
 *       <td>不动任务，只在<b>查待办</b>与<b>审批鉴权</b>时叠加</td></tr>
 *   <tr><td>委托前已有的待办</td><td>要额外扫一遍改过去，漏了就转不了</td>
 *       <td><b>天然生效</b></td></tr>
 *   <tr><td>委托到期</td><td>要再扫一遍改回来，漏了就一直是受托人的</td>
 *       <td><b>自动失效</b></td></tr>
 *   <tr><td>任务归属</td><td>被改写，事后看不出原本是谁的</td>
 *       <td><b>始终是委托人的</b>，审批记录也记在委托人名下</td></tr>
 * </table>
 *
 * 早绑定的两次「扫一遍」是漏数据的经典来源，而且漏了不会报错 ——
 * 表现是「他休假了，可单子还堆在他那儿没人能办」。
 * 所以本实现选晚绑定，代价是查待办时多一次委托查询。
 *
 * <h3>委托 ≠ 转办</h3>
 *
 * 转办是把这一个任务交出去、从此与我无关；
 * 委托是「我这段时间的事都请你帮忙看着」，任务还是我的，我回来后照常处理。
 *
 * @author 走进新时代
 */
@TableName("bpm_delegation")
@KeySequence("bpm_delegation_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BpmDelegationDO extends BaseDO {

    @TableId
    private Long id;

    /**
     * 委托人（把事情托付出去的人）
     *
     * 任务始终归他，审批记录也记在他名下
     */
    private Long delegatorUserId;

    /**
     * 受托人（代为处理的人）
     */
    private Long delegateUserId;

    /**
     * 生效开始时间
     */
    private LocalDateTime startTime;

    /**
     * 生效结束时间
     *
     * 为 null 表示长期有效。到期后<b>自动失效</b>，不需要任何清理动作 ——
     * 这正是晚绑定的好处
     */
    private LocalDateTime endTime;

    /**
     * 限定的流程定义 key
     *
     * 为空表示不限流程。多个用逗号分隔。
     * ⚠️ 用 key 而不是 definitionId —— definitionId 每次部署都变，
     * 拿它限定的话流程一改一部署委托就失效了（与 S5d 的流程资源同一个理由）
     */
    private String processDefinitionKeys;

    /**
     * 委托原因
     */
    private String reason;

    /**
     * 状态：0 开启，1 关闭
     *
     * 手工提前终止用。到期失效不需要改它
     */
    private Integer status;

}
