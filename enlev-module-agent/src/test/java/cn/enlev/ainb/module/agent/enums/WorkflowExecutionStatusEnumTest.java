package cn.enlev.ainb.module.agent.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkflowExecutionStatusEnum} 的三把「尺子」单测（迭代七 B3-2 / B3-4）
 *
 * <p>这个枚举有 9 个成员、三个静态判据（{@code isFinal} / {@code isCancellable} / {@code isResumable}）。
 * 成员本身不值一测，真正要钉的是<b>判据的口径</b>，因为它仨各把着一道闸：
 * <ul>
 *   <li>{@code isCancellable} —— {@code cancel} 的准入闸（并与 {@code casToCancelled} 的 SQL {@code IN} 同源）；</li>
 *   <li>{@code isResumable} —— {@code resume} 的准入闸；</li>
 *   <li>{@code isFinal} —— 「这行还会不会自己变」。
 *       🔴 <b>实测</b>：B3-2 落地后它在 agent 模块里<b>零生产调用方</b>（grep 只命中它自己的定义与测试），
 *       B3-4 落地时点重扫仍是零 —— 故把 CANCELLED / FAILED 加进来<b>当下不改变任何生产行为</b>，
 *       是给将来的调用方先把口径定对。写报告时不许把这一条说成「修好了一个 bug」。</li>
 * </ul>
 *
 * <p>🔴 加 {@code CANCELLED}（B3-2）与 {@code FAILED}（B3-4）时最容易写错的几处，各有一条测看着：
 * <ol>
 *   <li><b>CANCELLED 要进 {@code isFinal}</b>（取消是终态）；</li>
 *   <li><b>CANCELLED 不能进 {@code isResumable}</b>（裁定 A-Ⅰ：本轮<b>不做</b>「取消转可恢复」）；</li>
 *   <li><b>FAILED 要进 {@code isFinal}</b>（B3-4）：与 {@code AgentWorkflowCompensator#shouldCompensate}
 *       只认 COMPENSATE、以及 {@code settle} 注释「recovery=FORWARD：失败即终态」的口径对齐；</li>
 *   <li><b>FAILED 仍不能进 {@code isCancellable}</b>：它早已收尾，改写成 CANCELLED 会抹掉一次真实故障
 *       （{@code error_msg} 被「用户中断」顶掉、{@code end_time} 换成第二次的时间）。</li>
 * </ol>
 *
 * @author 走进新时代
 */
class WorkflowExecutionStatusEnumTest {

    /**
     * 九档状态在三把尺子上的<b>完整分类表</b>：{@code status → {isFinal, isCancellable, isResumable}}。
     *
     * <p>🔴 这张表同时是「加了新状态却没表态」的<b>绊网</b>，故下面那条测是<b>遍历枚举成员</b>去查表的：
     * <ul>
     *   <li>新成员不在表里 → 那条测立刻红（三个判据都是<b>白名单</b>写法，漏表态的新档会静默落进
     *       「不是终态、不可取消、也不可恢复」，编译不报错、既有测全绿）；</li>
     *   <li>删成员而表里还留着 → 表大小与成员数不等，同样红。</li>
     * </ul>
     *
     * <p>🔴 读这张表最该注意的一列是 {@code isCancellable}，它<b>不是</b> {@code !isFinal}：
     * {@code COMPENSATING} 那一档「不是终态、却不可取消」（反做完了还要再走一档，且半撤销比卡死更难查）。
     * B3-4 之后 {@code FAILED} 已进 isFinal，故 {@code !isFinal} 已不再漏 FAILED，
     * 但白名单另有一层保险 —— 新加状态默认落进「不可取消」，与「!isFinal 再补例外」的默认方向相反。
     * 详见 {@link WorkflowExecutionStatusEnum#isCancellable} 的注释。
     */
    private static final Map<String, boolean[]> CLASSIFICATION = new LinkedHashMap<>();

    static {
        //                    isFinal  isCancellable  isResumable
        CLASSIFICATION.put("CREATED",             new boolean[]{false, true,  false});
        CLASSIFICATION.put("RUNNING",             new boolean[]{false, true,  false});
        CLASSIFICATION.put("INTERRUPTED",         new boolean[]{false, true,  true});
        CLASSIFICATION.put("SUCCEEDED",           new boolean[]{true,  false, false});
        // 🔴 FAILED：B3-4 起 isFinal=true（与 recovery=FORWARD「失败即终态」的口径对齐），
        //    但 isCancellable 仍必须 false —— 它早已收尾，改写成 CANCELLED 会抹掉一次真实故障
        CLASSIFICATION.put("FAILED",              new boolean[]{true,  false, false});
        // 🔴 B3-2 新增的那一档：终态、不可再取消、也不可恢复（A-Ⅰ）
        CLASSIFICATION.put("CANCELLED",           new boolean[]{true,  false, false});
        // 🔴 COMPENSATING：不是终态（反做完了还要再走一档），但绝不可取消（半撤销比卡死更难查）
        CLASSIFICATION.put("COMPENSATING",        new boolean[]{false, false, false});
        CLASSIFICATION.put("COMPENSATED",         new boolean[]{true,  false, false});
        CLASSIFICATION.put("COMPENSATION_FAILED", new boolean[]{true,  false, false});
    }

    @Test
    @DisplayName("九档状态 × 三把尺子的分类表：逐格钉死，且表必须与成员数相等（加档不表态就红）")
    void classificationTableIsPinnedAndExhaustive() {
        for (WorkflowExecutionStatusEnum item : WorkflowExecutionStatusEnum.values()) {
            boolean[] expected = CLASSIFICATION.get(item.getStatus());
            assertNotNull(expected,
                    "状态 " + item.getStatus() + " 不在分类表里。三个判据都是白名单写法，"
                            + "漏表态的新档会静默落进「不是终态、不可取消、也不可恢复」——"
                            + "请显式决定它算哪一档，并把这一行补进 CLASSIFICATION");
            assertEquals(expected[0], WorkflowExecutionStatusEnum.isFinal(item.getStatus()),
                    item.getStatus() + " 的 isFinal 与分类表不符（isFinal 决定「这行还会不会自己变」）");
            assertEquals(expected[1], WorkflowExecutionStatusEnum.isCancellable(item.getStatus()),
                    item.getStatus() + " 的 isCancellable 与分类表不符（它把着 POST cancel 的准入闸，"
                            + "且与 casToCancelled 的 SQL IN 同源 —— 改这里等于改 SQL）");
            assertEquals(expected[2], WorkflowExecutionStatusEnum.isResumable(item.getStatus()),
                    item.getStatus() + " 的 isResumable 与分类表不符（它把着 resume 的准入闸；"
                            + "返回 false 的执行一个都恢复不了，不存在「另一条路」）");
        }
        assertEquals(WorkflowExecutionStatusEnum.values().length, CLASSIFICATION.size(),
                "分类表与枚举成员数不等：有成员被删了、或表里留着已不存在的档");
    }

    @Test
    @DisplayName("cancellableStatuses() 与 isCancellable() 必须逐字同源（SQL 的 IN 就是拿它拼的）")
    void cancellableStatusesIsTheSameSourceAsIsCancellable() {
        List<String> set = WorkflowExecutionStatusEnum.cancellableStatuses();
        assertEquals(List.of("CREATED", "RUNNING", "INTERRUPTED"), set,
                "可取消集合变了。这一份是 casToCancelled 的 SQL IN 的唯一出处，"
                        + "改它会同时改掉「预检放行什么」与「UPDATE 命中什么」，故必须过 CLASSIFICATION 那张表一起改");
        for (WorkflowExecutionStatusEnum item : WorkflowExecutionStatusEnum.values()) {
            assertEquals(set.contains(item.getStatus()),
                    WorkflowExecutionStatusEnum.isCancellable(item.getStatus()),
                    "两个取数口对 " + item.getStatus() + " 的说法不一致：一个进了 SQL、一个进了 Java 预检，"
                            + "分叉之后表现为「预检放过去了但 UPDATE 影响 0 行」或反之");
        }
        // 🔴 不可变：调用方要是能往里 add，「同源」就只是注释里的一句话
        assertTrue(isUnmodifiable(set), "cancellableStatuses() 必须返回不可变视图");
    }

    private static boolean isUnmodifiable(List<String> list) {
        try {
            list.add("PROBE");
            return false;
        } catch (UnsupportedOperationException expected) {
            return true;
        }
    }

    /**
     * 🔴 钉住「FAILED <b>在</b> {@code isFinal} 里」（迭代七 B3-4）。
     *
     * <p>这条测原先叫 {@code failedIsDeliberatelyNotFinalYet}，是<b>刻意钉住相反口径</b>的绊网：
     * B3-2 那一轮不许顺手把 FAILED 也塞进 isFinal，因为两轮改动混在一起、出问题时没法归因是哪一刀。
     * 现在 B3-4 落地，绊网翻面 —— 断言从 {@code assertFalse} 改成 {@code assertTrue}，
     * 理由也随之换成「为什么现在必须 true」。
     *
     * <p>为什么现在必须 true：{@code AgentWorkflowCompensator#shouldCompensate} 只认 {@code COMPENSATE}、
     * {@code AgentWorkflowExecutionServiceImpl#settle} 的注释也明写「recovery=FORWARD：失败即终态」，
     * 全仓没有任何路径会让 FAILED 自己再变一档。isFinal 里少了 FAILED 只会让「这行还会不会自己变」
     * 这把尺子在关键那一档上撒谎。
     *
     * <p>⚠️ 顺带记一条<b>实测</b>事实，别把它当免检凭据：{@code isFinal} 眼下在 agent 模块里
     * <b>零生产调用方</b>（B3-2 / B3-4 两轮都扫过）。所以本轮改完不改变任何生产行为，
     * 是把口径先定对、给将来的调用方铺路。写报告时不许说「修好了一个 bug」。
     */
    @Test
    @DisplayName("FAILED 现在是终态（B3-4）—— 与 recovery=FORWARD「失败即终态」的口径对齐")
    void failedIsFinalNow() {
        assertTrue(WorkflowExecutionStatusEnum.isFinal(WorkflowExecutionStatusEnum.FAILED.getStatus()),
                "⚠️ 若这条红了，说明有人把 FAILED 从 isFinal 里拿掉了。B3-4 之后 FAILED 必须算终态："
                        + "全仓没有任何路径会让它自己再变一档（compensator 只认 COMPENSATE、settle 明写 FORWARD 即终态）。"
                        + "改这里之前请先读 isFinal 与 FAILED 那两段的注释，并同步 CLASSIFICATION 表。");
        // 🔴 但 isCancellable 仍必须 false —— 终态与「可取消」是两把不同的尺子：
        //    FAILED 早已收尾，error_msg 记着失败原因、end_time 记着停在哪一刻，
        //    改写成 CANCELLED 等于抹掉一次真实故障（B3-2 的第一版实现真漏过这一档）。
        assertFalse(WorkflowExecutionStatusEnum.isCancellable(WorkflowExecutionStatusEnum.FAILED.getStatus()),
                "FAILED 是终态但不可取消：改写会抹掉 error_msg 里那句真实的失败原因");
    }

    /**
     * 🔴 裁定 A-Ⅰ 的钉子：{@code CANCELLED} 是终态、<b>且不可恢复</b>。
     *
     * <p>为什么值得单独一条测（明明已被分类表覆盖）：因为「取消转可恢复」是一个<b>看起来很自然</b>的下一步 ——
     * 用户按了停止又后悔，凭什么不能接着跑？答案是本轮<b>刻意不做</b>：checkpoint 里的 state 虽然还在，
     * 但那条入口一旦开出来，就得同时回答「已经发出去的外部副作用算不算数」
     * 「恢复后 settle 还会不会被 {@code updateByIdUnlessCancelled} 挡住」两个问题，
     * 而这两个都不在 B3-1/B3-2 的范围里。留着这条断言，是想让做那一刀的人先看见这里写过为什么没做。
     */
    @Test
    @DisplayName("A-Ⅰ：CANCELLED 是终态但<b>不可</b>恢复（本轮刻意不做「取消转可恢复」）")
    void cancelledIsFinalButNotResumable() {
        String cancelled = WorkflowExecutionStatusEnum.CANCELLED.getStatus();
        assertTrue(WorkflowExecutionStatusEnum.isFinal(cancelled), "取消是终态");
        assertFalse(WorkflowExecutionStatusEnum.isResumable(cancelled),
                "但不可恢复 —— A-Ⅰ 明确本轮不做「取消转可恢复」。要重跑得从头 run 一次");
        assertFalse(WorkflowExecutionStatusEnum.isCancellable(cancelled),
                "也不可再取消一次：重复点取消第二次拿到 0 行，据此如实回话");
    }

    // ==================== 认不出来的输入：一律 false，绝不兜底也绝不抛 ====================

    @Test
    @DisplayName("未知/空/null 状态 → 三把尺子都返回 false（不许兜底成某一档，也不许抛）")
    void unknownStatusMatchesNoRuler() {
        // 🔴 那个 null 是<b>真抓到过缺陷的</b>，不是凑数：第一版 isCancellable 直接写
        //    CANCELLABLE.contains(status)，而 CANCELLABLE 是 List.of(...) 造的 ——
        //    不可变列表的 contains(null) <b>抛 NPE</b>（ImmutableCollections.ListN.indexOf 里 requireNonNull），
        //    与 Arrays.asList(...) 的 contains(null)==false <b>行为相反</b>。
        //    入参是从库里读出来的 status，某行为 NULL 时一次「取消」就会变成 500 而不是一句干净的 STATE_INVALID。
        //    本测以 NPE 报红，据此在生产侧补了显式 null 判断。别把这条 null 删掉。
        for (String bogus : new String[]{null, "", "  ", "cancelled", "Cancelled", "CANCEL",
                "SUCCEED", "interrupted", "TELEPATHY"}) {
            assertFalse(WorkflowExecutionStatusEnum.isFinal(bogus),
                    "isFinal 对 " + bogus + " 兜底了：认不出来的值必须当「不是终态」，"
                            + "而不是猜一个 —— 猜成终态会让一行状态被写坏的记录永远无法再被 cancel 纠正");
            assertFalse(WorkflowExecutionStatusEnum.isCancellable(bogus),
                    "isCancellable 对 " + bogus + " 兜底了：猜成可取消 = 允许把一行状态不明的记录改写成 CANCELLED");
            assertFalse(WorkflowExecutionStatusEnum.isResumable(bogus),
                    "isResumable 对 " + bogus + " 兜底了：猜成可恢复会允许从一个不存在的断点续跑");
        }
        // 🔴 大小写敏感：库里的值由本枚举写入、前端也逐字比对，故不认小写是有意的
        //   （与 WaitTypeEnum#parse 同口径；那边还留着「不 trim 会静默卡死」的真缺陷记录）
        assertFalse(WorkflowExecutionStatusEnum.isFinal("succeeded"), "小写不认");
    }

    @Test
    @DisplayName("取值互不相同、与枚举名逐字一致、且都有中文名（status 是落库值，写串了会造出认不出来的历史行）")
    void statusValuesAreDistinctMatchEnumNamesAndHaveChineseLabels() {
        Set<String> seen = new HashSet<>();
        for (WorkflowExecutionStatusEnum item : WorkflowExecutionStatusEnum.values()) {
            assertTrue(seen.add(item.getStatus()),
                    "两档共用同一个落库值，三把尺子就只能靠声明顺序决定认谁：" + item.getStatus());
            // 🔴 刻意要求 name() == status：库里存的是 status，排障时人读的是日志里的枚举名。
            //   两者一旦分叉，「日志说 CANCELLED、库里存的却是别的串」这类问题只能靠猜。
            assertEquals(item.name(), item.getStatus(),
                    item.name() + " 的 status 与枚举名不一致；前端 EXEC_LABELS、只读 SQL 与日志三处都按字面量对，"
                            + "分叉之后没有一处会报错，只会各自显示成不同的东西");
            assertTrue(item.getDescription() != null && !item.getDescription().isBlank(),
                    item.name() + " 缺中文名，界面上会直接显示机器串");
        }
        assertEquals(WorkflowExecutionStatusEnum.values().length, seen.size());
        // 🔴 单独钉住本轮新增那一档的中文名：它是抽屉状态列上要显示的字，写错了运维认不出来
        assertEquals("已取消", WorkflowExecutionStatusEnum.CANCELLED.getDescription());
    }

}
