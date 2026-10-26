package cn.enlev.ainb.module.infra.api.datainterface;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.api.datainterface.dto.DataInterfaceSimpleRespDTO;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceDO;
import cn.enlev.ainb.module.infra.enums.datainterface.DataInterfaceTypeEnum;
import cn.enlev.ainb.module.infra.framework.datainterface.core.param.ParamDefinition;
import cn.enlev.ainb.module.infra.service.datainterface.DataInterfaceService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 数据接口 API 实现类
 *
 * 薄委托 {@link DataInterfaceService}，只做两件事：
 * 1. 目录过滤/截断（keyword、onlyReadOnly、limit）—— 目录可能 380+ 条，
 *    不收敛就是把 token 炸弹直接递给 AI
 * 2. DO → api DTO 的白名单装配 —— 与 DataInterfaceController#toSimpleVO 同理，
 *    不能用 BeanUtils.toBean（DO 的 params 是 JSON 串、DTO 是对象列表，同名不同型），
 *    显式装配顺带保证 config/script 等实现细节永不下发
 *
 * 🔴 writeOperation 的判定以 service 的只读清单为权威（getReadOnlySimpleList 的成员），
 * 而不是 DO 的 hasWriteOperation 列 —— 该列由 sqlStatementType 推导，只对 SQL 类型可靠，
 * HTTP POST 接口恒为 false，直接搬列会把「能写」标成「只读」，误导 AI 用 query 去调它。
 *
 * @author 走进新时代
 */
@Service
@Validated
public class DataInterfaceApiImpl implements DataInterfaceApi {

    /**
     * limit 缺省值与上限：目录很大，默认只给 AI 看一页摘要
     */
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    @Resource
    private DataInterfaceService dataInterfaceService;

    @Override
    public List<DataInterfaceSimpleRespDTO> listInterfaces(String keyword, Boolean onlyReadOnly, Integer limit) {
        List<DataInterfaceDO> enabled = dataInterfaceService.getEnabledSimpleList();
        // 只读判定的权威来源（SQL 看动作类型、HTTP 看 method、静态 JSON 恒只读）
        Set<Long> readOnlyIds = dataInterfaceService.getReadOnlySimpleList().stream()
                .map(DataInterfaceDO::getId).collect(Collectors.toSet());

        Stream<DataInterfaceDO> stream = enabled.stream();
        if (Boolean.TRUE.equals(onlyReadOnly)) {
            stream = stream.filter(entity -> readOnlyIds.contains(entity.getId()));
        }
        if (StrUtil.isNotBlank(keyword)) {
            String kw = keyword.trim().toLowerCase();
            stream = stream.filter(entity ->
                    StrUtil.nullToEmpty(entity.getCode()).toLowerCase().contains(kw)
                            || StrUtil.nullToEmpty(entity.getName()).toLowerCase().contains(kw));
        }
        int capped = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        return stream.limit(capped)
                .map(entity -> toDTO(entity, !readOnlyIds.contains(entity.getId())))
                .toList();
    }

    @Override
    public Object queryData(String code, Map<String, Object> params) {
        return dataInterfaceService.invokeReadOnly(code, params);
    }

    @Override
    public Object invokeData(String code, Map<String, Object> params) {
        return dataInterfaceService.invoke(code, params);
    }

    private static DataInterfaceSimpleRespDTO toDTO(DataInterfaceDO entity, boolean writeOperation) {
        DataInterfaceSimpleRespDTO dto = new DataInterfaceSimpleRespDTO();
        dto.setCode(entity.getCode());
        dto.setName(entity.getName());
        dto.setCategory(entity.getCategory());
        dto.setType(entity.getType());
        DataInterfaceTypeEnum typeEnum = DataInterfaceTypeEnum.typeOf(entity.getType());
        dto.setTypeName(typeEnum != null ? typeEnum.getName() : null);
        dto.setWriteOperation(writeOperation);
        dto.setHasPage(Integer.valueOf(1).equals(entity.getHasPage()));
        dto.setRemark(entity.getRemark());
        dto.setParams(parseParams(entity.getParams()).stream()
                .map(DataInterfaceApiImpl::toParamDTO).toList());
        return dto;
    }

    private static DataInterfaceSimpleRespDTO.Param toParamDTO(ParamDefinition definition) {
        DataInterfaceSimpleRespDTO.Param param = new DataInterfaceSimpleRespDTO.Param();
        param.setName(definition.getName());
        param.setLabel(definition.getLabel());
        param.setType(definition.getType());
        param.setRequired(definition.getRequired());
        param.setDefaultValue(definition.getDefaultValue());
        param.setRemark(definition.getRemark());
        return param;
    }

    /**
     * 与 DataInterfaceController#parseParams / DataInterfaceServiceImpl#parseParams 同一解析路径
     */
    private static List<ParamDefinition> parseParams(String paramsJson) {
        if (StrUtil.isBlank(paramsJson)) {
            return Collections.emptyList();
        }
        List<ParamDefinition> list = JsonUtils.parseArray(paramsJson, ParamDefinition.class);
        return list == null ? Collections.emptyList() : list;
    }

}
