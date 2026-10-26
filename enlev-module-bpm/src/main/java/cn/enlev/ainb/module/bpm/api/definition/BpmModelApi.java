package cn.enlev.ainb.module.bpm.api.definition;

import cn.enlev.ainb.module.bpm.api.definition.dto.BpmModelCreateReqDTO;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmModelRespDTO;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmProcessConstraintsRespDTO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 流程模型 API 接口
 *
 * 供 agent 模块「用一句话建出审批流」使用。
 *
 * <h3>🔴 为什么只有一个「保存并部署」的方法</h3>
 *
 * 因为<b>中间态在这个平台上是个死胡同</b>：修改流程模型要求调用者是该流程的管理员，
 * 而管理员判定读的是流程资源上的授权，那行资源要<b>部署成功之后</b>才会登记。
 * 「先 create 出草稿、回头再 update 补内容、最后 deploy」这个看起来很自然的三步走，
 * 会卡死在第二步 —— 草稿谁都改不了。
 *
 * <p>所以对外只暴露一个动作：<b>带着完整的节点树进来，出去时流程已经在跑了</b>。
 *
 * <h3>⚠️ 流程标识全局唯一，重名走的是更新而非新建</h3>
 * {@code createModel} 撞上已存在的 key 会直接抛
 * 「已经存在流程标识为 xxx 的流程」，<b>不会</b>自动变成新版本 ——
 * 这一点与直觉相反，实测踩到过。
 * 因此本方法按 key 判断走创建还是走更新，两条路最后都以一次 deploy 收尾，
 * 由 deploy 产生新的流程定义版本并把旧版本挂起。
 *
 * @author 走进新时代
 */
public interface BpmModelApi {

    /**
     * 保存并部署流程模型（<b>一个原子动作</b>）
     *
     * <p>{@code key} 不存在则新建，已存在则更新那一条，最后统一部署。
     *
     * <h3>为什么必须合成一步</h3>
     * 拆成两步的话，deploy 失败就会留下一个<b>从未部署过</b>的模型 ——
     * 而那种模型对绝大多数人是只读的（管理员判定拿不到资源行），
     * 用户会得到一个自己既改不了也删不掉的半成品。
     * 合成一个事务，失败就整体回滚，库里干干净净。
     *
     * @param userId 操作人编号，同时用于管理员判定
     * @param reqDTO 保存参数，含完整的节点树
     * @return 流程模型编号
     */
    String saveAndDeployModel(Long userId, @Valid BpmModelCreateReqDTO reqDTO);

    /**
     * 获得流程模型列表
     *
     * @param name 流程名称，模糊匹配；为空则返回全部
     * @return 流程模型列表
     */
    List<BpmModelRespDTO> getModelList(String name);

    /**
     * 获得流程模型的节点树（JSON）
     *
     * @param id 流程模型编号
     * @return 节点树 JSON；模型不存在、或不是仿钉钉设计器的模型时返回 null
     */
    String getModelSimpleJson(String id);

    /**
     * 获得流程生成规则
     *
     * 校验器与 prompt 的<b>唯一</b>规则来源，不要在调用方自己抄常量。
     *
     * @return 生成规则
     */
    BpmProcessConstraintsRespDTO getProcessConstraints();

}
