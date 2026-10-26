package cn.enlev.ainb.module.agent.service.workflow;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentPlannerAdoptionStatRespVO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentPlannerAdoptionDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentPlannerAdoptionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

/**
 * 规划采纳率埋点 Service 实现（迭代七 B5-3 / B5-8）
 *
 * <h3>🔴 手改判定用「语义相等」，不用「字符串相等」</h3>
 * 基线是 AI 原样产出的 DSL 文本，当前 DSL 是落库后再读出来的 —— 两者可能在<b>语义完全相同</b>的前提下
 * 文本不同（键顺序、缩进、尾随空白）。用字符串 equals 会把这种「没人改过」误判成「手改过」，
 * 采纳率数字当场失真。故 {@link #semanticEquals} 先把两边 parse 成 {@link JsonNode} 再比 ——
 * {@code ObjectNode.equals} 是<b>字段顺序无关</b>的深比较，正是我们要的「语义相等」。
 *
 * <h3>🔴 edit_count 的口径与它的诚实边界</h3>
 * 「改了几处」没有一个天然唯一的定义。本实现取：把基线与当前 DSL 各自<b>规范化</b>
 * （parse → 键排序 → 统一缩进 pretty-print）后按行做 <b>LCS 差异计数</b>
 * （= 删除行数 + 新增行数）。规范化是为了消掉「只是格式不同」的伪差异；LCS 是为了在<b>插删导致行错位</b>时
 * 仍数得准（逐行硬比会因为一行插入把后面全判成「改了」）。
 * ⚠️ 它数的是<b>规范化 JSON 的行</b>，不是「业务语义上改了几处」—— 改一个节点名可能只动 1 行，
 * 重排一个数组可能动很多行。作为「人改了多少」的量级信号足够，别把它当成精确的「改了 N 个字段」。
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class AgentPlannerAdoptionServiceImpl implements AgentPlannerAdoptionService {

    /**
     * 专用于「规范化」的 mapper：开启 {@code ORDER_MAP_ENTRIES_BY_KEYS}，让 ObjectNode 的字段按 key 排序后
     * 再 pretty-print，从而「只是键顺序不同」的两份 DSL 规范化成逐字相同的文本。
     * 🔴 不复用注入的 {@code ObjectMapper} bean：那个的配置由全局决定、未必排序键，
     * 而规范化的<b>确定性</b>是本类比对正确的前提，故自带一个配置已知的。
     */
    private static final ObjectMapper CANON_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final ObjectWriter CANON_WRITER = CANON_MAPPER.writerWithDefaultPrettyPrinter();

    /**
     * LCS 行数上限：超过就退化成「行数差的绝对值」并记日志。
     * 规范化后的 DSL 通常几百到几千行，O(n·m) 滚动数组完全够；这个上限只防极端大 DSL 把 CPU/内存打爆。
     */
    private static final int LCS_LINE_LIMIT = 20000;

    @Resource
    private AgentPlannerAdoptionMapper adoptionMapper;

    @Override
    public void recordGenerated(String code, Long defId, String baselineDsl) {
        if (StrUtil.isBlank(code)) {
            return;
        }
        // 🔴 best-effort：埋点是附加观测，写库失败（表没建、约束撞了）只记日志，绝不让「AI 生成草稿」因此抛错
        try {
            AgentPlannerAdoptionDO row = new AgentPlannerAdoptionDO();
            row.setWorkflowCode(code);
            row.setWorkflowDefId(defId);
            row.setBaselineDsl(baselineDsl);
            row.setAdopted(false);
            // handModified / editCount / adoptTime 刻意留 null：尚未确认，还没得比（三态见 DO 注释）
            row.setGenerateTime(LocalDateTime.now());
            adoptionMapper.insert(row);
            log.info("[recordGenerated][草稿({}) 已记采纳率埋点基线，defId={}]", code, defId);
        } catch (Exception ex) {
            log.warn("[recordGenerated][草稿({}) 采纳率埋点写入失败（不影响生成）：{}]", code, ex.getMessage());
        }
    }

    @Override
    public void recordAdopted(String code, String currentDsl) {
        if (StrUtil.isBlank(code)) {
            return;
        }
        // 🔴 best-effort：同理，绝不让「确认并运行」因埋点失败而抛错
        try {
            AgentPlannerAdoptionDO row = adoptionMapper.selectPendingByCode(code);
            if (row == null) {
                // 不是 AI 生成的流程（管理页手写后从列表发布）、或已确认过 → 静默跳过，不是错误
                log.info("[recordAdopted][流程({}) 无待确认的采纳率埋点行，跳过]", code);
                return;
            }
            boolean modified = !semanticEquals(row.getBaselineDsl(), currentDsl);
            // 没改就不必算行差（省一次 LCS）；改了才数「几处」
            int edits = modified ? countEdits(row.getBaselineDsl(), currentDsl) : 0;

            AgentPlannerAdoptionDO update = new AgentPlannerAdoptionDO();
            update.setId(row.getId());
            update.setAdopted(true);
            update.setHandModified(modified);
            update.setEditCount(edits);
            update.setAdoptTime(LocalDateTime.now());
            adoptionMapper.updateById(update);
            log.info("[recordAdopted][流程({}) 确认并运行：手改={}，处数={}]", code, modified, edits);
        } catch (Exception ex) {
            log.warn("[recordAdopted][流程({}) 采纳率埋点更新失败（不影响发布）：{}]", code, ex.getMessage());
        }
    }

    @Override
    public AgentPlannerAdoptionStatRespVO getAdoptionStat() {
        // 租户维度：selectCount 经 TenantBaseDO + 拦截器自动拼 tenant_id，每租户各算各的
        long generated = nz(adoptionMapper.selectCount());
        long adopted = nz(adoptionMapper.selectCount(AgentPlannerAdoptionDO::getAdopted, true));
        long handModified = nz(adoptionMapper.selectCount(new LambdaQueryWrapperX<AgentPlannerAdoptionDO>()
                .eq(AgentPlannerAdoptionDO::getAdopted, true)
                .eq(AgentPlannerAdoptionDO::getHandModified, true)));

        AgentPlannerAdoptionStatRespVO vo = new AgentPlannerAdoptionStatRespVO();
        vo.setGenerated(generated);
        vo.setAdopted(adopted);
        vo.setHandModified(handModified);
        vo.setAdoptionRate(rate(adopted, generated));
        vo.setHandModifiedRate(rate(handModified, adopted));
        return vo;
    }

    // ==================== 内部工具 ====================

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    /** 百分比（保留 1 位）；分母为 0 → null（语义「暂无」，不是「0%」，见 VO 注释） */
    private static BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
    }

    /**
     * 语义相等：parse 成 {@link JsonNode} 后深比较（字段顺序无关）。
     * 任一侧解析不了（不该发生：基线是过了校验的 DSL）→ 退回 trim 后字符串比对，仍给出一个确定答案。
     */
    private boolean semanticEquals(String a, String b) {
        String ta = StrUtil.trimToEmpty(a);
        String tb = StrUtil.trimToEmpty(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            // 有一侧为空：只有两侧都空才算「没改」
            return ta.isEmpty() && tb.isEmpty();
        }
        try {
            JsonNode na = CANON_MAPPER.readTree(ta);
            JsonNode nb = CANON_MAPPER.readTree(tb);
            return na.equals(nb);
        } catch (Exception ex) {
            log.debug("[semanticEquals][DSL 解析失败，退回字符串比对：{}]", ex.getMessage());
            return Objects.equals(ta, tb);
        }
    }

    /** 规范化后按行做 LCS 差异计数（删除行 + 新增行）。口径与诚实边界见类注释 */
    private int countEdits(String baseline, String current) {
        String[] a = canonicalLines(baseline);
        String[] b = canonicalLines(current);
        if (a.length > LCS_LINE_LIMIT || b.length > LCS_LINE_LIMIT) {
            log.warn("[countEdits][DSL 规范化后行数超上限（{} / {}），退化成行数差]", a.length, b.length);
            return Math.abs(a.length - b.length);
        }
        int lcs = lcsLength(a, b);
        return (a.length - lcs) + (b.length - lcs);
    }

    /** parse → 键排序 → pretty-print → 按行拆；解析不了就退回原文按行拆（仍给出确定结果） */
    private String[] canonicalLines(String dsl) {
        if (StrUtil.isBlank(dsl)) {
            return new String[0];
        }
        try {
            String canonical = CANON_WRITER.writeValueAsString(CANON_MAPPER.readTree(dsl));
            // 统一换行：pretty-printer 在 Windows 用 \r\n、Linux 用 \n，归一成 \n 保证跨平台行数一致
            return canonical.replace("\r\n", "\n").split("\n");
        } catch (Exception ex) {
            log.debug("[canonicalLines][DSL 解析失败，退回原文按行：{}]", ex.getMessage());
            return dsl.replace("\r\n", "\n").split("\n");
        }
    }

    /** LCS 长度（滚动数组，空间 O(min)）。只要求长度、不还原具体 diff，故两行足够 */
    private int lcsLength(String[] a, String[] b) {
        int[] prev = new int[b.length + 1];
        int[] curr = new int[b.length + 1];
        for (int i = 1; i <= a.length; i++) {
            for (int j = 1; j <= b.length; j++) {
                if (a[i - 1].equals(b[j - 1])) {
                    curr[j] = prev[j - 1] + 1;
                } else {
                    curr[j] = Math.max(prev[j], curr[j - 1]);
                }
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
            Arrays.fill(curr, 0);
        }
        return prev[b.length];
    }

}
