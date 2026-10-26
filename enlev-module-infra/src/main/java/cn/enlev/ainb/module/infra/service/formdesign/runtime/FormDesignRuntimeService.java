package cn.enlev.ainb.module.infra.service.formdesign.runtime;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormRuntimeConfigRespVO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDataChangeLogDO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.export.FormExportOptions;
import cn.enlev.ainb.module.infra.framework.formdesign.core.sql.FormDesignSqlBuilder;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 表单设计运行时 Service 接口
 *
 * 承载动态表单的 CRUD 能力。前端传来 form-create 的 field → value 数据，
 * 后端根据发布时存下的 tableBinding 配置，动态生成 SQL 并执行。
 *
 * 核心约定：
 * - 表名列名走守卫校验（白名单+元数据+正则）
 * - 值全部参数绑定（PreparedStatement），绝不拼接
 * - 主表与子表同事务
 * - 逻辑删除、自动填充、子表外键由 Service 层统一处理
 *
 * @author 走进新时代
 */
public interface FormDesignRuntimeService {

    /**
     * 获得运行时渲染配置
     *
     * 只返回渲染所需的最小集合，【不】下发 tableBinding ——
     * 物理表名与列名是服务端实现细节，暴露给前端等于摊开可攻击面。
     *
     * @param code 表单编码
     * @return 渲染配置
     */
    FormRuntimeConfigRespVO getRenderConfig(String code);

    /**
     * 创建
     *
     * @param code 表单编码
     * @param data form-create 提交的数据（field → value）
     * @return 主表主键值
     */
    Object create(String code, Map<String, Object> data);

    /**
     * 校验某个字段的值在库里是否可用（未被占用）
     *
     * 给前端失焦即时校验用。与提交时的唯一性校验查同一条 SQL，
     * 保证「输入时说可以、提交时说重复」不会发生。
     *
     * @param code           表单编码
     * @param field          字段名。必须是配了 props.uniqueCheck 的字段，
     *                       否则一律返回 true —— 不加这个限制，本接口就是一个
     *                       「任意列 + 任意值 → 存在与否」的探测口
     * @param value          待校验的值
     * @param excludePkValue 编辑时传自己的主键以排除自身；新增时传 null
     * @return 可用返回 true，已被占用返回 false
     */
    boolean checkFieldUnique(String code, String field, Object value, Object excludePkValue);

    /**
     * 取某条数据里某个脱敏字段的明文
     *
     * <h3>为什么需要它</h3>
     *
     * 详情接口对脱敏字段返回的是掩码，用户在编辑页看到的是 {@code 138****5678}。
     * 想把手机号改成别的值时，他没法确认原值是什么 —— 尤其是「这条记录到底存的是
     * 哪个号」这种需要先看清再决定改不改的场景。
     *
     * <h3>只对确实配了脱敏的字段放行</h3>
     *
     * 不加这个限制，本接口就成了一个「任意行 + 任意列 → 明文」的读取口，
     * 比不脱敏还糟（脱敏至少挡住了列表与导出）。这与 {@link #checkFieldUnique}
     * 里的防护是同一个思路。
     *
     * <h3>⚠️ 访问控制：两道门 + 两道字段级判据</h3>
     *
     * 权限门在 Controller（{@code FormDesignRuntimeController#getPlainField}）：
     * 表单的 {@code update} <b>与</b> {@code view-plain} 两个动作都要有。
     * 只有 update 的话，「能改这条数据」与「能看它的明文」被焊死成一件事，
     * 而这两件事在真实组织里常常不是同一批人。
     *
     * 本方法内部还有两道字段级判据，与上面的权限门互相独立、缺一不可：
     * 字段必须确实配了脱敏（否则本接口就是「任意行任意列 → 明文」的读取口），
     * 且该字段对当前用户必须是 EDITABLE（否则被判 HIDDEN 的字段能从这里原样读出明文，
     * 比不裁剪的 get 还多泄露一层）。
     *
     * 成功与被拒都会写审计（{@code FormDataAuditor}），且只记字段名不记值。
     *
     * @param code  表单编码
     * @param id    主键值
     * @param field 字段名，必须是配了 maskRule 的字段
     * @return 该字段的明文值
     */
    Object getPlainFieldValue(String code, Object id, String field);

    /**
     * 更新
     *
     * @param code    表单编码
     * @param pkValue 主表主键值
     * @param data    form-create 提交的数据
     */
    void update(String code, Object pkValue, Map<String, Object> data);

    /**
     * 更新系统列
     *
     * 与 {@link #update} 的区别：
     * 1. 入参 key 是**物理列名**，不是 form-create 字段名 —— 系统列不是表单字段，不在 fieldMapping 里，
     *    走 update 会被静默忽略
     * 2. 只更新传入的列（局部更新），而 update 会按 fieldMapping 全量覆盖主表所有映射列
     * 3. 仅允许写入受限白名单内的系统列（process_instance_id / process_status），不是通用写入口
     *
     * 供 BPM 在线表单对接回写流程实例编号与流程状态使用。
     *
     * @param code         表单编码
     * @param pkValue      业务主键
     * @param columnValues 物理列名 -> 值
     */
    void updateSystemColumns(String code, Object pkValue, Map<String, Object> columnValues);

    /**
     * 删除
     *
     * @param code    表单编码
     * @param pkValue 主表主键值
     */
    void delete(String code, Object pkValue);

    /**
     * 批量删除表单数据
     *
     * 与逐条调用 {@link #delete} 的区别只在事务边界：整批要么全删掉、要么一条都不删。
     * 前端循环单删做不到这一点 —— 删到一半失败会留下一个「删了三条、还剩两条」
     * 的中间状态，而用户看到的只是一句报错。
     *
     * @param code     表单编码
     * @param pkValues 主键值列表
     */
    void deleteBatch(String code, java.util.List<Object> pkValues);

