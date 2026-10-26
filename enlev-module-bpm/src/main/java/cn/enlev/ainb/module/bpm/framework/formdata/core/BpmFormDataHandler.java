package cn.enlev.ainb.module.bpm.framework.formdata.core;

import java.util.Map;

/**
 * 流程表单数据处理器
 *
 * 把三种表单类型的数据读写差异收敛到一个接口里，
 * 让流程链路（发起 / 审批 / 详情 / 状态回写）不必到处写 if (formType == ...) 分支。
 * 后续新增流程节点类型时，也不用在每个节点里重复三遍类型判断。
 *
 * 三种实现的语义差异（重要，不是遗漏）：
 * <ul>
 *     <li>流程表单(NORMAL)：数据即 Flowable 流程变量，pk 就是流程实例编号</li>
 *     <li>业务表单(CUSTOM)：数据由业务方自己的 Service 掌管，BPM 不介入，
 *         因此写操作都是空实现 —— 这是正确语义，不是没写完。业务方通过
 *         BpmProcessInstanceStatusEventListener 自行同步状态</li>
 *     <li>在线表单(ONLINE)：数据落到「表单设计」表绑定的物理表，pk 是业务主键</li>
 * </ul>
 *
 * @author 走进新时代
 */
public interface BpmFormDataHandler {

    /**
     * 获得处理的表单类型
     *
     * @return 表单类型，见 BpmModelFormTypeEnum
     */
    Integer getFormType();

    /**
     * 创建业务数据
     *
     * @param formKey 表单标识。NORMAL 为流程表单编号，ONLINE 为在线表单编码
     * @param data    表单数据
     * @return 业务主键。CUSTOM 返回 null
     */
    Object create(String formKey, Map<String, Object> data);

    /**
     * 更新业务数据
     *
     * @param formKey 表单标识
     * @param pk      业务主键
     * @param data    表单数据。调用方应已按节点表单权限裁剪过
     */
    void update(String formKey, Object pk, Map<String, Object> data);

    /**
     * 获得业务数据
     *
     * @param formKey 表单标识
     * @param pk      业务主键
     * @return 业务数据。不存在时返回 null
     */
    Map<String, Object> get(String formKey, Object pk);

    /**
     * 删除业务数据
     *
     * @param formKey 表单标识
     * @param pk      业务主键
     */
    void delete(String formKey, Object pk);

    /**
     * 回写流程状态
     *
     * @param formKey 表单标识
     * @param pk      业务主键
     * @param status  流程状态，见 BpmProcessInstanceStatusEnum
     */
    void updateStatus(String formKey, Object pk, Integer status);

}
