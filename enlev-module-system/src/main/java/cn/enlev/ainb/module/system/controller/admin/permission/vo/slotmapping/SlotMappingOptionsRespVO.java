package cn.enlev.ainb.module.system.controller.admin.permission.vo.slotmapping;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 槽位映射编辑器的候选项
 *
 * <h3>为什么要有这个端点</h3>
 *
 * 槽位映射是行过滤规则能不能求值的前提：规则里写 {@code {owner}}，引擎靠这张表
 * 才知道 owner 对应哪个物理列。填错的代价分两档，都不是「报个错」那么轻：
 *
 * <ul>
 *   <li><b>槽位名</b>必须来自 {@code PermissionSlotEnum}。规则模板里引用的就是这些名字，
 *       自己发明一个（比如 {@code ownerId}）映射建得出来、却永远没有规则会读它 ——
 *       而真正被引用的 {@code owner} 仍然缺着，症状与没配一模一样。
 *       前端如果自带一份词表，那就是全仓第 5 份副本（后端枚举、规则编辑器、
 *       自动推断、批量授权覆盖度各一份），加一个槽位必然漏改</li>
 *   <li><b>真实列名</b>填错更硬：它会被原样拼进 SQL ——
 *       {@code ConditionTreeEvaluator} 拿它拼行过滤的 WHERE，
 *       {@code RecordOwnerResolver} 拿它拼 {@code SELECT owner FROM table WHERE pk = ?}。
 *       列不存在 ⟹ 那条规则求不出来，进而整条授权按「求不出来」失效（fail-closed），
 *       症状是「权限配了但不生效」，界面上一点迹象都没有</li>
 * </ul>
 *
 * 所以表名与列名这两个输入框都不该让人凭空手打：槽位词表在这里给全，
 * 库里的表与列则由 {@code /options}（表名）与 {@code /table-columns}（选中表的列）两级下拉给出。
 *
 * <h3>⚠️ 表名与列名都是「只能选、不能填」，槽位名可以手填</h3>
 *
 * 这三者的口径不同，不是随手定的：
 *
 * <ul>
 *   <li><b>列名必须是白名单</b>。它是拼进 SQL 的标识符，而它的正确性可以被机器核实
 *       （{@code information_schema} 一查就知道这列在不在）。既然能核实，就不该让人手打 ——
 *       手打省下的那几秒，换来的是运行期一条静默失效的权限规则</li>
 *   <li><b>表名必须跟着一起选</b>。列表查询是带 JOIN 的，同一个列名（如 {@code create_user_id}）
 *       在主表和关联表上都可能存在，不记表名就表达不出「这个槽位对应关联表上的那一列」；
 *       而且列名下拉本来就是按表查的，没有表就没有列</li>
 *   <li><b>槽位名保留手填</b>。下拉里给的是 {@code PermissionSlotEnum} 全集，
 *       但规则模板允许引用枚举之外的自定义槽位（{@code BUILT_IN_CUSTOM_TREE} 的条件树里
 *       写什么 slot 都行），把它锁成硬白名单会让这类规则永远配不出来。
 *       填了一个没人引用的槽位，代价只是这行映射白配 —— 不会放大任何人的权限。
 *       <p>⚠️ 但「不锁白名单」不等于「不校验」：入口仍然过标识符正则
 *       （见 {@code SlotMappingSaveReqVO#slot}）。少了它，{@code " owner"} 这种带空格的
 *       名字能存进去，而求值侧是拿枚举字面值精确匹配的，于是它成了一条谁也读不到的死映射 ——
 *       在列表里还和好的那条长得一模一样。这种错必须当场拒，不能靠事后 trim 悄悄修好，
 *       悄悄修好等于不告诉用户他打错了什么</li>
 * </ul>
 *
 * 唯一需要留神的边界：表不在主库时 {@code information_schema} 查不到它，那张表的列就选不出来，
 * 保存也会被写入口拒掉。而 {@code RecordOwnerResolver} 与行过滤求值本来也只跑在主库上，
 * 所以这不是「白名单太严」，是那条路本身走不通 —— 配了也用不上。
 * 实测本库 55 张在线表单全部绑在主库，这条限制对现有数据零影响；
 * 真要支持非主库表单，得先让求值侧能按表单的数据源切换，而不是先让配置侧放行
 * （详见 {@code SlotMappingAdminController#listTables} 的注释）。
 *
 * <h3>⚠️ 列名是按表查的，所以两级下拉有先后</h3>
 *
 * 一次把全库的表带列全返回是几十万字节的量级（实测 217 张表 / 3367 列），
 * 而用户一次只配一行。所以 {@code /options} 只回表名与表注释，
 * 列名等用户真的选了表再单独取 —— 这也正好是「先选表、再选字段」这个交互本身的顺序。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 槽位映射编辑候选项")
@Data
public class SlotMappingOptionsRespVO {

    @Schema(description = "槽位词表，来自 PermissionSlotEnum 全集")
    private List<SlotOption> slots;

    @Schema(description = "库里的物理表，供「先选表」那一级下拉用；列名另调 /table-columns 取")
    private List<TableOption> tables;

    /**
     * 本资源数据集的主表名。
     *
     * 前端拿它做两件事：新增时把「所属表」默认选成主表（绝大多数槽位都在主表上），
     * 以及当用户选了别的表时提示一句 —— 那张表只有真的被 JOIN 进列表查询时才有效，
     * 否则这条映射运行期求不出来，会按 fail-closed 让整条授权失效
     */
    @Schema(description = "本资源数据集的主表名，供前端默认选中与越界提示", example = "biz_book_borrow")
    private String mainTable;

    @Schema(description = "槽位候选项")
    @Data
    public static class SlotOption {

        @Schema(description = "槽位名，如 owner / dept / status / bizKey")
        private String slot;

        @Schema(description = "槽位说明，直接取自枚举，与规则编辑器里看到的同一份")
        private String description;

        /**
         * 该槽位的候选列名，按优先级从高到低。
         *
         * ⚠️ 与 {@code PermissionSlotEnum} 的注释同一口径：这是自动推断<b>猜</b>时用的名单，
         * 不是约定。放在下拉里只是给人一个起点，猜错了照样恒假，仍需人工核对
         */
        @Schema(description = "该槽位的候选列名，按优先级从高到低（自动推断用的同一份名单）")
        private List<String> candidates;

        /**
         * 这个槽位在本资源上是否已经配过映射。
         *
         * 让前端能把已配的槽位在「新增」下拉里标出来并禁用：重复配同一个 slot 会被
         * create 里的查重拦下（在撞上唯一索引 {@code uk_resource_slot} 之前），
         * 返回 {@code SLOT_MAPPING_DUPLICATE}。提前看得见总比事后读错误提示好，
         * 而且下拉里禁掉之后，手输就成了唯一能绕过去的口子 —— 那条路由查重兜住
         */
        @Schema(description = "该槽位在本资源上是否已配过映射")
        private Boolean mapped;
    }

    @Schema(description = "物理表候选项")
    @Data
    public static class TableOption {

        @Schema(description = "物理表名", example = "biz_book_borrow")
        private String tableName;

        @Schema(description = "表注释，取自 information_schema，可能为空", example = "图书借阅表")
        private String tableComment;
    }

    /**
     * 列候选项。
     *
     * 挂在「编辑器候选项」这个 VO 下面而不是单独一个文件：它就是同一个下拉的第二级，
     * 由 {@code /table-columns} 单独返回 {@code List<ColumnOption>}
     */
    @Schema(description = "物理列候选项")
    @Data
    public static class ColumnOption {

        @Schema(description = "物理列名", example = "create_user_id")
        private String columnName;

        @Schema(description = "列注释，取自 information_schema，可能为空", example = "创建人")
        private String columnComment;
    }

}
