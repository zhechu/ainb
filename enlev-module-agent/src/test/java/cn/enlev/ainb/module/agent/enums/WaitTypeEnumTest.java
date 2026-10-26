package cn.enlev.ainb.module.agent.enums;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.io.NodeIoContract;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WaitTypeEnum} 单测（缺陷 E2：{@code waitType} 裸字面量散在 5 个文件）
 *
 * <p>这个枚举本身只有三行常量，但它承接了原先散在各处的那把「尺子」，所以真正要钉的是<b>尺子的口径</b>：
 * <ol>
 *   <li>{@link WaitTypeEnum#parse(String)} <b>trim</b> —— 这是 E2 顺手修掉的那个真缺陷：编译期与校验器 V14 trim、
 *       运行期不 trim，于是 {@code " event "} 能发布却在挂起时不落 event_key，流程静默卡死；</li>
 *   <li>{@code parse} <b>大小写敏感</b> —— 与 {@code WorkflowRecoveryEnum#parse} 的 {@code equalsIgnoreCase}
 *       <b>相反</b>，且这个相反是有测试在管的（{@code WorkflowDslValidatorTest} 那条
 *       {@code validate_headlessWaitWrongCasedWaitType_leftToTheCompiler} 断言 {@code "EVENT"} 由编译器去拒）；</li>
 *   <li>{@code parse} 认不出来<b>返回 null，绝不兜底</b>成 DURATION —— 兜底会把「填错了」变成「按定时等待跑」，
 *       而这两种在界面上都是「发布成功」。</li>
 * </ol>
 *
 * <p>🟡 还有一条是<b>刻意钉住一个「同名」</b>：{@code DURATION.getValue()} 与 wait 契约里那个
 * {@code duration} <b>配置键</b>是同一个字符串，但属两个命名空间（取值 vs 键名）。
 * 这不是要消灭的重复，而是要<b>看得见</b>的重复 —— 见 {@link #durationValueCollidesWithTheDurationConfigKeyOnPurpose()}。
 *
 * @author 走进新时代
 */
class WaitTypeEnumTest {

    // ==================== ① parse：trim ====================

    @Test
    void parse_trimsSurroundingWhitespace() {
        // 🔴 这一条是 E2 真正的修复点，不是重构的副产品。
        //   修之前：compiler / validator trim，service 与 manifest 逐字比 ⟹ " event " 发布得过去，
        //   但挂起时 resolveWaitEventKey 认不出来、event_key 不落库，POST /event 永远匹配不到，
        //   流程停在 INTERRUPTED，既不报错也不自动唤醒。
        assertSame(WaitTypeEnum.EVENT, WaitTypeEnum.parse(" event "), "前后空格必须被 trim 掉");
        assertSame(WaitTypeEnum.EVENT, WaitTypeEnum.parse("event"), "无空格的原值当然也要认");
        assertSame(WaitTypeEnum.DURATION, WaitTypeEnum.parse("\tduration\n"), "制表符/换行同属空白");
    }

    // ==================== ② parse：大小写敏感（与 WorkflowRecoveryEnum 刻意相反） ====================

    @Test
    void parse_isCaseSensitive() {
        // ⚠️ 别照着 WorkflowRecoveryEnum#parse 改成 equalsIgnoreCase：那边兜底是因为 recovery 有「缺省值」这个概念，
        //    这边没有 —— waitType 是必填格（契约里 required=true），认不出来就该报错让作者改，
        //    而不是替他挑一个。放宽成不敏感还会让同一份 DSL 同时收到「未知 waitType」和「没声明唤醒方」两条，
        //    一个根因说两遍（WorkflowDslValidator V14 那段 🔴 注释就是在防这个）。
        assertNull(WaitTypeEnum.parse("EVENT"), "EVENT 必须认不出来，交给编译器报「未知 waitType」");
        assertNull(WaitTypeEnum.parse("Duration"), "首字母大写同样不认");
        assertNull(WaitTypeEnum.parse("dURATION"), "混合大小写同样不认");
    }

    // ==================== ③ parse：认不出来返回 null，绝不兜底 ====================

    @Test
    void parse_returnsNullInsteadOfFallingBack() {
        assertNull(WaitTypeEnum.parse(null), "null → null（configStr 对缺失键就返回 null）");
        assertNull(WaitTypeEnum.parse(""), "空串 → null");
        assertNull(WaitTypeEnum.parse("   "), "纯空白 trim 完是空串 → null");
        assertNull(WaitTypeEnum.parse("telepathy"), "没这一档 → null");
        assertNull(WaitTypeEnum.parse("wait"), "节点<b>类型名</b>不是 waitType 的取值，别混");
    }

    @Test
    void parse_roundTripsEveryMember() {
        for (WaitTypeEnum item : WaitTypeEnum.values()) {
            assertSame(item, WaitTypeEnum.parse(item.getValue()),
                    "getValue() 必须能被 parse 认回来，否则这一档在运行期是死代码：" + item);
        }
    }

    // ==================== ④ supported 分档：hitl 在册但不支持 ====================

    @Test
    void hitlIsRegisteredButUnsupported() {
        // 🔴 HITL 必须是枚举成员（哪怕 supported=false）：编译器靠 switch 命中它，才能给出
        //   「waitType=hitl 等价 hitl.confirm 节点，请直接用 hitl.confirm」这句<b>专用</b>文案。
        //   若把它从枚举里删掉，作者填 hitl 只会收到一句「未知，仅支持 duration/event」——
        //   而那是一句假话：这一档平台<b>认识</b>，只是刻意不做。
        assertSame(WaitTypeEnum.HITL, WaitTypeEnum.parse("hitl"), "hitl 要认得出来，才轮得到专用文案");
        assertFalse(WaitTypeEnum.HITL.isSupported(), "认得出来 ≠ 支持");
        assertTrue(WaitTypeEnum.DURATION.isSupported());
        assertTrue(WaitTypeEnum.EVENT.isSupported());
    }

    @Test
    void supportedValuesText_listsOnlyTheSupportedOnesInDeclarationOrder() {
        // 这串文案会被拼进「缺少 waitType（duration/event）」与「未知，仅支持 duration/event」两条报错里。
        // 钉住逐字结果：它是作者唯一能看到的「合法取值清单」，多写一档（hitl）就是教人去踩一个编译器会拒的坑。
        assertEquals("duration/event", WaitTypeEnum.supportedValuesText());
        assertFalse(WaitTypeEnum.supportedValuesText().contains(WaitTypeEnum.HITL.getValue()),
                "不支持的那一档不许出现在给作者的清单里");
    }

    // ==================== ⑤ 取值互不相同（否则 parse 变成有歧义的） ====================

    @Test
    void valuesAreDistinct() {
        Set<String> seen = new HashSet<>();
        for (WaitTypeEnum item : WaitTypeEnum.values()) {
            assertTrue(seen.add(item.getValue()),
                    "两档共用同一个取值，parse 就只能靠声明顺序决定返回谁：" + item.getValue());
        }
        assertEquals(WaitTypeEnum.values().length, seen.size());
    }

    // ==================== ⑥ 🟡 刻意钉住「一字两义」：取值 duration vs 配置键 duration ====================

    @Test
    void durationValueCollidesWithTheDurationConfigKeyOnPurpose() {
        NodeIoContract.Contract wait = NodeIoContract.of("wait");
        assertTrue(wait != null, "契约里必须有 wait 这一类，否则下面这条比对是在跟 null 比");

        Set<String> configKeys = new TreeSet<>();
        wait.inputs().forEach(p -> configKeys.add(p.key()));

        // ① 撞名的那一个：waitType 的取值 "duration" 与 wait 的配置键 "duration" 是同一个字符串
        assertTrue(configKeys.contains(WaitTypeEnum.DURATION.getValue()),
                "契约里 wait 确实有一个叫 duration 的<b>配置键</b>（等多少秒），"
                        + "它与 WaitTypeEnum.DURATION 的<b>取值</b>同字面量、不同命名空间。"
                        + "实际键集合：" + configKeys);

        // ② 撞名的<b>只有</b>这一个：event / hitl 都不是配置键（事件那格的键叫 eventKey）
        List<String> others = new ArrayList<>();
        for (WaitTypeEnum item : WaitTypeEnum.values()) {
            if (item != WaitTypeEnum.DURATION && configKeys.contains(item.getValue())) {
                others.add(item.getValue());
            }
        }
        assertTrue(others.isEmpty(),
                "除了 duration，又有别的 waitType 取值撞上了 wait 的配置键：" + others
                        + "。撞名不违法（两个命名空间各自成立），但每多一处，"
                        + "NodeIoContractRuntimeReadScanTest 那张「同名不同义」认领表就要多一条，"
                        + "而那张表的存在理由就是「这种撞名越少越好」");

        // ③ waitType 这个<b>键</b>本身也必须在册：它是必填格，前端面板与契约分析都靠它
        assertTrue(configKeys.contains("waitType"),
                "契约里必须有 waitType 这一格（required=true）；实际键集合：" + configKeys);
        assertTrue(wait.inputs().stream()
                        .anyMatch(p -> p.key().equals("waitType") && p.required()),
                "waitType 必须是必填：它认不出来时编译器是<b>拒</b>的，没有缺省档可退");
    }

}