    /**
     * 查询单条
     *
     * @param code    表单编码
     * @param pkValue 主表主键值
     * @return 主表数据 + 子表数据（按配置结构嵌套）
     */
    Map<String, Object> get(String code, Object pkValue);

    /**
     * 查询一条数据的变更时间线
     *
     * 只有表单启用了变更日志才会有记录；未启用时返回空列表，而不是报错 ——
     * 前端拿它做「有就展示、没有就不展示」的判断。
     *
     * @param code    表单编码
     * @param pkValue 数据主键值
     * @param limit   最多返回多少条，最新的在前
     */
    List<FormDataChangeLogDO> getDataChangeLogs(String code, Object pkValue, int limit);

    /**
     * 分页查询
     *
     * @param code       表单编码
     * @param conditions 查询条件
     * @param sortField  排序字段（可选）
     * @param sortOrder  排序方向（可选）
     * @param pageNo     页码，从 1 开始
     * @param pageSize   每页条数
     * @return 分页结果
     */
    PageResult<Map<String, Object>> page(String code,
                                         List<FormDesignSqlBuilder.QueryCondition> conditions,
                                         String sortField, String sortOrder,
                                         int pageNo, int pageSize);

    /**
     * 关联表单控件的候选数据（REFERENCE 弱查询）
     *
     * 与 {@link #page} 的区别不只是「少几个字段」，而是<b>另一套权限语义</b>：
     * 它校验的是目标表单的 {@code reference} 而不是 {@code query}，
     * 为的是让「能填订单但不能浏览客户列表」的人也选得到客户。
     *
     * ⚠️ 目标表单与可见字段一律从<b>源表单的已发布配置</b>里读，不接受调用方传。
     * 这是它全部安全性的来源 —— 允许传的话，它就比要替代的 query 还宽。
     *
     * @param sourceCode  正在填写的表单（引用方）编码
     * @param sourceField 该表单上关联控件的字段名
     * @param keyword     按显示字段模糊搜，可空
     * @param limit       返回条数，服务端有硬上限
     * @return 每行只含主键、显示字段与带出映射声明的源字段
     */
    List<Map<String, Object>> referenceOptions(String sourceCode, String sourceField,
                                               String keyword, Integer limit);

    /**
     * 解析关联控件指向的目标表单编码
     *
     * 单独开一个方法是因为<b>鉴权要在取数之前</b>，而「要校验哪张表单的权限」
     * 这件事本身得先解析配置才知道。本仓库的惯例是 checkPermission 一律写在
     * Controller 里，所以由它先问一次目标是谁，再决定放不放行。
     */
    String resolveReferenceTargetCode(String sourceCode, String sourceField);

    /**
     * 查询某字段的去重值
     *
     * 供列表页左侧树筛选用：树节点是该字段的全部去重值。
     * 不用分页接口再去重 —— 分页只能拿到当前页，第二页才出现的值在树里会缺失。
     *
     * @param code  表单编码
     * @param field form-create 字段名，必须在 fieldMapping 里声明过
     * @return 去重值列表，已排序，NULL 已过滤
     */
    List<Object> distinctValues(String code, String field);

    /**
     * 导出表单数据 —— <b>列表怎么显示，导出就怎么导</b>
     *
     * 格式、列、顺序都不由调用方指定：它们来自列表配置与用户的列设置。
     * {@code options} 里只放「同一张列表、同一次导出也可能不同」的那几项
     * （范围、合计行），见 {@link FormExportOptions}。
     *
     * <h3>行数上限</h3>
     *
     * 来自列表配置 {@code listConf.exportMaxRows}，由表单设计者定，默认 2 万行。
     * 超了会在文件名与响应头上<b>明确标出来</b>而不是静默截断 ——
     * 静默截断的话用户以为导全了，而少掉的那部分他永远不会去找。
     *
     * @param conditions 查询条件
     * @param options    导出选项，可为 null（按默认：全部范围、合计行跟随列表）
     * @param response   HTTP 响应，直接写入 Excel 流
     */
    void exportExcel(String code,
                     List<FormDesignSqlBuilder.QueryCondition> conditions,
                     FormExportOptions options,
                     HttpServletResponse response) throws IOException;

    /**
     * 执行自定义按钮的「调数据接口」动作
     *
     * <h3>⚠️ 为什么参数是按钮标识而不是接口编码</h3>
     *
     * 接口编码<b>只能</b>从这张表单已发布的配置里读出来。让调用方直接传接口编码的话，
     * 这个端点就变成「任何对某张表单有 update 权限的人都能调任意写型数据接口」——
     * 那是一个真正的权限提升面，而不只是「多了一个入口」。
     * 按钮标识这条路把可达范围收窄成「表单设计者在这张表单上明确接过线的那几个接口」。
     *
     * <h3>⚠️ 入参也不用调用方送的值</h3>
     *
     * 按 {@code params} 的字段映射，服务端<b>重新读一遍那条记录</b>再取值。
     * 用调用方送上来的值，等于让它自由构造接口入参 —— 与自由指定接口编码一样危险。
     *
     * <h3>鉴权是临时状态</h3>
     *
     * 沿用表单自己的运行时权限（{@code infra:form-design-run:{code}:update}），
     * 没有针对数据接口的独立权限模型。见交接文档第十五节与台账第 8 项。
     *
     * @param code      表单编码
     * @param buttonKey 按钮标识
     * @param pkValue   当前记录主键。工具栏按钮没有记录，可为 null
     * @return 数据接口的返回值
     */
    Object invokeButtonInterface(String code, String buttonKey, Object pkValue);

}
