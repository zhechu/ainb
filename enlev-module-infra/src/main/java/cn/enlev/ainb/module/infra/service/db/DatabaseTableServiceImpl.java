package cn.enlev.ainb.module.infra.service.db;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.mybatis.core.util.JdbcUtils;
import cn.enlev.ainb.module.infra.dal.dataobject.db.DataSourceConfigDO;
import com.baomidou.mybatisplus.generator.config.DataSourceConfig;
import com.baomidou.mybatisplus.generator.config.GlobalConfig;
import com.baomidou.mybatisplus.generator.config.StrategyConfig;
import com.baomidou.mybatisplus.generator.config.builder.ConfigBuilder;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import com.baomidou.mybatisplus.generator.config.rules.DateType;
import com.baomidou.mybatisplus.generator.query.SQLQuery;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据库表 Service 实现类
 *
 * @author 芋道源码
 */
@Service
public class DatabaseTableServiceImpl implements DatabaseTableService {

    @Resource
    private DataSourceConfigService dataSourceConfigService;

    @Override
    public List<TableInfo> getTableList(Long dataSourceConfigId, String nameLike, String commentLike) {
        List<TableInfo> tables = getTableList0(dataSourceConfigId, null);
        return tables.stream().filter(tableInfo -> (StrUtil.isEmpty(nameLike) || tableInfo.getName().contains(nameLike))
                        && (StrUtil.isEmpty(commentLike) || tableInfo.getComment().contains(commentLike)))
                .collect(Collectors.toList());
    }

    @Override
    public TableInfo getTable(Long dataSourceConfigId, String name) {
        return CollUtil.getFirst(getTableList0(dataSourceConfigId, name));
    }

    private List<TableInfo> getTableList0(Long dataSourceConfigId, String name) {
        // 获得数据源配置
        DataSourceConfigDO config = dataSourceConfigService.getDataSourceConfig(dataSourceConfigId);
        Assert.notNull(config, "数据源({}) 不存在！", dataSourceConfigId);

        // 使用 MyBatis Plus Generator 解析表结构
        DataSourceConfig.Builder dataSourceConfigBuilder = new DataSourceConfig.Builder(config.getUrl(), config.getUsername(),
                config.getPassword());
        if (JdbcUtils.isSQLServer(config.getUrl())) { // 特殊：SQLServer jdbc 非标准，参见 https://github.com/baomidou/mybatis-plus/issues/5419
            dataSourceConfigBuilder.databaseQueryClass(SQLQuery.class);
        }
        StrategyConfig.Builder strategyConfig = new StrategyConfig.Builder().enableSkipView(); // 忽略视图，业务上一般用不到
        if (StrUtil.isNotEmpty(name)) {
            strategyConfig.addInclude(name);
        }

        GlobalConfig globalConfig = new GlobalConfig.Builder().dateType(DateType.TIME_PACK).build(); // 只使用 LocalDateTime 类型，不使用 LocalDate
        ConfigBuilder builder = new ConfigBuilder(null, dataSourceConfigBuilder.build(), strategyConfig.build(),
                null, globalConfig, null);
        // 按照名字排序
        List<TableInfo> tables = builder.getTableInfoList().stream()
                .filter(table -> !isExcludedTable(table.getName()))
                .sorted(Comparator.comparing(TableInfo::getName))
                .toList();
        return tables;
    }

    /**
     * 是否是不该出现在选表列表里的表
     *
     * ⚠️ 为什么在这里过滤，而不是用 StrategyConfig.addExclude：
     * MyBatis-Plus 生成器里 include 与 exclude 是<b>互斥</b>的，同时设置会抛
     * 「include and exclude configurations are mutually exclusive」。
     * 原实现把 addExclude 放在 else 分支正是因为这个限制，但副作用是
     * <b>传了表名过滤就一条排除都不生效</b> —— 搜 act_ 能把工作流引擎表全搜出来。
     * 改成取回结果后统一过滤，两条路径口径才一致。
     */
    private static boolean isExcludedTable(String tableName) {
        if (StrUtil.isEmpty(tableName)) {
            return true;
        }
        String lower = tableName.toLowerCase();
        // 工作流与定时任务
        if (lower.startsWith("act_") || lower.startsWith("qrtz_") || lower.startsWith("flw_")) {
            return true;
        }
        // ORACLE 系统表
        if (lower.startsWith("impdp_") || lower.startsWith("all_") || lower.startsWith("hs_")) {
            return true;
        }
        // 带 $ 的一般都是系统表
        return tableName.contains("$");
    }

}
