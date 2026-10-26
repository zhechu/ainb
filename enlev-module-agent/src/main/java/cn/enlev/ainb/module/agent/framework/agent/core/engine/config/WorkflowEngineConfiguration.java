package cn.enlev.ainb.module.agent.framework.agent.core.engine.config;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Workflow 执行引擎的基础设施 bean
 *
 * <h3>checkpoint saver 用内置 {@link MysqlSaver}（SPIKE 验证①）</h3>
 * CREATE_IF_NOT_EXISTS 会自动建 GRAPH_THREAD / GRAPH_CHECKPOINT 两张表，跨进程/重启可恢复。
 *
 * <p>🔴 它走自己的 JDBC（直接吃 DataSource），<b>绕过 MyBatis-Plus 的租户拦截器</b> ——
 * 这正是我们要的：GRAPH_* 表没有 tenant_id 列，租户隔离全靠 thread_name 里编码了租户
 * （格式见 {@code AgentWorkflowThreadName}，B3-5 之后是全仓唯一定义处），
 * 不能让 enlev 的 TenantDatabaseInterceptor 去给 SAA 的裸 SQL 拼 tenant_id（拼了反而报错）。
 *
 * <h3>saver 是<b>单例共享</b>，图是每次执行新建</h3>
 * saver 持有连接池、建表逻辑，重建代价高且无状态收益；图要闭包捕获执行级标识，必须每次新建。
 * 见 DslToStateGraphCompiler 的类注释。
 *
 * @author 走进新时代
 */
@Configuration
@Slf4j
public class WorkflowEngineConfiguration {

    /**
     * checkpoint saver
     *
     * @param dataSource 主库数据源
     */
    @Bean
    public BaseCheckpointSaver workflowCheckpointSaver(DataSource dataSource) {
        log.info("[workflowCheckpointSaver][初始化 MysqlSaver，CREATE_IF_NOT_EXISTS 自动建 GRAPH_* 表]");
        return MysqlSaver.builder()
                .dataSource(dataSource)
                .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                .build();
    }

}
