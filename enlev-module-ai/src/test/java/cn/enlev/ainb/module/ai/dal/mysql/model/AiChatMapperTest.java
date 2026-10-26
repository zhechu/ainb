package cn.enlev.ainb.module.ai.dal.mysql.model;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;
import cn.enlev.ainb.module.ai.enums.model.AiModelTypeEnum;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiChatMapper} 的排序契约单测
 *
 * <h3>为什么一个「取默认模型」的查询要专门钉一条测试</h3>
 * 它决定了<b>不指定 modelId 时到底跑哪个模型</b>，而这个选择过去是<b>抽签</b>：
 * {@code ORDER BY sort} 在 sort 并列的行之间没有确定顺序，开发库里八个启用的通义聊天模型
 * <b>sort 全是 0</b>，于是同一句话这次走 qwen3-max（2 秒）、下次走 qwen3.5-flash（295 秒）。
 *
 * <p>实测疤（2026-10-04）：模板矩阵 e2e 连发 7 条，落到的模型依次 76 → 74 → 74 → 75 → 69 → 69 → 72，
 * 慢的那几个把前端 300 秒用例上限吃满、消息全被 abort 成 CANCELLED，整轮 45 分钟作废。
 * 而<b>后端一条 warn 都没有</b>：没有调用失败 ⟹ 故障切换不会触发；慢模型是在持续吐字 ⟹
 * 120 秒的流式 timeout 也永远不会到。唯一的线索是 {@code agent_usage_log.model_id} 在变。
 *
 * <h3>🔴 这条测试盯的是「排序里有没有 id 兜底」，不是「有没有 ORDER BY」</h3>
 * 只断言存在 {@code ORDER BY sort} 的话，改回并列不确定的写法它<b>照样是绿的</b> —— 那正是出事的形状。
 * 所以要断言的是 sort <b>之后还跟着一列</b>。
 *
 * <h3>⚠️ 只覆盖了两个查询里的一个，这个缺口是<b>已知的</b>，不是漏写</h3>
 * {@code selectFirstByStatus} 钉不住：它内部的 {@code limitN(1)} 会调
 * {@code JdbcUtils.getDbType()}，而那要从 Spring 容器里取 {@code DataSource}、
 * 真开一条连接读 {@code DatabaseProductName} 才能判断方言。纯单测里要么起 Spring + H2
 * （ai 模块目前<b>没有</b>这套基座，为此新建一套远超本改动的分量），
 * 要么往 hutool {@code SpringUtil} 的<b>全局静态</b>字段里塞一个假 ApplicationContext ——
 * 那个静态会在同一个 surefire JVM 里泄漏给后续所有测试，为了钉一行排序把整个模块的测试环境搞脏，不值。
 *
 * <p>好在这次出事的<b>就是</b>下面这条：agent 聊天不传 modelId，走的是
 * {@code AiModelApiImpl#buildChatCandidates → buildCandidateChain → selectListByStatusAndType}，
 * 链首即默认模型。{@code selectFirstByStatus} 服务的是 ai 模块自己的
 * {@code AiModelServiceImpl#getRequiredDefaultModel}，改动同款、只是没被单测钉住。
 *
 * <p>另外，单测只能验到「SQL 片段里写对了」，验不到「MySQL 真的按它排」——
 * 后者是数据库行为，不在单测的能力范围内（{@code LambdaQueryWrapperXTest} 同口径，也是断言 sqlSegment）。
 *
 * @author 走进新时代
 */
class AiChatMapperTest {

    /**
     * lambda 形式的 {@code orderByAsc(AiModelDO::getSort)} 要把方法引用解析成列名，
     * 依赖 MyBatis-Plus 的 TableInfo 缓存 —— 那个缓存平时由 mapper 注册时填充，
     * 纯单测里没有 Spring，得手动初始化一次（照 {@code LambdaQueryWrapperXTest} 的既有做法）
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiModelDO.class);
    }

    @Test
    @DisplayName("selectListByStatusAndType：候选链顺序必须有 id 兜底（链首即默认模型）")
    void selectListByStatusAndType_orderByHasDeterministicTiebreaker() {
        AiChatMapper mapper = mock(AiChatMapper.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiModelDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        when(mapper.selectList(captor.capture())).thenReturn(List.of());
        when(mapper.selectListByStatusAndType(any(), any(), any())).thenCallRealMethod();

        mapper.selectListByStatusAndType(CommonStatusEnum.ENABLE.getStatus(),
                AiModelTypeEnum.CHAT.getType(), null);

        assertTiebreakedBySortThenId(captor.getValue().getSqlSegment());
    }

    /**
     * 断言 ORDER BY 是「sort 在前、且后面还跟着一列」
     *
     * <p>不写死成 {@code "ORDER BY sort ASC,id ASC"} 这种整串比对：MP 各版本在逗号后是否留空格、
     * ASC 是否显式输出上都有差别，钉字面串会让升级 MP 时无谓变红。这里只钉<b>语义</b>：
     * 有 ORDER BY、第一列是 sort、且 sort 不是最后一列。
     */
    private static void assertTiebreakedBySortThenId(String sqlSegment) {
        int orderByAt = sqlSegment.toUpperCase().indexOf("ORDER BY");
        assertTrue(orderByAt >= 0, "应有 ORDER BY，实际 SQL 片段：" + sqlSegment);

        String[] columns = sqlSegment.substring(orderByAt + "ORDER BY".length())
                .split(",");
        assertTrue(columns.length >= 2,
                "ORDER BY 至少要两列（sort 并列时靠第二列定序），实际：" + sqlSegment);
        assertEquals("sort", columns[0].trim().replaceAll("(?i)\\s+(asc|desc)$", "").trim(),
                "第一列必须是 sort，实际：" + sqlSegment);
        assertEquals("id", columns[1].trim().replaceAll("(?i)\\s+(asc|desc)$", "").trim(),
                "第二列必须是 id 兜底，实际：" + sqlSegment);
    }

}
