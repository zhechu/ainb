package cn.enlev.ainb.module.infra.service.datamodel;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelLogPageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelPageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelSaveReqVO;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelStructureRespVO;
import cn.enlev.ainb.module.infra.dal.dataobject.datamodel.DataModelDO;
import cn.enlev.ainb.module.infra.dal.dataobject.datamodel.DataModelLogDO;
import cn.enlev.ainb.module.infra.enums.artifact.ArtifactSourceEnum;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;

import java.util.List;
import java.util.Map;

/**
 * 数据模型 Service 接口
 *
 * @author 走进新时代
 */
public interface DataModelService {

    /**
     * 创建数据模型：登记模型 + 执行 CREATE TABLE
     *
     * <p>来源记为 {@link ArtifactSourceEnum#MANUAL}。
     *
     * @param createReqVO 创建信息
     * @return 模型编号
     */
    Long createDataModel(DataModelSaveReqVO createReqVO);

    /**
     * 创建数据模型，并显式指定<b>来源</b>
     *
     * <h3>🔴 为什么来源是参数而不是 VO 上的字段</h3>
     * 来源是「这次改动是谁做的」这一<b>事实</b>，而事实只能由服务端按入口判定：
     * 走 Controller 的就是人，走 {@code DataModelApi} 的就是 AI。
     * 放进 VO 的话它就变成了一个<b>调用方自报</b>的值 ——
     * AI 只要传 {@code MANUAL}，它建出来的表就永远洗白成「人工创建」，
     * 而 {@code manual_edited_flag} 那套治理逻辑（人工接管过的 AI 产物不许被 AI 覆盖）
     * 整个失效，且失效得无声无息。
     *
     * <p>重载而不是改签名：管理页那一条调用点不关心来源，让它继续调单参版本，
     * 少一个必须传对的参数就少一处会传错的地方。
     *
     * @param createReqVO 创建信息
     * @param source      来源
     * @return 模型编号
     */
    Long createDataModel(DataModelSaveReqVO createReqVO, ArtifactSourceEnum source);

    /**
     * 更新数据模型：比对差异 + 执行 ALTER TABLE
     *
     * <p>按<b>人工入口</b>处理：若这条模型原本是 AI 建的，本次更新会把它标记为
     * 「已被人工接管」（{@code manualEditedFlag=true}，且此后不再回退）。
     *
     * @param updateReqVO 更新信息
     */
    void updateDataModel(DataModelSaveReqVO updateReqVO);

    /**
     * 更新数据模型，并显式指定<b>操作者来源</b>
     *
     * <p>🔴 {@code operator} 只决定「要不要打人工接管标记」，<b>不会</b>改写这条记录的
     * {@code source} —— 来源是出生证明，谁改的都改不掉它。
     * 理由同 {@link #createDataModel(DataModelSaveReqVO, ArtifactSourceEnum)}。
     *
     * @param updateReqVO 更新信息
     * @param operator    本次操作的入口来源
     */
    void updateDataModel(DataModelSaveReqVO updateReqVO, ArtifactSourceEnum operator);

    /**
     * 删除数据模型
     *
     * @param id            模型编号
     * @param dropTable     是否同时删除真实表
     * @param riskConfirmed 是否已确认风险
     */
    void deleteDataModel(Long id, Boolean dropTable, Boolean riskConfirmed);

    /**
     * 导入已有表为数据模型（不执行 DDL）
     *
     * <p>来源记为 {@link ArtifactSourceEnum#IMPORT}：这张表不是平台建的，
     * 平台对它的全部认知都来自这一次导入。
     *
     * @param dataSourceConfigId 数据源配置编号
     * @param tableNames         表名列表
     */
    void importTables(Long dataSourceConfigId, List<String> tableNames);

    /**
     * 获得数据模型
     *
     * @param id 模型编号
     * @return 数据模型。查不到时抛「数据模型不存在」，不返回 null ——
     *         与本接口其余按 id 读的方法（字段列表 / 完整结构 / 表数据分页）同口径
     */
    DataModelDO getDataModel(Long id);

    /**
     * 获得数据模型分页
     */
    PageResult<DataModelDO> getDataModelPage(DataModelPageReqVO pageReqVO);

    /**
     * 获得模型的字段列表（实时读取真实表结构）
     *
     * @param id 模型编号
     * @return 字段列表
     */
    List<TableColumnModel> getDataModelColumns(Long id);

    /**
     * 获得模型的完整结构：字段 + 索引（实时读取真实表结构）
     *
     * @param id 模型编号
     * @return 结构信息
     */
    DataModelStructureRespVO getDataModelStructure(Long id);

    /**
     * 获得系统内置字段列表，按默认的多租户策略
     *
     * 供「新建模型」界面以只读方式展示，让用户清楚建表后会自动补齐哪些字段。
     *
     * @return 系统字段列表
     */
    List<TableColumnModel> getSystemColumns();

    /**
     * 获得指定策略下的系统内置字段列表
     *
     * 🔴 界面必须按 profile 取，否则「展示的」与「建出来的」会不一致：
     * 新建时界面上的字段是要<b>一并提交</b>的，若选了 GLOBAL 却仍展示并提交了 tenant_id，
     * 注入器发现同名列就会让位，结果表里照样有 tenant_id —— profile 形同虚设。
     *
     * @param profile 系统列策略，null 或无法识别时按 TENANT 处理
     * @return 系统字段列表
     */
    List<TableColumnModel> getSystemColumns(String profile);

    /**
     * 预览待执行的 DDL，不实际执行
     *
     * 用于让用户在改表前看清将要发生什么 —— MySQL DDL 不可回滚，这一步很重要。
     *
     * @param saveReqVO 模型信息
     * @return DDL 语句列表
     */
    List<String> previewDdl(DataModelSaveReqVO saveReqVO);

    /**
     * 查询数据库中的表列表，用于「导入已有表」
     *
     * @param dataSourceConfigId 数据源配置编号
     * @param tableName          表名，模糊匹配
     * @param tableComment       表描述，模糊匹配
     */
    List<Map<String, Object>> getDatabaseTableList(Long dataSourceConfigId,
                                                   String tableName, String tableComment);

    /**
     * 预览表数据
     *
     * @param id       模型编号
     * @param pageNo   页码
     * @param pageSize 每页条数
     */
    PageResult<Map<String, Object>> getTableDataPage(Long id, Integer pageNo, Integer pageSize);

    /**
     * 获得 DDL 执行日志分页
     */
    PageResult<DataModelLogDO> getDataModelLogPage(DataModelLogPageReqVO pageReqVO);

}
