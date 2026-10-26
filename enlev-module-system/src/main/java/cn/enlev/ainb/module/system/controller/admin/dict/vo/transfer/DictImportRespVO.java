package cn.enlev.ainb.module.system.controller.admin.dict.vo.transfer;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字典迁移包的导入结果
 *
 * <h3>为什么按分类逐条回报，而不是只给几个总数</h3>
 * 导入一棵子树可能同时发生「新建了 3 个、更新了 2 个、有 1 个因为名称被占用没进去」。
 * 只给总数的话，用户知道有东西失败了，却不知道是哪一个、为什么——
 * 只能自己对着左树一个个找。逐条回报之后，界面可以直接把失败项列成表格。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 字典迁移包导入结果")
@Data
public class DictImportRespVO {

    @Schema(description = "分类维度的导入结果，顺序与包内一致")
    private List<TypeResult> types = new ArrayList<>();

    /**
     * 单个分类的处理结果
     */
    @Schema(description = "管理后台 - 单个分类的导入结果")
    @Data
    @Builder
    public static class TypeResult {

        /** 新建 */
        public static final String ACTION_CREATED = "created";
        /** 更新已存在的分类 */
        public static final String ACTION_UPDATED = "updated";
        /** 分类已存在，保持原样 */
        public static final String ACTION_SKIPPED = "skipped";
        /** 以副本形式新建，编码与名称带后缀 */
        public static final String ACTION_RENAMED = "renamed";
        /** 没能导入 */
        public static final String ACTION_FAILED = "failed";

        @Schema(description = "包里的分类编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "sys_common_sex")
        private String type;

        @Schema(description = "落库后的分类编码；副本策略下与 type 不同", example = "sys_common_sex_2")
        private String savedType;

        @Schema(description = "落库后的分类名称", example = "性别（副本 2）")
        private String name;

        @Schema(description = "处理动作：created / updated / skipped / renamed / failed",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "created")
        private String action;

        @Schema(description = "失败原因；action 为 failed 时才有", example = "该名称已被其它分类占用")
        private String reason;

        @Schema(description = "本分类下新增的字典数据条数", example = "3")
        private Integer dataCreateCount;

        @Schema(description = "本分类下更新的字典数据条数", example = "1")
        private Integer dataUpdateCount;

        @Schema(description = "本分类下因为已存在而被跳过的字典数据条数；仅「跳过」策略下会有", example = "5")
        private Integer dataSkipCount;

        @Schema(description = "本分类下没能导入的字典数据：键值 -> 原因")
        private Map<String, String> dataFailures;

        public static TypeResultBuilder of(DictTypeTransferVO item) {
            return TypeResult.builder()
                    .type(item.getType())
                    .name(item.getName())
                    .dataCreateCount(0)
                    .dataUpdateCount(0)
                    .dataSkipCount(0)
                    .dataFailures(new LinkedHashMap<>());
        }

    }

}
