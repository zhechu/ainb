package cn.enlev.ainb.module.infra.framework.formdesign.core.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DataRowFilterRespDTO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.sql.SqlIdentifierGuard;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_ROW_OUT_OF_SCOPE;

/**
 * 写后落域校验
 *
 * <h3>它挡的是什么</h3>
 *
 * WHERE 里的行过滤只能挡住「改别人的行」，挡不住「把自己的行改成别人的」。
 *
 * 举例：规则是「只能看本部门的单据」，用户改一张本部门的单，
 * 顺手把 {@code dept_id} 改成另一个部门 —— UPDATE 的 WHERE 校验的是<b>改之前</b>的值，
 * 完全通过；改完之后这条记录就跑到别的部门去了，用户自己也再看不见。
 *
 * 轻则是「单据莫名消失」的数据事故，重则是往别人的域里塞数据
 * （比如把审批单挂到另一个部门名下）。
 *
 * 所以写入之后要再确认一次：这条记录现在<b>仍然</b>落在我的可见域内。
 *
 * <h3>为什么用「再查一次」而不是比对提交值</h3>
 *
 * 比对提交值需要知道「哪些字段参与了域的判定」，而那取决于行过滤规则的条件树 ——
 * 规则可以引用任意槽位、任意组合。在应用层重新推导一遍等于把规则引擎实现两遍，
 * 两份实现迟早不一致。
 *
 * 直接用同一份行过滤条件去 SELECT 一次，判定口径必然与查询一致，不会漂。
 * 代价是一次按主键的查询，可以忽略。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormRowScopeGuard {

    @Resource
    private FormRowFilter rowFilter;
    @Resource
    private SqlIdentifierGuard guard;
    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 校验某条记录写入后仍在当前用户的可见域内
     *
     * 没有配行级规则时直接返回，不产生任何查询。
     *
     * @param binding 表绑定
     * @param pkValue 主键值
     * @throws cn.enlev.ainb.framework.common.exception.ServiceException 落到域外时抛出
     */
    public void validateInScope(TableBinding binding, Object pkValue) {
        if (binding == null || pkValue == null || StrUtil.isBlank(binding.getMainTable())) {
            return;
        }
        String tableName = binding.getMainTable();
        DataRowFilterRespDTO filter = rowFilter.resolve(tableName, "");
        if (!filter.isFiltered()) {
            return; // 这张表没有行级规则，无所谓落域
        }
        if (filter.isDenyAll()) {
            throw exception(FORM_DESIGN_ROW_OUT_OF_SCOPE);
        }
        if (StrUtil.isBlank(filter.getSql())) {
            return;
        }

        List<Object> params = new ArrayList<>();
        params.add(pkValue);
        params.addAll(filter.getParams());
        String sql = String.format("SELECT 1 FROM %s WHERE %s = ? AND (%s) LIMIT 1",
                guard.quote(tableName),
                guard.quote(binding.getMainPk()),
                filter.getSql());

        boolean inScope = !jdbcTemplate.queryForList(sql, params.toArray()).isEmpty();
        if (!inScope) {
            // 用业务异常而不是静默回滚：用户必须知道自己这次提交为什么没成
            log.warn("[validateInScope][表({})主键({})写入后落到可见域之外，已拒绝]",
                    tableName, pkValue);
            throw exception(FORM_DESIGN_ROW_OUT_OF_SCOPE);
        }
    }

}
