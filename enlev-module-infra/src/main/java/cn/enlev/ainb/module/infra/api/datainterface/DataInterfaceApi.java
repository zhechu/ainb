package cn.enlev.ainb.module.infra.api.datainterface;

import cn.enlev.ainb.module.infra.api.datainterface.dto.DataInterfaceSimpleRespDTO;

import java.util.List;
import java.util.Map;

/**
 * 数据接口 API 接口
 *
 * 供 agent 模块的「数据接口工具」使用：把平台已治理的数据接口目录
 * （{@code infra_data_interface}：SQL 查询 / HTTP 转发 / 静态 JSON）暴露给 AI 调用。
 *
 * 设计约定（与 {@link cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi} 同风格）：
 * 1. 一律以 code 而非 id 作为执行入参 —— code 是调用方视角的稳定标识
 * 2. 薄委托 DataInterfaceService，参数校验（必填/类型）与输出整形都由 service 完成，
 *    本层不重复做任何业务判断
 * 3. 🔴 <b>本 API 不做任何权限校验</b>，调用方必须自己复刻 infra Controller 的口径：
 *    {@link #queryData} 对应只需登录态的 {@code /options} 端点（任何登录用户今天就能
 *    经它执行任意启用的只读接口，故 agent 侧挂 agent:assistant:use 不扩大暴露面）；
 *    {@link #invokeData} 对应 {@code /invoke} 端点的 {@code infra:data-interface:invoke}
 *    权限点 —— agent 工具把它声明为 @AgentTool.permission，由 AgentSecurityGateway 强制
 * 4. 接口不存在 / 未启用 / 参数缺失 / 只读约束拦截，都抛 infra 的 ServiceException
 *    （中文可读），原样冒泡给调用方（AI 可据此自修正），本层不包新错误码
 *
 * @author 走进新时代
 */
public interface DataInterfaceApi {

    /**
     * 获得启用中的数据接口目录（摘要，不含 config/script 实现细节）
     *
     * 目录可能很大（现网 380+ 条），必须带 keyword/limit 收敛结果，
     * 否则一次全量列出会把调用方（AI 上下文）撑爆。
     *
     * @param keyword      关键字，模糊匹配 code/name（忽略大小写）；空则不过滤
     * @param onlyReadOnly true 时仅返回读取型接口（判定以 service 的只读清单为权威）；null/false 返回全部启用接口
     * @param limit        最多返回条数；null 默认 20，钳制在 [1, 50]
     * @return 接口摘要列表
     */
    List<DataInterfaceSimpleRespDTO> listInterfaces(String keyword, Boolean onlyReadOnly, Integer limit);

    /**
     * 只读执行数据接口
     *
     * 委托 {@code DataInterfaceService.invokeReadOnly}：实现层强制只允许读取型接口
     * （SQL 仅 SELECT、HTTP 仅 GET/HEAD、静态 JSON 放行），对写型接口调用会被拒绝（抛 ServiceException）。
     *
     * @param code   接口编码
     * @param params 请求参数（按接口的入参定义传；无参接口传空 Map 即可）
     * @return 执行结果（service 已整形：查询为 {code,msg,data} 或 {total,list}，HTTP 为解析后的 JSON 等）
     */
    Object queryData(String code, Map<String, Object> params);

    /**
     * 全能力执行数据接口（<b>含写</b>：INSERT/UPDATE/DELETE、HTTP POST 等）
     *
     * 委托 {@code DataInterfaceService.invoke}。🔴 调用方必须先校验
     * {@code infra:data-interface:invoke} 权限（见类注释约定 3），否则就是越权执行写接口。
     *
     * @param code   接口编码
     * @param params 请求参数
     * @return 执行结果（写操作为影响行数等，形状由 service 决定）
     */
    Object invokeData(String code, Map<String, Object> params);

}
