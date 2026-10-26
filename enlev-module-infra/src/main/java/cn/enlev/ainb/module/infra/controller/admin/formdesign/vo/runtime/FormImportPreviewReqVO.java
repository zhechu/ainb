package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/**
 * 表格导入预览 Request VO
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 表格导入预览 Request VO")
@Data
public class FormImportPreviewReqVO {

    @Schema(description = "上传的 Excel 文件", requiredMode = Schema.RequiredMode.REQUIRED)
    private MultipartFile file;

    @Schema(description = "更新键的字段名列表。用于判断「这一行在库里已存在」。"
            + "不传时按表单上配了唯一性校验的字段推断",
            example = "[\"supplier_code\"]")
    private java.util.List<String> updateKeyFields;

}
