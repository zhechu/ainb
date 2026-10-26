package cn.enlev.ainb.module.infra.controller.admin.datamodel.vo;

import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableIndexModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 数据模型结构 Response VO（字段 + 索引）")
@Data
public class DataModelStructureRespVO {

    @Schema(description = "字段列表，实时读取真实表结构")
    private List<TableColumnModel> columns;

    @Schema(description = "索引列表（不含主键）")
    private List<TableIndexModel> indexes;

    /**
     * 本次读到的表结构指纹
     *
     * <p>🔴 改表时<b>必须原样回传</b>（{@code DataModelSaveReqVO.schemaFingerprint}）。
     * 它的用途是让「我看到的那张表」与「提交时那张表」能被判等 ——
     * 读取与提交之间可能隔着几分钟，AI 走工具时可能隔着好几轮对话，
     * 这期间表完全可能被绕过平台改掉（DBA 手工 SQL、迁移脚本）。
     * 那种情况下 ALTER 照样执行成功，只有登记在这张模型上的业务配置在悄悄失效。
     *
     * <p>算的是<b>实时结构</b>，不是库里存的那个基线值 —— 基线是「上次平台执行 DDL 时的结构」，
     * 拿它当回传值就变成自己跟自己比，永远相等，这道闸就成了空操作。
     */
    @Schema(description = "本次读到的表结构指纹，改表时需原样回传")
    private String schemaFingerprint;

    /**
     * 登记行当前的乐观锁版本号，改表时<b>必须原样回传</b>
     *
     * <p>与 {@link #schemaFingerprint} 防的是两件不同的事：本字段防「登记行被人改过」
     * （模型名称、备注、列的业务元数据），指纹防「物理表结构被人绕过平台改过」。
     * 后者不会反映在前者上 —— 直接对库执行 ALTER 根本不经过本平台。
     */
    @Schema(description = "登记行当前的乐观锁版本号，改表时需原样回传")
    private Integer version;

}
