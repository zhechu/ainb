package cn.enlev.ainb.module.agent.tool.impl.process;

import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.BpmProcessValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code process_create_and_deploy} 的<b>文案面</b>单元测试
 *
 * <h3>为什么这个工具需要一个只测文案的测试类</h3>
 * 它执行侧的行为（建 + 部署、二选一校验、分类回落）由集成路径与
 * {@code WorkflowDslValidator} 的相关规则覆盖；本类钉的是<b>模型看得见的那两坨字</b> ——
 * 注解 {@code description} 与 {@link ProcessCreateAndDeployTool#paramsSchema()}。
 *
 * <p>🔴 这不是「顺便测测注释」。实跑（设备报修登记，规划 2 轮收敛、warnings 为空）
 * 里模型正是照着 {@code paramsSchema()} 原先那句
 * 「formDesignCode…来自 form_save_draft 的结果」把在线表单绑到了<b>本流程自己现建的草稿</b>上，
 * 规划一路绿灯，发布体检才拒：报的还是「表单(device_repair_register)不存在，可能已被删除」
 * 这句把原因说反的话。也就是说，那句文案本身就是缺陷本体 —— 它把模型往一条
 * 「怎么排都发布不了」的路上引。文案改对了，规则（V31）才有意义；
 * 文案不改，下一次换个提示语照样挂。
 *
 * <p>⚠️ 两坨字都要断言，不能只测一处：注解 description 进的是工具清单，
 * paramsSchema 进的是参数说明，模型在哪一轮读到哪一份并不确定。
 * 只改一半的表现是「有时绕开、有时照旧踩」。
 *
 * @author 走进新时代
 */
public class ProcessCreateAndDeployToolTest {

    private final ProcessCreateAndDeployTool tool = new ProcessCreateAndDeployTool();

    private final String description = ProcessCreateAndDeployTool.class
            .getAnnotation(AgentTool.class).description();

    /**
     * 🔴 核心那条：绑的必须是【已发布的既有表单】，不能是同一 Plan 里现建的草稿
     *
     * <p>断言的是<b>事实</b>而不是措辞：把「已发布」「草稿」「发布体检是此刻的只读快照」
     * 「运行期部署照样被拒」「DDL 回滚不掉」这几件都点到，模型才有理由绕开。
     * 少任何一件，它都可能把这句当成风格建议。
     */
    @Test
    public void testBothProsesForbidBindingInFlowDraftForm() {
        for (String prose : new String[]{description, tool.paramsSchema()}) {
            assertTrue(prose.contains("【已发布的既有表单】"),
                    "要说清合格线是「已发布的既有表单」，不是「有个编码就行」：" + prose);
            assertTrue(prose.contains(ProcessCreateAndDeployTool.PARAM_FORM_DESIGN_CODE), prose);
            assertTrue(prose.contains("form_save_draft"),
                    "要点名那个会把模型引进来的工具，抽象地说「别绑现建的表单」它认不出来：" + prose);
            assertTrue(prose.contains("【草稿】"),
                    "要说清 form_save_draft 的产物只是草稿 —— 这正是「已发布」给不出的原因：" + prose);
            assertTrue(prose.contains("把原因说反"),
                    "要点名那句误导性的报错，否则模型看到「可能已被删除」会去查谁删了它：" + prose);
            assertTrue(prose.contains("DDL 回滚不掉"),
                    "要说清占位符那一档的代价是「表已建出来、回不去」，否则它看不出轻重：" + prose);
            assertTrue(prose.contains("form_publish_bundle"),
                    "要堵掉「把发布节点排前面就行」这个最自然的错误自救：" + prose);
            assertTrue(prose.contains("占位符"),
                    "字面量与占位符是两档不同的失败，只说一档它就以为另一档能用：" + prose);
            assertTrue(prose.contains("hitl"),
                    "光说「不行」不给替代路径，模型会自己编一条更糟的：" + prose);
        }
    }

    /**
     * 主表那两列必须一起点名，且**带上类型**
     *
     * <p>🔴 修复前文案只提 {@code process_instance_id}，于是「带着一列去建表」的编排
     * 会在运行期部署时才被拒（{@code FormDesignApiImpl#resolveUnavailableReason} 的第 ⑦ 条
     * 对主表单还要 {@code process_status}）。两列都要写进模型看得见的地方。
     *
     * <p>🔴 B18（方案 §10.13）之后光点名还不够：第 6 轮实录里模型<b>自己</b>补了这两列，
     * 名字全对、类型全错（{@code process_status STRING(32)}），建表/存草稿/发布一路绿灯，
     * 直到挂审批流那一刻才被标「不可用」，而建表不可逆。所以类型与长度下限也要在这两份文案里。
     *
     * <p>⚠️ 断言的是**受控类型名**（{@code STRING} / {@code TINYINT}），不是 MySQL 词：
     * 这两份文案是给 {@code datamodel_create} 的参数说明用的，那边收的就是受控名
     * （原文案写的是 {@code varchar(64)}，与它自己要喂的那个工具不是一套词表 —— B17 记的正是
     * 两份类型词表各自漂移的代价）。两列分开断言，是因为 paramsSchema 里两行之间隔着缩进换行。
     */
    @Test
    public void testBothProsesNameBothProcessColumns() {
        for (String prose : new String[]{description, tool.paramsSchema()}) {
            assertTrue(prose.contains("process_instance_id（STRING，长度不小于 64）"),
                    "第一列要连类型与长度下限一起说，否则建出部署不了的表：" + prose);
            assertTrue(prose.contains("process_status（整数型：TINYINT/INT/LONG）"),
                    "第二列要连合格类型一起说 —— 建成 STRING 不报错，只是永远挂不上流程：" + prose);
        }
        // 「只有主表单要 process_status」这层区分同样不许丢：
        // 丢了它，模型给节点表单也加一列，虽然不致命，但会污染建表清单
        assertTrue(description.contains("只有主表单要"), description);
        assertTrue(tool.paramsSchema().contains("只当节点表单绑时才只要前一列"), tool.paramsSchema());
    }

    /**
     * 🔴 反向断言：那句把模型引进死胡同的话不许回来
     *
     * <p>钉住「已删除」而不是「已改写」：将来有人觉得这段话啰嗦、
     * 想把它缩回「编码来自 form_save_draft 的结果」时，这条会红。
     */
    @Test
    public void testMisleadingSentenceStaysGone() {
        assertFalse(description.contains("来自 form_save_draft 的结果"), description);
        assertFalse(tool.paramsSchema().contains("来自 form_save_draft 的结果"), tool.paramsSchema());
    }

    /**
     * paramsSchema 还要教「那这张表怎么建才对」
     *
     * <p>注解 description 有长度顾虑，只说禁什么；schema 是参数说明，
     * 该把出路一起给：表若是同一 Plan 里 {@code datamodel_create} 建的，建表时就带上这两列。
     */
    @Test
    public void testParamsSchemaTeachesTheWayOut() {
        String schema = tool.paramsSchema();
        assertTrue(schema.contains("datamodel_create"),
                "要告诉模型「既有表单」这条路怎么和建表节点配合：" + schema);
        assertTrue(schema.contains("建表时就要带上这两列"), schema);
    }

    /**
     * 冒烟：schema 尾部的少样本骨架仍在
     *
     * <p>{@link ProcessCreateAndDeployTool#paramsSchema()} 是把
     * {@code BpmProcessValidator#FEW_SHOT_EXAMPLE} 拼在尾部的 ——
     * 上面几条断言都只看 formDesignCode 那一段，这条保证拼接没被改坏（比如少了换行、拼了个 null）。
     */
    @Test
    public void testParamsSchemaStillCarriesFewShotExample() {
        String schema = tool.paramsSchema();
        assertTrue(schema.contains("可以照抄的骨架"), schema);
        // endsWith 而不是 contains：拼在尾部这件事本身就是约束，
        // 骨架被挤到中间（比如插进了 formDesignCode 那一段里）会让模型把两件事读成一件事
        assertTrue(schema.endsWith(BpmProcessValidator.FEW_SHOT_EXAMPLE),
                "骨架必须逐字拼在尾部，不许被改写或截断：" + schema);
        assertTrue(schema.contains("approveType"),
                "骨架里最要紧的一条：漏写 approveType 会让候选人/审批方式被静默丢弃：" + schema);
    }

}
