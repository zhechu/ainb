package cn.enlev.ainb.module.infra.service.db;

import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.mybatis.core.util.JdbcUtils;
import cn.enlev.ainb.module.infra.controller.admin.db.vo.DataSourceConfigSaveReqVO;
import cn.enlev.ainb.module.infra.dal.dataobject.db.DataSourceConfigDO;
import cn.enlev.ainb.module.infra.dal.mysql.db.DataSourceConfigMapper;
import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceProperties;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.DATA_SOURCE_CONFIG_NOT_EXISTS;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.DATA_SOURCE_CONFIG_NOT_OK;

/**
 * 数据源配置 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
public class DataSourceConfigServiceImpl implements DataSourceConfigService {

    @Resource
    private DataSourceConfigMapper dataSourceConfigMapper;

    @Resource
    private DynamicDataSourceProperties dynamicDataSourceProperties;

    @Override
    public Long createDataSourceConfig(DataSourceConfigSaveReqVO createReqVO) {
        DataSourceConfigDO config = BeanUtils.toBean(createReqVO, DataSourceConfigDO.class);
        validateConnectionOK(config);

        // 插入
        dataSourceConfigMapper.insert(config);
        // 返回
        return config.getId();
    }

    @Override
    public void updateDataSourceConfig(DataSourceConfigSaveReqVO updateReqVO) {
        // 校验存在
        validateDataSourceConfigExists(updateReqVO.getId());
        DataSourceConfigDO updateObj = BeanUtils.toBean(updateReqVO, DataSourceConfigDO.class);
        validateConnectionOK(updateObj);

        // 更新
        dataSourceConfigMapper.updateById(updateObj);
    }

    @Override
    public void deleteDataSourceConfig(Long id) {
        // 校验存在
        validateDataSourceConfigExists(id);
        // 删除
        dataSourceConfigMapper.deleteById(id);
    }

    @Override
    public void deleteDataSourceConfigList(List<Long> ids) {
        dataSourceConfigMapper.deleteByIds(ids);
    }

    private void validateDataSourceConfigExists(Long id) {
        if (dataSourceConfigMapper.selectById(id) == null) {
            throw exception(DATA_SOURCE_CONFIG_NOT_EXISTS);
        }
    }

    @Override
    public DataSourceConfigDO getDataSourceConfig(Long id) {
        // 如果 id 为 0，默认为 master 的数据源
        if (Objects.equals(id, DataSourceConfigDO.ID_MASTER)) {
            return buildMasterDataSourceConfig();
        }
        // 从 DB 中读取
        return dataSourceConfigMapper.selectById(id);
    }

    @Override
    public List<DataSourceConfigDO> getDataSourceConfigList() {
        List<DataSourceConfigDO> result = dataSourceConfigMapper.selectList();
        // 补充 master 数据源
        result.add(0, buildMasterDataSourceConfig());
        return result;
    }

    private void validateConnectionOK(DataSourceConfigDO config) {
        boolean success = JdbcUtils.isConnectionOK(config.getUrl(), config.getUsername(), config.getPassword());
        if (!success) {
            throw exception(DATA_SOURCE_CONFIG_NOT_OK);
        }
    }

    private DataSourceConfigDO buildMasterDataSourceConfig() {
        String primary = dynamicDataSourceProperties.getPrimary();
        DataSourceProperty dataSourceProperty = dynamicDataSourceProperties.getDatasource().get(primary);
        return new DataSourceConfigDO().setId(DataSourceConfigDO.ID_MASTER).setName(primary)
                .setUrl(dataSourceProperty.getUrl())
                .setUsername(dataSourceProperty.getUsername())
                .setPassword(dataSourceProperty.getPassword());
    }

    @Override
    public List<cn.enlev.ainb.module.infra.controller.admin.db.vo.DataSourceTableVO> getDataSourceTables(Long id) {
        // 获取数据源配置
        DataSourceConfigDO config = id.equals(DataSourceConfigDO.ID_MASTER)
                ? buildMasterDataSourceConfig()
                : dataSourceConfigMapper.selectById(id);
        if (config == null) {
            throw exception(DATA_SOURCE_CONFIG_NOT_EXISTS);
        }

        // 获取表列表及其字段信息
        List<JdbcUtils.TableInfo> tableInfos = JdbcUtils.getTableList(
                config.getUrl(), config.getUsername(), config.getPassword());

        // 转换为 VO
        return tableInfos.stream().map(table -> {
            cn.enlev.ainb.module.infra.controller.admin.db.vo.DataSourceTableVO vo =
                    new cn.enlev.ainb.module.infra.controller.admin.db.vo.DataSourceTableVO();
            vo.setTableName(table.getTableName());
            vo.setTableComment(table.getTableComment());

            List<cn.enlev.ainb.module.infra.controller.admin.db.vo.DataSourceTableVO.ColumnInfo> columns =
                    table.getColumns().stream().map(column -> {
                        cn.enlev.ainb.module.infra.controller.admin.db.vo.DataSourceTableVO.ColumnInfo columnVO =
                                new cn.enlev.ainb.module.infra.controller.admin.db.vo.DataSourceTableVO.ColumnInfo();
                        columnVO.setColumnName(column.getColumnName());
                        columnVO.setColumnComment(column.getColumnComment());
                        columnVO.setDataType(column.getDataType());
                        return columnVO;
                    }).collect(java.util.stream.Collectors.toList());
            vo.setColumns(columns);
            return vo;
        }).collect(java.util.stream.Collectors.toList());
    }

}
