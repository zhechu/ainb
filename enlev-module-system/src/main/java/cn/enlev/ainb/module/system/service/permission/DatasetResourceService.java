package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.module.infra.api.formdesign.dto.FormResourceDescriptorDTO;
import cn.enlev.ainb.module.system.framework.permission.core.dataset.NativeDatasetScanner;

import java.util.Map;

/**
 * 在线表单的数据集资源 Service
 *
 * 把一张已发布表单登记成权限体系里的可授权对象：
 * 一个 DATASET 资源、若干按需创建的 COLUMN/FIELD 资源、一组语义槽位映射。
 *
 * @author 走进新时代
 */
public interface DatasetResourceService {

    /**
     * 登记（或重新登记）一张表单
     *
     * 幂等：同一张表单反复发布，结果收敛到同一份资源与槽位，不会越积越多。
     *
     * @param descriptor 表单的可授权对象描述
     * @return 登记结果
     */
    RegisterResult register(FormResourceDescriptorDTO descriptor);

    /**
     * 登记（或重新登记）一张<b>原生模块</b>的表（S7a）
     *
     * 与 {@link #register} 的差别只在来源：那边的槽位是从表单定义里猜的，
     * 这边是开发者在 {@code @PermissionDataset} 上写死的。
     * 登记出的东西（DATASET 资源、槽位映射、行级规则的表名注册）完全一样，
     * 所以在线表单与原生模块在授权页、决策算法上是同一种对象 ——
     * 分成两套的话，「给这张表配一条只看自己的规则」会因为对象是哪一种而行为不同。
     *
     * 幂等：反复启动收敛到同一份资源与槽位。
     *
     * @param descriptor 原生数据集声明
     * @return 登记结果
     */
    RegisterResult registerNative(NativeDatasetScanner.NativeDatasetDescriptor descriptor);

    /**
     * 注销一张在线表单登记出来的数据集资源（表单被<b>删除</b>时调用）
     *
     * <p>{@link #register} 的反向。连同数据集锚点一起清掉它的整棵子树
     * （列/字段资源、被收拢到它下面的动作资源）、这些资源上的授权行、以及槽位映射。
     *
     * <h3>为什么删除要注销，而下线（unpublish）不注销</h3>
     *
     * 下线之后表单还在，只是回到草稿，「下线 → 改 → 再发布」是常规操作。
     * 那时注销的话，管理员配好的行级规则与字段级授权会在下次发布时被 {@link #register}
     * 重建成<b>全新</b>的一份（新 id、出厂默认效果、空槽位），是一次没有任何提示的配置丢失。
     * 删除不一样：表单连同编码墓碑一起没了，锚点留着没有任何人能再用上它，
     * 反而会在新表单重新占用同一个编码时被 {@link #register} 原样捞出来复用 ——
     * 连带它身上可能被塌缩计划器或管理员收紧过的 {@code default_effect}，
     * 以及 {@code syncSlots}「只补不改」策略下永远留着的旧槽位映射。
     *
     * <p>幂等：锚点不存在（表单从未发布过，或已经注销过）时什么都不做，返回全 0。
     *
     * @param formCode 表单编码（删除<b>前</b>的原编码）
     * @return 注销结果
     */
    UnregisterResult unregister(String formCode);

    /**
     * 把已登记的全部数据集表名重新灌进行级规则
     *
     * 应用启动时调用一次。不做这一步的话，重启后 {@code getTableNames()} 是空集，
     * 所有已配好的行级规则<b>静默失效</b>（查询照常返回，只是没了过滤）。
     *
     * @return 注册的表数量
     */
    int reloadRegisteredTables();

    /**
     * 登记结果
     *
     * @param datasetResourceId 数据集资源编号
     * @param createdCount      本次新建的列/字段资源数
     * @param removedCount      本次回收的列/字段资源数（不再敏感且无人授权的）
     * @param keptCount         保留的列/字段资源数（含因为已有授权而不能回收的）
     * @param slots             推断出的语义槽位：槽位名 → 列名
     */
    record RegisterResult(Long datasetResourceId, int createdCount, int removedCount,
                          int keptCount, Map<String, String> slots) {
    }

    /**
     * 注销结果
     *
     * @param datasetResourceId    被注销的数据集资源编号；锚点不存在时为 null
     * @param removedResourceCount 本次软删的资源行数（含数据集锚点自身）
     * @param removedGrantCount    本次级联清掉的授权行数
     * @param removedSlotCount     本次物理删除的槽位映射行数
     */
    record UnregisterResult(Long datasetResourceId, int removedResourceCount,
                            int removedGrantCount, int removedSlotCount) {

        /**
         * 什么都没注销（锚点不存在）
         */
        public static UnregisterResult nothing() {
            return new UnregisterResult(null, 0, 0, 0);
        }
    }

}
