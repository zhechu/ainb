package cn.enlev.ainb.module.infra.framework.datainterface.core.executor;

import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceDO;
import cn.enlev.ainb.module.infra.framework.datainterface.core.param.ParamDefinition;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

/**
 * 接口执行上下文
 *
 * 一次执行所需的全部输入，以及执行过程中产生的、需要回传给调用方的信息
 * （如实际执行的 SQL，要落进调用日志）。
 *
 * @author 走进新时代
 */
@Data
@Accessors(chain = true)
public class InterfaceExecuteContext {

    /**
     * 接口定义
     */
    private DataInterfaceDO dataInterface;
    /**
     * 入参定义
     */
    private List<ParamDefinition> paramDefinitions;
    /**
     * 请求参数
     */
    private Map<String, Object> requestArgs;
    /**
     * 是否为在线预览
     *
     * 预览会强制限制返回条数，避免误配的全表查询卡住页面
     */
    private boolean preview;
    /**
     * 预览时的最大返回条数
     */
    private Integer previewLimit;

    // ==================== 执行过程产出（回传用） ====================

    /**
     * 实际执行的 SQL，含自动注入的租户条件
     *
     * 落进调用日志。排查「为什么查不到数据」时这是最直接的证据。
     */
    private String executeSql;

    /**
     * 当前请求的 Authorization 头
     *
     * HTTP 转发时，若目标是相对路径（本系统接口），需透传此头以通过权限验证
     */
    private String authorization;

    /**
     * 当前请求的 Cookie 头
     *
     * HTTP 转发时，若目标是相对路径（本系统接口），需透传此头以保持会话状态
     */
    private String cookie;

    /**
     * 当前请求的 tenant-id 头
     *
     * HTTP 转发时，若目标是相对路径（本系统接口），需透传此头以保持租户隔离
     */
    private String tenantId;

}
