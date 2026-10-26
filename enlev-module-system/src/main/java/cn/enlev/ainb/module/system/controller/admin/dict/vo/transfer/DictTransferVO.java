package cn.enlev.ainb.module.system.controller.admin.dict.vo.transfer;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 字典迁移包：一棵分类子树 + 这棵子树下的全部字典数据
 *
 * <h3>为什么分类的导入导出用 JSON，而字典数据用 Excel</h3>
 * 两者要解决的问题不是一回事：
 * <ul>
 *     <li>字典数据的典型场景是「让业务同事批量改几十上百个选项」，Excel 是他们手边的工具，
 *         这条通道保留不动（见 {@code DictDataController#export/importExcel}）</li>
 *     <li>分类的典型场景是「把一整棵分类子树从测试环境搬到生产」，搬的是嵌套结构：
 *         分类有层级、分类下面还挂着同样有层级的数据。二维表格表达嵌套结构要么摊成多个 sheet、
 *         要么塞进一列拼接字符串，两种都很难读也很难改。JSON 天生就是这个形状</li>
 * </ul>
 *
 * <h3>版本号的作用</h3>
 * 包的结构以后可能加字段。导入时先看 {@link #version}：比当前支持的版本新，说明这个包
 * 是更高版本的系统导出的，直接拒绝并说明原因，好过按老结构解析出一份缺胳膊少腿的数据。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 字典迁移包")
@Data
public class DictTransferVO {

    /**
     * 当前支持的包格式版本
     */
    public static final Integer CURRENT_VERSION = 1;

    @Schema(description = "包格式版本", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer version;

    @Schema(description = "导出时间", example = "时间戳格式")
    private LocalDateTime exportTime;

    @Schema(description = "导出的根分类编码，仅供人快速辨认这个包是什么", example = "sys_common")
    private String rootType;

    @Schema(description = "分类节点列表，按「父在前、子在后」排列", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<DictTypeTransferVO> types = new ArrayList<>();

    @Schema(description = "字典数据列表")
    private List<DictDataTransferVO> datas = new ArrayList<>();

}
