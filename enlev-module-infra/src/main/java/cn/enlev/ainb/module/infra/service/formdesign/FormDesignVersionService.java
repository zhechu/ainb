package cn.enlev.ainb.module.infra.service.formdesign;

import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignVersionDO;

import java.util.List;

/**
 * 表单设计版本 Service 接口
 *
 * 版本机制：每次发布把「发布的那份配置」存为一个版本快照。
 * - 版本 N 的快照 = 第 N 次发布的状态
 * - 主表当前记录 = 最新草稿状态
 * - 回滚 = 把某版本快照写回主表草稿，回滚本身不产生新版本（下次发布才会）
 *
 * 每个表单只保留最新 {@link FormDesignVersionDO#MAX_KEEP_VERSIONS} 个版本。
 *
 * @author 走进新时代
 */
public interface FormDesignVersionService {

    /**
     * 创建版本快照
     *
     * 版本号取该表单已有最大版本号 + 1。创建后会裁剪超出保留上限的旧版本。
     *
     * @param formDesign    发布时的表单设计（快照来源）
     * @param changeSummary 变更说明
     * @return 新版本号
     */
    Integer createVersion(FormDesignDO formDesign, String changeSummary);

    /**
     * 获得指定表单的版本列表，按版本号倒序
     *
     * @param designId 表单设计编号
     * @return 版本列表
     */
    List<FormDesignVersionDO> getVersionList(Long designId);

    /**
     * 获得指定版本
     *
     * @param designId 表单设计编号
     * @param version  版本号
     * @return 版本快照
     */
    FormDesignVersionDO getVersion(Long designId, Integer version);

    /**
     * 回滚到指定版本
     *
     * 把该版本的快照写回主表草稿，状态置为「已发布有改动」——
     * 回滚只改草稿，线上版本仍是发布过的那个，需要用户再点发布才会生效。
     * 这样用户回滚后还有机会检查再决定是否上线。
     *
     * @param designId 表单设计编号
     * @param version  目标版本号
     */
    void rollback(Long designId, Integer version);

    /**
     * 删除指定表单的所有版本
     *
     * @param designId 表单设计编号
     */
    void deleteByDesignId(Long designId);

}
