package cn.enlev.ainb.module.infra.service.datainterface;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.infra.controller.admin.datainterface.vo.DataInterfaceLogPageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.datainterface.vo.DataInterfacePageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.datainterface.vo.DataInterfaceSaveReqVO;
import cn.enlev.ainb.module.infra.controller.admin.datainterface.vo.DataInterfaceSqlDetectRespVO;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceDO;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceLogDO;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceVersionDO;

import java.util.List;
import java.util.Map;

/**
 * 数据接口 Service 接口
 *
 * @author 走进新时代
 */
public interface DataInterfaceService {

    /**
     * 创建数据接口
     */
    Long createDataInterface(DataInterfaceSaveReqVO createReqVO);

    /**
     * 更新数据接口
     *
     * 更新前会把「修改前的配置」存为一个版本快照，便于比对与回滚
     */
    void updateDataInterface(DataInterfaceSaveReqVO updateReqVO);

    /**
     * 删除数据接口，连带删除版本历史
     */
    void deleteDataInterface(Long id);

    /**
     * 更新状态（启用/停用）
     */
    void updateStatus(Long id, Integer status);

    DataInterfaceDO getDataInterface(Long id);

    PageResult<DataInterfaceDO> getDataInterfacePage(DataInterfacePageReqVO pageReqVO);

    /**
     * 探测 SQL 里实际包含的语句类型
     *
     * 给配置界面用：用户写完 SQL 后，前端拿这个结果和「用户选的动作类型」比对，
     * 不一致就当场提示，不用等到点保存才报错。
     *
     * 只做解析、不做拦截 —— 真正的校验在 create/update 里。
     *
     * @param sql 用户配置的 SQL，可含 #{xx} 占位符
     * @return 探测结果
     */
    DataInterfaceSqlDetectRespVO detectSql(String sql);

    // ==================== 执行 ====================

    /**
     * 在线预览
     *
     * 与正式调用走同一套执行逻辑，只是限制返回条数并记为「预览」类型日志 ——
     * 这样能保证「预览能跑通 = 正式调用也能跑通」，不出现两套行为。
     *
     * @param id          接口编号
     * @param requestArgs 请求参数
     * @return 执行结果，含数据与实际执行的 SQL
     */
    Map<String, Object> preview(Long id, Map<String, Object> requestArgs);

    /**
     * 调用接口
     *
     * @param code        接口编码
     * @param requestArgs 请求参数
     * @return 执行结果数据
     */
    Object invoke(String code, Map<String, Object> requestArgs);

    /**
     * 只读调用。
     *
     * 供运行时消费方使用（门户组件、表单控件的选项取数等），避免它们依赖数据接口的管理权限；
     * 实现层强制只允许读取型接口：SQL 仅 SELECT、HTTP 仅 GET/HEAD、静态 JSON 放行。
     */
    Object invokeReadOnly(String code, Map<String, Object> requestArgs);

    /**
     * 获得可用于运行时取数的接口列表。
     *
     * 只返回启用中的读取型接口 —— 表单设计器选接口时列出来的就该是「选了一定能用」的那批，
     * 而不是让用户选完才在运行时报错。
     */
    List<DataInterfaceDO> getReadOnlySimpleList();

    /**
     * 获得全部启用中的接口（含写型）
     *
     * 只给<b>设计期</b>用：表单设计者要给自定义按钮选一个「作废单据」这类写型接口，
     * 而 {@link #getReadOnlySimpleList()} 刻意只返回读取型（那条服务的是运行时取数）。
     *
     * ⚠️ 两个清单不能合并成一个：取数那条是<b>只需登录态</b>的运行时端点，
     * 把写型接口放进去等于把它们暴露给每一个填单人。
     */
    List<DataInterfaceDO> getEnabledSimpleList();

    // ==================== 版本管理 ====================

    /**
     * 获得版本分页
     */
    PageResult<DataInterfaceVersionDO> getVersionPage(Long interfaceId, PageParam pageParam);

    /**
     * 获得指定版本的快照
     */
    DataInterfaceVersionDO getVersion(Long interfaceId, Integer version);

    /**
     * 回滚到指定版本
     *
     * 回滚本身也会产生一个新版本，保证操作可追溯
     */
    void rollbackVersion(Long interfaceId, Integer version);

    // ==================== 调用日志 ====================

    PageResult<DataInterfaceLogDO> getLogPage(DataInterfaceLogPageReqVO pageReqVO);

}
