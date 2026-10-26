package cn.enlev.ainb.server.controller;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.util.servlet.ServletUtils;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.enlev.ainb.framework.common.exception.enums.GlobalErrorCodeConstants.NOT_IMPLEMENTED;

/**
 * 默认 Controller，解决部分 module 未开启时的 404 提示。
 * 例如说，/bpm/** 路径，工作流
 *
 * @author 芋道源码
 */
@RestController
@Slf4j
public class DefaultController {

    @RequestMapping("/admin-api/bpm/**")
    public CommonResult<Boolean> bpm404() {
        return CommonResult.error(NOT_IMPLEMENTED.getCode(),
                "[工作流模块 enlev-module-bpm - 已禁用][参考 https://doc.enlev.cn/bpm/ 开启]");
    }

    @RequestMapping("/admin-api/mp/**")
    public CommonResult<Boolean> mp404() {
        return CommonResult.error(NOT_IMPLEMENTED.getCode(),
                "[微信公众号 enlev-module-mp - 已禁用][参考 https://doc.enlev.cn/mp/build/ 开启]");
    }

    @RequestMapping(value = { "/admin-api/product/**", // 商品中心
            "/admin-api/trade/**", // 交易中心
            "/admin-api/promotion/**" }) // 营销中心
    public CommonResult<Boolean> mall404() {
        return CommonResult.error(NOT_IMPLEMENTED.getCode(),
                "[商城系统 enlev-module-mall - 已禁用][参考 https://doc.enlev.cn/mall/build/ 开启]");
    }

    @RequestMapping("/admin-api/erp/**")
    public CommonResult<Boolean> erp404() {
        return CommonResult.error(NOT_IMPLEMENTED.getCode(),
                "[ERP 模块 enlev-module-erp - 已禁用][参考 https://doc.enlev.cn/erp/build/ 开启]");
    }

    @RequestMapping(value = { "/admin-api/wms/**"})
    public CommonResult<Boolean> wms404() {
        return CommonResult.error(NOT_IMPLEMENTED.getCode(),
                "[WMS 仓库管理系统 enlev-module-wms - 已禁用][参考 https://doc.enlev.cn/wms/build/ 开启]");
    }

    @RequestMapping("/admin-api/crm/**")
    public CommonResult<Boolean> crm404() {
        return CommonResult.error(NOT_IMPLEMENTED.getCode(),
                "[CRM 模块 enlev-module-crm - 已禁用][参考 https://doc.enlev.cn/crm/build/ 开启]");
    }

    @RequestMapping(value = { "/admin-api/mes/**"})
    public CommonResult<Boolean> mes404() {
        return CommonResult.error(NOT_IMPLEMENTED.getCode(),
                "[MES 系统 enlev-module-mes - 已禁用][参考 https://doc.enlev.cn/mes/build/ 开启]");
    }

    @RequestMapping(value = { "/admin-api/im/**"})
    public CommonResult<Boolean> im404() {
        return CommonResult.error(NOT_IMPLEMENTED.getCode(),
                "[IM 即时通讯 enlev-module-im - 已禁用][参考 https://doc.enlev.cn/im/build/ 开启]");
    }

    @RequestMapping(value = { "/admin-api/report/**"})
    public CommonResult<Boolean> report404() {
        return CommonResult.error(NOT_IMPLEMENTED.getCode(),
                "[报表模块 enlev-module-report - 已禁用][参考 https://doc.enlev.cn/report/ 开启]");
    }

    @RequestMapping(value = { "/admin-api/pay/**"})
    public CommonResult<Boolean> pay404() {
        return CommonResult.error(NOT_IMPLEMENTED.getCode(),
                "[支付模块 enlev-module-pay - 已禁用][参考 https://doc.enlev.cn/pay/build/ 开启]");
    }

    @RequestMapping(value = { "/admin-api/ai/**"})
    public CommonResult<Boolean> ai404() {
        return CommonResult.error(NOT_IMPLEMENTED.getCode(),
                "[AI 大模型 enlev-module-ai - 已禁用][参考 https://doc.enlev.cn/ai/build/ 开启]");
    }

    @RequestMapping(value = { "/admin-api/iot/**"})
    public CommonResult<Boolean> iot404() {
        return CommonResult.error(NOT_IMPLEMENTED.getCode(),
                "[IoT 物联网 enlev-module-iot - 已禁用][参考 https://doc.enlev.cn/iot/build/ 开启]");
    }

    /**
     * 测试接口：打印 query、header、body
     */
    @RequestMapping(value = { "/test" })
    @PermitAll
    public CommonResult<Boolean> test(HttpServletRequest request) {
        // 打印查询参数
        log.info("Query: {}", ServletUtils.getParamMap(request));
        // 打印请求头
        log.info("Header: {}", ServletUtils.getHeaderMap(request));
        // 打印请求体
        log.info("Body: {}", ServletUtils.getBody(request));
        return CommonResult.success(true);
    }

}
