package cn.enlev.ainb.module.infra.service.formdesign.runtime;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.relation.RelationFormResolver;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 关联表单带出字段的服务端重新带出
 *
 * <h3>它防的是什么</h3>
 *
 * 关联表单的典型用法是「选一个客户，自动带出客户的信用额度、结算方式、开票信息」。
 * 这些带出值前端算好后随表单一起提交 —— 也就意味着可以被随意改掉：
 * 选一个信用额度 1 万的客户，把带出的额度字段改成 100 万再提交。
 *
 * 所以提交时服务端要按映射表【重新读一遍】被关联记录，并覆盖前端提交的带出值。
 * 与公式重算是同一个道理：凡是「由别处推导出来的值」，都不能信前端。
 *
 * <h3>为什么读取要鉴权</h3>
 *
 * 被关联表单是另一张独立的表单，有自己的权限。不能因为「它被别的表单引用了」
 * 就绕过那张表单的访问控制 —— 否则给任意一张自己有权的表单加一个关联控件，
 * 就能把无权访问的表单数据读出来。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class RelationFormFiller {

    @Resource
    private RelationFormResolver resolver;

    /**
     * 读被关联表单的数据
     *
     * ⚠️ 必须加 {@code @Lazy}。这里存在一个循环：
     * {@code FormDesignRuntimeServiceImpl → FormSubmitProcessor → RelationFormFiller → FormDesignRuntimeService}。
     *
     * 运行时服务带 {@code @Transactional} 代理，急切注入时 Spring 只能给出还没被包装的
     * 原始对象，于是直接拒绝启动（BeanCurrentlyInCreationException）。
     * {@code @Lazy} 注入一个代理，真正取实例推迟到第一次调用 —— 那时容器已经就绪。
     *
     * 循环本身是合理的：关联表单的语义就是「读另一张表单的数据」，
     * 而读取必须走运行时服务才能带上它的权限与发布状态校验。为了消掉循环而
     * 把读取逻辑复制一份到这里，反而会绕过那些校验。
     */
    @Lazy
    @Resource
    private FormDesignRuntimeService runtimeService;

    /**
     * 按映射表重新带出并覆盖
     *
     * @param design 当前表单设计
     * @param data   提交数据。本方法不修改入参
     * @return 带出后的数据
     */
    public Map<String, Object> fill(FormDesignDO design, Map<String, Object> data) {
        Map<String, Object> result = data != null ? new LinkedHashMap<>(data) : new LinkedHashMap<>();
        List<RelationFormResolver.RelationField> relations =
                resolver.parseRelationFields(design.getFormFields());
        if (relations.isEmpty()) {
            return result;
        }

        for (RelationFormResolver.RelationField relation : relations) {
            Object pkValue = result.get(relation.getField());
            if (pkValue == null || (pkValue instanceof CharSequence cs && cs.isEmpty())) {
                // 没选关联记录。此时把带出字段一并清空 —— 留着上一次带出的值
                // 会出现「关联记录已清空，但带出的客户名还在」这种自相矛盾的数据
                clearMappedFields(relation, result);
                continue;
            }

            Map<String, Object> sourceRow = loadSourceRow(relation, pkValue);
            if (sourceRow == null) {
                // 关联记录读不到（被删了、或当前用户无权）。不抛异常：
                // 主键值本身是合法提交内容，拿不到带出值不该让整次提交失败。
                // 但要记 warn —— 数据不完整是需要被发现的
                log.warn("[fill][关联字段({})指向的记录读不到，表单({})主键({})。带出字段将保持原值]",
                        relation.getField(), relation.getTargetFormCode(), pkValue);
                continue;
            }

            Map<String, String> toApply = relation.resolveMappingsToApply(result);
            toApply.forEach((targetField, sourceField) -> {
                Object serverValue = sourceRow.get(sourceField);
                Object submitted = result.get(targetField);
                result.put(targetField, serverValue);
                if (!Objects.equals(String.valueOf(submitted), String.valueOf(serverValue))) {
                    // 记 info：用户开着老页面、或关联记录刚被别人改过，都会让两边不一致，
                    // 这是常态。真正的篡改尝试也会落在这条日志里，需要时可按字段名检索
                    log.info("[fill][带出字段({})的提交值({})与服务端读到的值({})不一致，已按服务端结果覆盖]",
                            targetField, submitted, serverValue);
                }
            });
        }
        return result;
    }

    /**
     * 清空该关联的全部带出字段
     */
    private void clearMappedFields(RelationFormResolver.RelationField relation,
                                   Map<String, Object> data) {
        relation.getFieldMappings().forEach(mapping -> {
            if (data.containsKey(mapping.getTargetField())) {
                data.put(mapping.getTargetField(), null);
            }
        });
    }

    /**
     * 读被关联的记录
     *
     * 走 {@code runtimeService.get}，它内部会做表单存在性、发布状态与权限校验 ——
     * 这正是「跨表单读取不绕过权限」这条要求的落点。
     *
     * @return 记录；读不到返回 null
     */
    private Map<String, Object> loadSourceRow(RelationFormResolver.RelationField relation,
                                              Object pkValue) {
        if (StrUtil.isBlank(relation.getTargetFormCode())) {
            return null;
        }
        try {
            return runtimeService.get(relation.getTargetFormCode(), pkValue);
        } catch (Exception e) {
            // 包括「表单不存在」「未发布」「无权限」「记录不存在」几种情况。
            // 统一按读不到处理，不把底层异常透给调用方 ——
            // 那会让「关联的表单没发布」表现成一个看不懂的 SQL 错误
            log.debug("[loadSourceRow][读关联记录失败，表单({}) 主键({})]",
                    relation.getTargetFormCode(), pkValue, e);
            return null;
        }
    }

}
