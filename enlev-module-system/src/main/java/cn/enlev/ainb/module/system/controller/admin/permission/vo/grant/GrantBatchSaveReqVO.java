package cn.enlev.ainb.module.system.controller.admin.permission.vo.grant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 授权行批量新增请求 VO
 *
 * <h3>为什么要有这个包装类，而不是直接收 {@code List<GrantSaveReqVO>}</h3>
 *
 * 🔴 Spring 对 {@code @RequestBody @Valid List<T>} <b>不会</b>级联校验元素：
 * List 不是 JavaBean，没有属性可供 {@code @Valid} 附着，于是每一项上的
 * {@code @NotBlank} / {@code @InEnum} / {@code @NotNull} 全部静默跳过 ——
 * 批量入口会变成一条绕过校验的后门（能塞进枚举外的 action、空的 subjectType）。
 * 类级 {@code @Validated} + {@code List<@Valid T>} 虽然能触发，但抛的是
 * {@code ConstraintViolationException}，不走 {@code MethodArgumentNotValidException}
 * 那条标准 400 处理路径，前端拿到的错误结构与单条提交不一致。
 *
 * <b>包装 VO + 字段级 {@code @Valid}</b> 才是让「批量与单条共用同一套校验注解」
 * 真正成立的机制 —— 这也正是本接口不另造第二个 VO（resourceIds + actions + 共用字段）、
 * 而由前端做笛卡尔展开的原因：展开后每一项与单条 {@code /create} 完全同构，
 * 校验只有一份，不存在两处各自漂移的可能。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 授权行批量新增请求")
@Data
public class GrantBatchSaveReqVO {

    /**
     * 授权项列表，每项与单条 {@code POST /system/grant/create} 的请求体完全同构
     */
    @Schema(description = "授权项列表，每项与单条新增完全同构", requiredMode = Schema.RequiredMode.REQUIRED)
    @Valid
    // 与批量删除的「空列表幂等成功」刻意不对称：删除的 ids 来自表格勾选状态，
    // 翻页/重载竞态可能把选择清空，静默成功是对的；而 items 来自一个带表单校验的弹窗，
    // 空 items 到达后端只能是前端 bug，400 比静默成功更有信息量
    @NotEmpty(message = "授权项不能为空")
    // 上限镜像前端 batchGrant.ts 的 GRANT_BATCH_MAX_ITEMS，两处必须同步改。
    // 500 的依据：最大的合法场景是「字段级数据集子树 × 2 动作」（一个数据集约 100 字段），
    // 500 覆盖它还有余量；再大基本是误勾了整棵资源树，让前端先拦、后端兜底。
    // ⚠️ {max} 是 Bean Validation 的插值语法（渲染成 500），与错误码文案里的 {} 不是一套东西
    @Size(max = 500, message = "单次批量授权最多 {max} 条，请拆分提交")
    private List<GrantSaveReqVO> items;

}
