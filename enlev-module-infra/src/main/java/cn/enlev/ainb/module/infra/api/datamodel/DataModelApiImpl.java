package cn.enlev.ainb.module.infra.api.datamodel;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.pojo.PageParam;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelSaveReqDTO;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelStructureRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApiImpl;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelPageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelSaveReqVO;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelStructureRespVO;
import cn.enlev.ainb.module.infra.dal.dataobject.datamodel.DataModelDO;
import cn.enlev.ainb.module.infra.enums.artifact.ArtifactSourceEnum;
import cn.enlev.ainb.module.infra.enums.datamodel.DataModelColumnTypeEnum;
import cn.enlev.ainb.module.infra.framework.datamodel.config.DataModelProperties;
import cn.enlev.ainb.module.infra.framework.datamodel.core.MysqlDdlBuilder;
import cn.enlev.ainb.module.infra.framework.datamodel.core.SqlSafeGuard;
import cn.enlev.ainb.module.infra.framework.datamodel.core.SystemColumnInjector;
import cn.enlev.ainb.module.infra.service.datamodel.DataModelService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据模型 API 实现类
 *
 * <b>每个方法都是对 {@code DataModelService} 的直接委托</b>，只做 DTO ↔ VO 的搬运。
 * 一旦这里出现 if/else 之类的判断，就意味着「api 路径」与「Controller 路径」开始分叉 ——
 * 那正是要避免的。
 *
 * <h3>🔴 上面那句对两个方法不成立，例外要写出来而不是让它悄悄烂掉</h3>
 * {@link #getPhysicalTableList} 与 {@link #getDanglingRegistrationList} 都含判断，
 * 因为它们做的是<b>两个读者之间的对账</b> —— 逻辑登记（{@code infra_data_model}）
 * 与物理表（INFORMATION_SCHEMA）各说各话，而对账这件事<b>本来就没有 Service 可以委托</b>：
 * Service 的每个方法只站在其中一个读者上。
 *
 * <p>判断只能落在这里，于是纪律换成：<b>对账的两个方向必须共用同一份归一化</b>
 * （见 {@link #registeredTableNames}），并且<b>口径差异要写在 javadoc 里</b>
 * （{@code getPhysicalTableList} 是单参数 OR，Controller 的孪生出口是双参数 AND）。
 * 否则漂移的表现不是报错，是两边都不响、只是结论相反 —— 台账 #93（id=324 悬挂登记）
 * 就是这么在库里挂了两天没人发现。
 *
 * @author 走进新时代
 */
@Service
@Validated
public class DataModelApiImpl implements DataModelApi {

    @Resource
    private DataModelService dataModelService;
    @Resource
    private DataModelProperties dataModelProperties;

    @Override
    public Long createDataModel(DataModelSaveReqDTO reqDTO) {
        // 🔴 来源在这里定死为 AI，不从 reqDTO 取 —— DTO 上【根本没有】source 字段，
        //    这是刻意的：让调用方自报来源，等于让 AI 能把自家产物洗白成「人工创建」，
        //    而 manual_edited_flag 那套「人工接管过的 AI 产物不许被 AI 覆盖」的治理逻辑
        //    就整个失效，且失效得无声无息
        return dataModelService.createDataModel(toSaveReqVO(reqDTO), ArtifactSourceEnum.AI);
    }

    @Override
    public void updateDataModel(DataModelSaveReqDTO reqDTO) {
        dataModelService.updateDataModel(toSaveReqVO(reqDTO), ArtifactSourceEnum.AI);
    }

    @Override
    public DataModelStructureRespDTO getStructure(Long id) {
        DataModelDO model = dataModelService.getDataModel(id);
        DataModelStructureRespVO structure = dataModelService.getDataModelStructure(id);

        DataModelStructureRespDTO respDTO = new DataModelStructureRespDTO();
        if (model != null) {
            respDTO.setTableName(model.getTableName());
            respDTO.setTableComment(model.getTableComment());
        }
        respDTO.setColumns(BeanUtils.toBean(structure.getColumns(),
                DataModelStructureRespDTO.ColumnDTO.class,
                // 🔴 补上 systemColumn 标记：改表是全量同步语义，
                // 调用方必须知道哪些列是「必须原样回传」的
                column -> column.setSystemColumn(SystemColumnInjector.isSystemColumn(column.getColumnName()))));
        respDTO.setIndexes(BeanUtils.toBean(structure.getIndexes(),
                DataModelStructureRespDTO.IndexDTO.class));
        // 🔴 改表要回传的两个基线。DataModelApi 没有「按 id 读登记行」的出口，
        //    所以它们只能挂在这份结构上一起给出去 —— 少给一个，
        //    AI 那条改表路径就会被 DATA_MODEL_RECORD_VERSION_REQUIRED / SCHEMA_DRIFT 挡死
        respDTO.setSchemaFingerprint(structure.getSchemaFingerprint());
        respDTO.setVersion(structure.getVersion());
        return respDTO;
    }

    @Override
    public List<String> previewDdl(DataModelSaveReqDTO reqDTO) {
        return dataModelService.previewDdl(toSaveReqVO(reqDTO));
    }

    @Override
    public List<PhysicalTableRespDTO> getPhysicalTableList(String keyword) {
        Set<String> registered = registeredTableNames();
        List<PhysicalTableRespDTO> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // 空串/纯空格与 null 必须发同一条查询。底层 TableMetaReader 也用 isNotBlank 把关，
        // 所以修前的行为「碰巧」是对的，但传下去的实参不是规范形 —— 一旦底层换成
        // tableName != null 判断，空串就会变成 LIKE '%%'（恰好等价）而纯空格会变成
        // LIKE '%%   %%'（查不到任何东西）。在这里归一化掉，不把口径押在下游的实现上。
        String kw = StrUtil.trimToNull(keyword);
        // 🔴 keyword 的语义是「表名 **或** 表注释」，而底层 getDatabaseTableList 的两个条件
        //    是 AND（TableMetaReader:138-145 各拼一个 LIKE）。把同一个 keyword 同时传进去
        //    会得到「名字和注释都含它」，比只按表名还窄 ⟹ 只能拆成两次查、按表名去重合并。
        //
        //    为什么非得支持注释：调用方是 LLM，它拿到的是中文需求，于是按中文语义词搜表（「设备」），
        //    而表名是拉丁的（biz_device_manage）—— 只按表名匹配就返回 0 条，模型据此判定
        //    「库里没有可复用的表」，然后去建新表（不可逆）。实测见 _evidence/probe107.txt【一B】：
        //    keyword=设备 时 datamodel_list 给 4 条、本方法（修前）给 0 条。
        //
        //    顺序是「先表名命中、后仅注释命中」，两段各自 ORDER BY TABLE_NAME。
        //    这个顺序是有意义的：调用方（DataModelPhysicalTableTool）会截断到 60 条，
        //    名字命中的相关性更高，该优先占位。
        for (int side = 0; side < (kw != null ? 2 : 1); side++) {
            String nameLike = side == 0 ? kw : null;
            String commentLike = side == 1 ? kw : null;
            for (Map<String, Object> row : dataModelService.getDatabaseTableList(0L, nameLike, commentLike)) {
                String name = meta(row, "tableName", "TABLE_NAME");
                String lower = name == null ? null : name.toLowerCase(Locale.ROOT);
                if (lower == null || !seen.add(lower)) {
                    continue;
                }
                String comment = meta(row, "tableComment", "TABLE_COMMENT");
                PhysicalTableRespDTO dto = new PhysicalTableRespDTO();
                dto.setTableName(name);
                dto.setTableComment(comment == null ? "" : comment);
                dto.setRegistered(registered.contains(lower));
                result.add(dto);
            }
        }
        return result;
    }

    @Override
    public List<DanglingRegistrationRespDTO> getDanglingRegistrationList() {
        // 反向对账：论域是**登记**，查找表是**物理表名**。方向与上面那个方法相反，
        // 所以上面那个方法结构上永远看不见悬挂登记（它的循环压根不经过不存在的表）。
        //
        // 🔴 物理表必须取全库（两个 like 都传 null）。实测（_evidence/probe107.txt【二】）：
        //    拿关键字并集当全集会把差集从 80 条灌水到 243 条，其中 163 条的物理表其实好好在着。
        Set<String> physical = new HashSet<>();
        for (Map<String, Object> row : dataModelService.getDatabaseTableList(0L, null, null)) {
            String name = meta(row, "tableName", "TABLE_NAME");
            if (name != null) {
                physical.add(name.toLowerCase(Locale.ROOT));
            }
        }
        List<DanglingRegistrationRespDTO> result = new ArrayList<>();
        for (DataModelSimpleRespDTO model : getDataModelList()) {
            String tableName = model.getTableName();
            if (StrUtil.isBlank(tableName)) {
                // 没有表名的登记无从对账 —— 它不是「表不在」，是「压根没说哪张表」
                continue;
            }
            if (physical.contains(tableName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            DanglingRegistrationRespDTO dto = new DanglingRegistrationRespDTO();
            dto.setId(model.getId());
            dto.setTableName(tableName);
            dto.setModelName(model.getModelName());
            dto.setTableComment(model.getTableComment());
            result.add(dto);
        }
        return result;
    }

    /**
     * 已登记的表名集合（小写归一）
     *
     * <p>🔴 两个方向的对账<b>必须共用这一份</b>：正向给物理行打 {@code registered} 标、
     * 反向找悬挂登记。各写一份小写归一化，就是「两套口径各自漂移」的起点 ——
     * 而那种漂移的表现是两边都不报错、只是结论相反。
     */
    private Set<String> registeredTableNames() {
        return getDataModelList().stream()
                .map(DataModelSimpleRespDTO::getTableName)
                .filter(Objects::nonNull)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    /**
     * 从 information_schema 的一行里取字段
     *
     * <p>底层按 information_schema 的列名返回，大小写随驱动而变，两种都取一次
     */
    private static String meta(Map<String, Object> row, String camelKey, String upperKey) {
        Object value = row.getOrDefault(camelKey, row.get(upperKey));
        return value == null ? null : value.toString();
    }

    @Override
    public void importTables(List<String> tableNames) {
        // dataSourceConfigId 恒 0：数据模型在多数据源上实际不支持（Service 用默认
        // JdbcTemplate、无 @DS），表永远在主库。让调用方自由填只会产生无效参数
        dataModelService.importTables(0L, tableNames);
    }

    @Override
    public List<DataModelSimpleRespDTO> getDataModelList() {
        DataModelPageReqVO pageReqVO = new DataModelPageReqVO();
        pageReqVO.setPageNo(1);
        // 单租户模型数在实测库里已达 286，取满一页（上限 200）不够用时由调用方按表名过滤
        pageReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        return BeanUtils.toBean(dataModelService.getDataModelPage(pageReqVO).getList(),
                DataModelSimpleRespDTO.class);
    }

    @Override
    public List<ColumnTypeRespDTO> getColumnTypeList() {
        return Arrays.stream(DataModelColumnTypeEnum.values()).map(item -> {
            ColumnTypeRespDTO dto = new ColumnTypeRespDTO();
            dto.setType(item.getType());
            dto.setMysqlType(item.getMysqlType());
            dto.setJavaType(item.getJavaType());
            dto.setNeedLength(item.getNeedLength());
            dto.setNeedScale(item.getNeedScale());
            dto.setDescription(item.getDescription());
            return dto;
        }).toList();
    }

    @Override
    public TableConstraintsRespDTO getTableConstraints() {
        TableConstraintsRespDTO dto = new TableConstraintsRespDTO();
        // 🔴 一律从既有来源取，不在这里重新写一份字面量。
        //    这个方法存在的全部意义就是「规则只有一份」，
        //    如果它自己抄一份常量，那就白做了
        dto.setTablePrefix(dataModelProperties.getTablePrefix());
        dto.setIdentifierPattern(SqlSafeGuard.snakeCasePattern());
        dto.setStringMaxLength(MysqlDdlBuilder.STRING_MAX_LENGTH);
        dto.setStringDefaultLength(MysqlDdlBuilder.STRING_DEFAULT_LENGTH);
        dto.setSystemColumnNames(List.copyOf(SystemColumnInjector.SYSTEM_COLUMNS));
        // 🔴 两列的<b>名字</b>与<b>最小长度</b>都取自 FormDesignApiImpl 的常量：判「这两列够不够格挂流程」
        //    的就绪校验（FormDesignApiImpl#resolveUnavailableReason 第 6/7 条）读的是同一份，
        //    这里再写一遍字面量的话，改了那边不会红在这里，表现是「建表放行、挂流程才拒」
        dto.setFlowColumns(List.of(
                flowColumn(FormDesignApiImpl.COLUMN_PROCESS_INSTANCE_ID,
                        List.of(DataModelColumnTypeEnum.STRING.getType()),
                        FormDesignApiImpl.PROCESS_INSTANCE_ID_MIN_LENGTH),
                flowColumn(FormDesignApiImpl.COLUMN_PROCESS_STATUS, integerColumnTypes(), null)));
        return dto;
    }

    private static DataModelApi.FlowColumnConstraint flowColumn(String columnName, List<String> allowedTypes,
                                                                Integer minLength) {
        DataModelApi.FlowColumnConstraint constraint = new DataModelApi.FlowColumnConstraint();
        constraint.setColumnName(columnName);
        constraint.setAllowedTypes(allowedTypes);
        constraint.setMinLength(minLength);
        return constraint;
    }

    /**
     * 整数族的受控类型名（TINYINT / INT / LONG）
     *
     * <p>🔴 判据取枚举自己的 {@code javaType}，不在这里把三个名字再列一遍：
     * 「哪些受控类型是整数」只有 {@code DataModelColumnTypeEnum} 说得准，日后加一个整数类型这里自动跟上。
     * 比对用 {@code Integer.class.getSimpleName()} 而不是字面量 {@code "Integer"} 同理 ——
     * 枚举里那个字符串被人改错时，这里会当场红，而不是悄悄放行一个非整数类型。
     */
    private static List<String> integerColumnTypes() {
        Set<String> integerJavaTypes = Set.of(Integer.class.getSimpleName(), Long.class.getSimpleName());
        return Arrays.stream(DataModelColumnTypeEnum.values())
                .filter(item -> integerJavaTypes.contains(item.getJavaType()))
                .map(DataModelColumnTypeEnum::getType)
                .toList();
    }

    private DataModelSaveReqVO toSaveReqVO(DataModelSaveReqDTO reqDTO) {
        return BeanUtils.toBean(reqDTO, DataModelSaveReqVO.class);
    }

}
