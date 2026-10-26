package cn.enlev.ainb.module.infra.framework.datainterface.core.executor;

import cn.enlev.ainb.module.infra.enums.datainterface.DataInterfaceTypeEnum;

/**
 * 数据接口执行器
 *
 * 策略模式：三种数据来源（SQL / 静态 JSON / HTTP 转发）各一个实现，
 * 新增来源类型只需加一个实现类并注册为 Spring Bean，编排层无需改动。
 *
 * @author 走进新时代
 */
public interface InterfaceExecutor {

    /**
     * 支持的接口类型
     */
    DataInterfaceTypeEnum getType();

    /**
     * 执行接口
     *
     * @param context 执行上下文
     * @return 执行结果。SQL 查询返回 List&lt;Map&gt;；写操作返回影响行数；
     *         静态类型返回配置的 JSON 对象；HTTP 返回对方响应体解析后的对象
     */
    Object execute(InterfaceExecuteContext context);

}
