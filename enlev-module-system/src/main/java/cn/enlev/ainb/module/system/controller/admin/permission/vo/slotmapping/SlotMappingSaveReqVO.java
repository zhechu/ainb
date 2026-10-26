package cn.enlev.ainb.module.system.controller.admin.permission.vo.slotmapping;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 语义槽位映射新增 / 修改请求 VO
 *
 * <h3>表名与列名都是必填，且都要过标识符正则</h3>
 *
 * 列名会被拼进 SQL（值走参数绑定，列名没法绑定），所以正则校验是防注入的第一道；
 * 服务端还会核实这一列在 {@code tableName} 上真的存在 —— 存一个不存在的列名，
 * 后果不是报错而是<b>规则静默求不出来</b>，进而整条授权按「求不出来」失效。
 * 在写入口拦掉，比让管理员配完再去猜为什么权限不生效要好。
 *
 * <h3>槽位名同样过正则，但理由完全不同</h3>
 *
 * 槽位名<b>不会</b>被拼进 SQL，它只是求值时那张「槽位 → 列」map 的 key。
 * 拦它是为了防另一种静默失效：{@code RecordOwnerResolver} 与行过滤求值都拿
 * {@code PermissionSlotEnum} 的字面值精确匹配，于是 {@code " owner"} 建得出来、
 * 却永远没有规则会读它 —— 而它在列表里和 {@code "owner"} 看着一模一样。
 * 这种错必须当场拒，不能靠事后 trim 悄悄修好：悄悄修好等于不告诉用户他打错了什么。
 */
@Schema(description = "管理后台 - 语义槽位映射新增/修改请求")
@Data
public class SlotMappingSaveReqVO {

    /** 表名 / 列名 / 槽位名共用的标识符正则，与 ConditionTreeEvaluator 读时校验的那条保持一致 */
    private static final String IDENTIFIER_PATTERN = "^[a-zA-Z_][a-zA-Z0-9_]*$";

    @Schema(description = "映射编号，修改时必填")
    private Long id;

    @Schema(description = "资源编号（DATASET 类型）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "资源编号不能为空")
    private Long resourceId;

    /**
     * 🔴 过正则，但<b>绝不能转小写</b> —— 尽管表名与列名在服务端都是那么归一的。
     * 内置槽位里有 {@code bizKey} 这个驼峰名，转小写会得到 {@code bizkey}，
     * 于是 {@code PermissionSlotEnum} 里那一条永远匹配不上：一条被"归一化"亲手弄死的映射。
     * 口径天生不同 —— 表列名是 SQL 标识符（MySQL 里大小写不敏感），
     * 槽位名是程序里的 map key（严格区分大小写）
     */
    @Schema(description = "语义槽位名称，如 owner / dept / status / bizKey", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "槽位名称不能为空")
    @Pattern(regexp = IDENTIFIER_PATTERN, message = "槽位名只能是字母、数字、下划线，且不能以数字开头")
    private String slot;

    @Schema(description = "列所在的物理表名，如 biz_order", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "所属表不能为空")
    @Pattern(regexp = IDENTIFIER_PATTERN, message = "所属表只能是字母、数字、下划线，且不能以数字开头")
    private String tableName;

    @Schema(description = "真实列名，如 create_user_id", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "列名不能为空")
    @Pattern(regexp = IDENTIFIER_PATTERN, message = "列名只能是字母、数字、下划线，且不能以数字开头")
    private String columnName;

    @Schema(description = "槽位说明")
    private String description;

}
