package cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 取号管理 Response VO")
@Data
public class BillTemplateRespVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "模板名称", example = "采购订单号")
    private String name;

    @Schema(description = "模板编码", example = "purchase_order")
    private String code;

    @Schema(description = "分类", example = "business")
    private String category;

    @Schema(description = "生成方式：1-时间流水 2-随机数 3-UUID", example = "1")
    private Integer genType;

    @Schema(description = "前缀", example = "CG")
    private String prefix;

    @Schema(description = "后缀")
    private String suffix;

    @Schema(description = "日期格式", example = "yyyyMMdd")
    private String dateFormat;

    @Schema(description = "流水位数", example = "4")
    private Integer digit;

    @Schema(description = "流水起始值", example = "1")
    private Integer startNumber;

    @Schema(description = "流水步长", example = "1")
    private Integer step;

    @Schema(description = "重置周期", example = "AUTO")
    private String resetCycle;

    /**
     * AUTO 解析后的实际周期
     *
     * 由后端算好下发，前端不重复实现解析规则。列表页要展示「实际按什么周期重置」，
     * 若前端自己照着日期格式推一遍，规则一旦调整两边就会对不上。
     */
    @Schema(description = "实际重置周期。AUTO 解析后的结果", example = "DAY")
    private String resetCycleActual;

    @Schema(description = "流水是否按前缀分组重置", example = "false")
    private Boolean groupByPrefix;

    @Schema(description = "随机位数", example = "16")
    private Integer randomDigit;

    @Schema(description = "随机类型：1-纯数字 2-字母+数字", example = "1")
    private Integer randomType;

    @Schema(description = "是否记录取号日志", example = "false")
    private Boolean logEnabled;

    @Schema(description = "范例", example = "CG202609060001")
    private String example;

    @Schema(description = "最近一次生成的号码", example = "CG202609060012")
    private String lastNo;

    @Schema(description = "最近一次的周期标记", example = "20260906")
    private String lastPeriod;

    /**
     * 来自 DB，是「上次出号后回写的值」。权威值在 Redis，
     * 若回写失败，这里可能落后于真实值 —— 仅作参考。
     */
    @Schema(description = "当前流水值（展示用）", example = "12")
    private Long currentNumber;

    @Schema(description = "状态：0-开启 1-停用", example = "0")
    private Integer status;

    @Schema(description = "排序", example = "0")
    private Integer sort;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建者")
    private String creator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新者")
    private String updater;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

}
