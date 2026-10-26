package cn.enlev.ainb.module.infra.api.formdesign.dto;

import lombok.Data;

import java.util.List;

/**
 * 表单设计 Response DTO
 *
 * 供 BPM 等其它模块使用。字段是「流程侧需要的最小集」，不暴露 FormDesignDO 的全部内容。
 *
 * @author 走进新时代
 */
@Data
public class FormDesignRespDTO {

    /**
     * 表单编码
     *
     * 流程模型与流程节点通过它引用本表单。发布后不可修改，因此可安全作为外键使用。
     */
    private String code;
    /**
     * 表单名称
     */
    private String name;
    /**
     * 分类
     */
    private String category;
    /**
     * 表单配置 JSON
     *
     * form-create 的 designer.getOption() 序列化结果，与 bpm_form.conf 同约定
     */
    private String formConf;
    /**
     * 表单字段规则 JSON 数组
     *
     * form-create 的 designer.getRule() 逐条序列化，与 bpm_form.fields 同约定。
     * 流程设计器直接消费它，无需任何格式转换
     */
    private List<String> formFields;
    /**
     * 主表名
     */
    private String mainTable;
    /**
     * 主表主键列名
     */
    private String mainPk;

    // ========== 流程就绪状态 ==========

    /**
     * 是否可用于流程
     *
     * false 时前端应置灰不可选，并展示 {@link #unavailableReason}
     */
    private Boolean flowReady;
    /**
     * 不可用原因
     *
     * flowReady 为 false 时非空。必须是可直接展示给用户、能指导其修复的具体描述，
     * 例如「缺少 process_instance_id 列，请在主表 xxx 补加 varchar(64) 列」。
     * 不要笼统写「表单不可用」—— 那会显著增加排障成本
     */
    private String unavailableReason;

}
