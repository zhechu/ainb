package cn.enlev.ainb.module.infra.api.formdesign.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * 表单删除事件
 *
 * <p>{@link FormDesignPublishedEvent} 的反向：广播「这张表单没了，把它的可授权对象注销掉」。
 * 为什么用事件而不是 infra 直接调权限模块，理由与发布事件逐字相同 ——
 * 模块依赖是单向的（system 依赖 infra），反向调用会造成循环依赖。
 *
 * <h3>🔴 少了它会怎样：删除表单会永久留下一条游离的数据集锚点</h3>
 *
 * 发布时 {@code DatasetResourceService#register} 按<b>表单编码</b>建出一行
 * {@code type=DATASET} 的 {@code sys_resource}（权限串 {@code infra:form-design-run:<code>}）。
 * 而 {@code deleteFormDesign} 原本只清自己模块的数据（版本快照、菜单关联、表单行本身），
 * 一行都不碰 {@code sys_resource} —— 于是每删一张发布过的表单，权限体系里就多一条
 * 指向已不存在对象的锚点。实测本库已积累 <b>155</b> 条这样的游离锚点。
 *
 * 它不只是脏数据，有一条<b>会真正影响鉴权</b>的路径：
 * {@code deleteFormDesign} 为了把编码从 {@code uk_code}（不含 deleted 的全局唯一索引）里让出来，
 * 会把 code 改写成 {@code <原编码>_del_<id>}。编码一旦被让出就能被新表单重新占用，
 * 而 {@code register} 走的 {@code selectByPermissionIncludeDisabled} 只滤 deleted=0 ——
 * 那条<b>没被删掉</b>的旧锚点会被原样捞出来复用，连带它身上的
 * {@code default_effect}（可能被塌缩计划器或管理员收紧成 DENY）与
 * {@code sys_slot_mapping}（{@code syncSlots} 是「只补不改」，旧槽位会一直留着）。
 * 结果是一张全新的表单静默继承了死表单的权限配置，全程 200 无异常。
 *
 * <p>注销之后这条路径自动断开：锚点 deleted=1，{@code selectByPermissionIncludeDisabled}
 * 查不到它，重新占用编码时会建出干净的一行。
 *
 * <h3>为什么删除要注销、下线（unpublish）不注销</h3>
 *
 * 下线之后表单还在，只是回到草稿；「下线 → 改 → 再发布」是常规操作。
 * 下线时把锚点删掉的话，管理员配好的行级规则与字段级授权会在下一次发布时
 * 由 {@code register} 重新建成一份<b>全新</b>的（新 id、默认效果、空槽位），
 * 等于一次没有任何提示的配置丢失。删除不一样：表单连同编码墓碑一起没了，
 * 留着锚点没有任何人能再用上它。
 *
 * <h3>同步 + 同事务</h3>
 *
 * 与发布事件同一个取舍：监听方用普通 {@code @EventListener}，跑在删除的事务里，
 * 注销失败则整个删除回滚。宁可删不掉得明明白白，也不要「表单删了、
 * 权限体系里还挂着它」—— 后者在页面上完全看不出来。
 *
 * @author 走进新时代
 */
@Getter
@ToString
@RequiredArgsConstructor
public class FormDesignDeletedEvent {

    /**
     * 被删除表单的编码（删除<b>前</b>的原编码，不是墓碑编码）
     *
     * <p>⚠️ 必须是原编码：数据集锚点的权限串是发布时用原编码建的，
     * 拿墓碑编码去注销会一条都找不到，静默地什么都不做
     */
    private final String formCode;

}
