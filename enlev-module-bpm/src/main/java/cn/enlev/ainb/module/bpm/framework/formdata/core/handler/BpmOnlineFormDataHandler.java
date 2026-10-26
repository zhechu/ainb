package cn.enlev.ainb.module.bpm.framework.formdata.core.handler;

import cn.enlev.ainb.module.bpm.enums.definition.BpmModelFormTypeEnum;
import cn.enlev.ainb.module.bpm.framework.formdata.core.BpmFormDataHandler;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 在线表单（ONLINE）的数据处理器
 *
 * 数据落到「表单设计」（infra_form_design）表绑定的物理表，读写全部委托
 * {@link FormDesignApi} —— 其内部走 FormDesignRuntimeService，方法已带
 * {@code @Transactional(REQUIRED)}，会自动加入调用方的外层事务，从而保证
 * 「业务表写入」与「流程启动 / 任务完成」的原子性。
 *
 * 这里不直接拼 SQL：表绑定的字段映射、主键策略、逻辑删除、系统字段填充等规则
 * 都在表单设计模块内，绕过它自己写 SQL 必然与之不一致。
 *
 * @author 走进新时代
 */
@Component
public class BpmOnlineFormDataHandler implements BpmFormDataHandler {

    /**
     * 流程实例编号列名。固定列名，不接受映射到别名
     */
    public static final String COLUMN_PROCESS_INSTANCE_ID = "process_instance_id";
    /**
     * 流程状态列名。仅主表单要求。
     *
     * 用 process_status 而非 status —— 与业务表自己的 status 业务字段解耦，避免同表撞名
     */
    public static final String COLUMN_PROCESS_STATUS = "process_status";

    @Resource
    private FormDesignApi formDesignApi;

    @Override
    public Integer getFormType() {
        return BpmModelFormTypeEnum.ONLINE.getType();
    }

    @Override
    public Object create(String formKey, Map<String, Object> data) {
        return formDesignApi.createData(formKey, data);
    }

    @Override
    public void update(String formKey, Object pk, Map<String, Object> data) {
        if (pk == null || data == null || data.isEmpty()) {
            return;
        }
        // 注意：FormDesignRuntimeService.update 是**全量覆盖**语义 —— 它遍历 fieldMapping 里的
        // 每个字段并写入 data.get(field)，缺失的键会被写成 null。
        // 因此这里必须先读出整行、合并后再写，否则节点表单只提交部分字段时会把其它字段清空。
        Map<String, Object> merged = new HashMap<>();
        Map<String, Object> exists = formDesignApi.getData(formKey, pk);
        if (exists != null) {
            merged.putAll(exists);
        }
        merged.putAll(data);
        formDesignApi.updateData(formKey, pk, merged);
    }

    @Override
    public Map<String, Object> get(String formKey, Object pk) {
        if (pk == null) {
            return null;
        }
        return formDesignApi.getData(formKey, pk);
    }

    @Override
    public void delete(String formKey, Object pk) {
        if (pk == null) {
            return;
        }
        formDesignApi.deleteData(formKey, pk);
    }

    @Override
    public void updateStatus(String formKey, Object pk, Integer status) {
        if (pk == null || status == null) {
            return;
        }
        // 走系统列通道：process_status 是系统列不在 fieldMapping 里，用 updateData 写不进去
        formDesignApi.updateSystemColumns(formKey, pk, Map.of(COLUMN_PROCESS_STATUS, status));
    }

    /**
     * 回写流程实例编号
     *
     * 发起流程时，业务数据先写入拿到主键，流程实例编号此时才产生，因此需要回写一次。
     *
     * @param formKey           在线表单编码
     * @param pk                业务主键
     * @param processInstanceId 流程实例编号
     */
    public void updateProcessInstanceId(String formKey, Object pk, String processInstanceId) {
        if (pk == null || processInstanceId == null) {
            return;
        }
        formDesignApi.updateSystemColumns(formKey, pk, Map.of(COLUMN_PROCESS_INSTANCE_ID, processInstanceId));
    }

    /**
     * 批量回写系统列
     *
     * 用于发起时一次性写入 process_instance_id 与 status，避免两次 UPDATE。
     *
     * @param formKey      在线表单编码
     * @param pk           业务主键
     * @param columnValues 物理列名 -> 值，只允许系统列
     */
    public void updateSystemColumns(String formKey, Object pk, Map<String, Object> columnValues) {
        if (pk == null || columnValues == null || columnValues.isEmpty()) {
            return;
        }
        formDesignApi.updateSystemColumns(formKey, pk, columnValues);
    }

}
