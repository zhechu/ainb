package cn.enlev.ainb.module.infra.service.formdesign.runtime;

import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormImportPreviewReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormImportPreviewRespVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormImportReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormImportRespVO;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;

/**
 * 表单数据导入 Service
 *
 * <h3>为什么导入不复用「直接 insert」的做法</h3>
 *
 * 参考实现里导入就是循环调 mapper 的 insert。那样导进去的数据与用户手工录的
 * **不是同一种东西**：没有单据编号、公式没算、审计列没填、变更日志没记、
 * 提交后脚本没跑、服务端扩展点也没过。
 *
 * 本模块的导入一律走 {@link FormDesignRuntimeService#create} 与
 * {@link FormDesignRuntimeService#update} —— 于是「手工录入能拦住的，
 * 导入也一定拦得住」，因为走的就是同一段代码。
 *
 * 导入自己的逻辑全在这两个方法**外面**：解析、表头映射、逐行干跑、
 * 重复策略、结果汇总。
 *
 * @author 走进新时代
 */
public interface FormImportService {

    /**
     * 预览导入
     *
     * 解析文件并逐行干跑校验，返回表头映射、逐行状态与统计。**不写库**。
     *
     * @param code    表单编码
     * @param input   Excel 输入流。调用方负责关闭
     * @param reqVO   请求
     * @return 预览结果
     */
    FormImportPreviewRespVO preview(String code, InputStream input,
                                    FormImportPreviewReqVO reqVO) throws IOException;

    /**
     * 执行导入
     *
     * 必须带上预览返回的 importToken，且同一个 token 只能消费一次 ——
     * 防的是「用户不确定有没有成功，又点了一次」这种最常见的重复导入。
     *
     * @param code  表单编码
     * @param reqVO 请求
     * @return 导入结果
     */
    FormImportRespVO execute(String code, FormImportReqVO reqVO);

    /**
     * 生成并下载导入模板
     *
     * @param code     表单编码
     * @param response HTTP 响应
     */
    void downloadTemplate(String code, HttpServletResponse response) throws IOException;

}
