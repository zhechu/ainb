package cn.enlev.ainb.module.ai.api.rag;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.enlev.ainb.module.ai.controller.admin.knowledge.vo.knowledge.AiKnowledgeSaveReqVO;
import cn.enlev.ainb.module.ai.dal.dataobject.knowledge.AiKnowledgeDO;
import cn.enlev.ainb.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import cn.enlev.ainb.module.ai.dal.dataobject.knowledge.AiKnowledgeSegmentDO;
import cn.enlev.ainb.module.ai.dal.mysql.knowledge.AiKnowledgeDocumentMapper;
import cn.enlev.ainb.module.ai.dal.mysql.knowledge.AiKnowledgeMapper;
import cn.enlev.ainb.module.ai.dal.mysql.knowledge.AiKnowledgeSegmentMapper;
import cn.enlev.ainb.module.ai.service.knowledge.AiKnowledgeDocumentService;
import cn.enlev.ainb.module.ai.service.knowledge.AiKnowledgeService;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import cn.enlev.ainb.module.ai.api.rag.dto.AiRagSegmentRespDTO;
import cn.enlev.ainb.module.ai.service.knowledge.AiKnowledgeSegmentService;
import cn.enlev.ainb.module.ai.service.knowledge.bo.AiKnowledgeSegmentSearchReqBO;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 知识库检索 API 实现类
 *
 * @author 走进新时代
 */
@Service
@Validated
public class AiRagApiImpl implements AiRagApi {

    /** {@code segmentMaxTokens} 缺省值。原先是写死在方法体里的字面量 800，提到常量是为了让它有个名字可指 */
    private static final int DEFAULT_SEGMENT_MAX_TOKENS = 800;

    /**
     * ⚠️ 必须 {@code @Lazy}。
     *
     * ai 模块内部本就存在一圈循环依赖：{@code AiKnowledgeSegmentServiceImpl} 与
     * {@code AiKnowledgeDocumentServiceImpl} 互相注入，后者靠 {@code @Lazy} 打破
     * （见 {@code AiKnowledgeSegmentServiceImpl:78}）。
     *
     * <p>本类若用普通注入，会在那个循环<b>还没完成包装</b>时把 raw 实例取走，
     * Spring 直接拒绝启动：「has been injected into other beans in its raw version
     * as part of a circular reference, but has eventually been wrapped」。
     *
     * <p>这是新增 api 层时容易踩的一类问题：<b>纯新增代码也可能扰动上游模块的 bean 初始化顺序</b>。
     * 编译通过、单测通过，直到启动才炸。
     */
    @Resource
    @Lazy
    private AiKnowledgeSegmentService knowledgeSegmentService;
    @Resource
    @Lazy
    private AiKnowledgeService knowledgeService;
    @Resource
    @Lazy
    private AiKnowledgeDocumentService knowledgeDocumentService;
    @Resource
    private AiKnowledgeMapper knowledgeMapper;
    @Resource
    private AiKnowledgeDocumentMapper knowledgeDocumentMapper;
    @Resource
    private AiKnowledgeSegmentMapper knowledgeSegmentMapper;
    @Resource
    private TokenCountEstimator tokenCountEstimator;

    @Override
    public List<AiRagSegmentRespDTO> retrieve(Long knowledgeId, String query,
                                              Integer topK, Double similarityThreshold) {
        // 注意：AiKnowledgeSegmentSearchReqBO 只有 @Data、没有 @Accessors(chain)，setter 返回 void
        AiKnowledgeSegmentSearchReqBO reqBO = new AiKnowledgeSegmentSearchReqBO();
        reqBO.setKnowledgeId(knowledgeId);
        reqBO.setContent(query);
        reqBO.setTopK(topK);
        reqBO.setSimilarityThreshold(similarityThreshold);
        List<AiRagSegmentRespDTO> segments = BeanUtils.toBean(
                knowledgeSegmentService.searchKnowledgeSegment(reqBO), AiRagSegmentRespDTO.class);
        fillDocumentName(segments);
        return segments;
    }

    /**
     * 回填来源：文档名、公开地址、标题
     *
     * <p>段落表里只有 {@code documentId}，而引用要给用户看的是「依据来自哪一篇手册的哪一页」——
     * 拿着一个数字编号他无从查起，那跟没有出处没区别。给了标题还不够：标题要能<b>点</b>，
     * 否则用户还得自己去站内搜一遍，「答案可核对」这句话就只兑现了一半。
     *
     * <p>一次批量查回来，不在循环里逐条查：召回默认 5 条，
     * 逐条查就是 5 次往返，而这条链路本来就已经有一次向量检索加一次 LLM 调用了。
     *
     * <p>⚠️ {@code selectByIds} 取的是整行，{@code content}（正文，篇均万字符）也在里面 ——
     * 这不是浪费：{@code documentTitle} 正是从它的首个一级标题现算出来的（理由见 DTO 上那段注释）。
     */
    private void fillDocumentName(List<AiRagSegmentRespDTO> segments) {
        if (CollUtil.isEmpty(segments)) {
            return;
        }
        Set<Long> documentIds = segments.stream()
                .map(AiRagSegmentRespDTO::getDocumentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (documentIds.isEmpty()) {
            return;
        }
        Map<Long, AiKnowledgeDocumentDO> documentMap = knowledgeDocumentMapper.selectByIds(documentIds).stream()
                .collect(Collectors.toMap(AiKnowledgeDocumentDO::getId, d -> d, (a, b) -> a));
        // 查不到就留 null（文档被删了而段落还在），不要填个「未知文档」——
        // 那会让前端以为真有这么一篇，用户点进去是空的
        segments.forEach(segment -> {
            AiKnowledgeDocumentDO document = documentMap.get(segment.getDocumentId());
            if (document == null) {
                return;
            }
            segment.setDocumentName(document.getName());
            segment.setDocumentUrl(document.getUrl());
            segment.setDocumentTitle(extractTitle(document.getContent(), document.getName()));
        });
    }

    /**
     * 取标题：正文首个一级标题，回落到文档名
     *
     * <p>{@code ★} 后缀保留 —— 它是「本页主体为自研功能」的标记。
     *
     * <p>🔴 与 {@code AgentDocMarkdownSanitizer#extractTitle} 是同一条规则的两份实现。
     * 没抽成一份是因为 {@code ai} 模块不能反向依赖 {@code agent}（依赖单向），
     * 而回填必须在检索侧做。改规则时两处一起改。
     */
    private static String extractTitle(String content, String fallbackName) {
        if (StrUtil.isNotBlank(content)) {
            for (String line : StrUtil.split(content, '\n', 40, true, false)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("# ")) {
                    return trimmed.substring(2).strip();
                }
            }
        }
        if (StrUtil.isBlank(fallbackName)) {
            return null;
        }
        String name = fallbackName.substring(fallbackName.lastIndexOf('/') + 1)
                .replaceAll("\\.(md|markdown)$", "");
        return name.replaceFirst("^\\d+[-_]", "");
    }

    @Override
    public Long getOrCreateKnowledge(String name, String description, Long embeddingModelId,
                                     Integer topK, Double similarityThreshold) {
        // 按名称幂等：重复调用不会建出一堆同名知识库
        AiKnowledgeDO existing = knowledgeMapper.selectOne(AiKnowledgeDO::getName, name);
        if (existing != null) {
            return existing.getId();
        }
        AiKnowledgeSaveReqVO reqVO = new AiKnowledgeSaveReqVO();
        reqVO.setName(name);
        reqVO.setDescription(description);
        reqVO.setEmbeddingModelId(embeddingModelId);
        reqVO.setTopK(topK);
        reqVO.setSimilarityThreshold(similarityThreshold);
        reqVO.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return knowledgeService.createKnowledge(reqVO);
    }

    @Override
    public Long createDocumentByContent(Long knowledgeId, String name, String content) {
        return createDocumentByContent(knowledgeId, name, null, content, null);
    }

    @Override
    public Long createDocumentByContent(Long knowledgeId, String name, String url,
                                        String content, Integer segmentMaxTokens) {
        knowledgeService.validateKnowledgeExists(knowledgeId);
        AiKnowledgeDocumentDO document = new AiKnowledgeDocumentDO()
                .setKnowledgeId(knowledgeId)
                .setName(name)
                // url 是上游用来下载内容的；这里内容直接给了，所以它只剩「出处」这一个用途。
                // 没给就留个 local:// 标记说明内容不是从网络来的
                .setUrl(StrUtil.isBlank(url) ? "local://" + name : url)
                .setContent(content)
                .setContentLength(content.length())
                .setTokens(tokenCountEstimator.estimate(content))
                // segment_max_tokens 在表里是 NOT NULL 且无默认值，漏设会直接插不进去。
                // 800 是手册类语料的经验值：一节内容通常落在这个量级，
                // 切太碎会丢上下文（「常见坑」的问题与答案被拆开就都没用了）
                .setSegmentMaxTokens(segmentMaxTokens == null ? DEFAULT_SEGMENT_MAX_TOKENS : segmentMaxTokens)
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
        knowledgeDocumentMapper.insert(document);
        // 复用上游的切分与向量化 —— 只换了内容来源，不重写这部分逻辑。
        // 🔴 注意它是 @Async 的：本方法返回时段落多半还没写完、更没向量化。
        //    调用方要判「真的同步完了没有」，只能比对 countSegments / countEmbeddedSegments，
        //    不能拿本方法的返回当完成信号 —— 那正是「印着成功、其实没做」的形状
        knowledgeSegmentService.createKnowledgeSegmentBySplitContentAsync(document.getId(), content);
        return document.getId();
    }

    @Override
    public Map<String, String> getDocumentContentHashes(Long knowledgeId) {
        return knowledgeDocumentMapper.selectListByKnowledgeId(knowledgeId).stream()
                .filter(d -> d.getName() != null)
                .collect(Collectors.toMap(AiKnowledgeDocumentDO::getName,
                        d -> DigestUtil.sha256Hex(StrUtil.nullToEmpty(d.getContent())), (a, b) -> a));
    }

    @Override
    public int countSegments(Long knowledgeId) {
        Long count = knowledgeSegmentMapper.selectCount(AiKnowledgeSegmentDO::getKnowledgeId, knowledgeId);
        return count == null ? 0 : count.intValue();
    }

    @Override
    public int countEmbeddedSegments(Long knowledgeId) {
        Long count = knowledgeSegmentMapper.selectCount(new LambdaQueryWrapperX<AiKnowledgeSegmentDO>()
                .eq(AiKnowledgeSegmentDO::getKnowledgeId, knowledgeId)
                .isNotNull(AiKnowledgeSegmentDO::getVectorId)
                .ne(AiKnowledgeSegmentDO::getVectorId, AiKnowledgeSegmentDO.VECTOR_ID_EMPTY));
        return count == null ? 0 : count.intValue();
    }

    /**
     * 「这个段落向量化了没有」的唯一判据
     *
     * <p>{@link #countEmbeddedSegments}（SQL 侧）与 {@link #listUnembeddedDocumentNames}
     * （Java 侧）必须同口径，否则会出现「计数说灌完了、重灌清单说没灌完」的自相矛盾，
     * 表现为每次同步都在重灌同一批文档。抽成一个方法是为了让这种漂移无处可藏。
     */
    private static boolean isEmbedded(String vectorId) {
        return StrUtil.isNotEmpty(vectorId) && !AiKnowledgeSegmentDO.VECTOR_ID_EMPTY.equals(vectorId);
    }

    @Override
    public Set<String> listUnembeddedDocumentNames(Long knowledgeId) {
        // 只取 document_id 与 vector_id：514 段全字段拉回来是近 1MB 正文，这里一个字都不需要
        List<AiKnowledgeSegmentDO> segments = knowledgeSegmentMapper.selectList(
                new LambdaQueryWrapperX<AiKnowledgeSegmentDO>()
                        .select(AiKnowledgeSegmentDO::getDocumentId, AiKnowledgeSegmentDO::getVectorId)
                        .eq(AiKnowledgeSegmentDO::getKnowledgeId, knowledgeId));
        Map<Long, int[]> countsByDocument = new HashMap<>();
        for (AiKnowledgeSegmentDO segment : segments) {
            int[] counts = countsByDocument.computeIfAbsent(segment.getDocumentId(), id -> new int[2]);
            counts[0]++;
            if (isEmbedded(segment.getVectorId())) {
                counts[1]++;
            }
        }

        // 以「库里的文档」为准遍历，而不是以 countsByDocument 为准：
        // 一个段落都没有的文档压根不在这个 Map 里，而它同样是召回不到的
        List<AiKnowledgeDocumentDO> documents = knowledgeDocumentMapper.selectList(
                new LambdaQueryWrapperX<AiKnowledgeDocumentDO>()
                        .select(AiKnowledgeDocumentDO::getId, AiKnowledgeDocumentDO::getName)
                        .eq(AiKnowledgeDocumentDO::getKnowledgeId, knowledgeId));
        return documents.stream()
                .filter(document -> {
                    int[] counts = countsByDocument.get(document.getId());
                    return counts == null || counts[0] != counts[1];
                })
                .map(AiKnowledgeDocumentDO::getName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    @Override
    public int deleteDocumentByName(Long knowledgeId, String name) {
        List<AiKnowledgeDocumentDO> documents = knowledgeDocumentMapper
                .selectListByKnowledgeId(knowledgeId).stream()
                .filter(d -> name.equals(d.getName())).toList();
        for (AiKnowledgeDocumentDO document : documents) {
            // 走 Service 而不是直接删表：它会连带清理段落与向量，
            // 只删文档记录会在向量库里留下永远召回得到、却已无主的内容
            knowledgeDocumentService.deleteKnowledgeDocument(document.getId());
        }
        return documents.size();
    }

    @Override
    public Long findKnowledgeIdByName(String name) {
        AiKnowledgeDO knowledge = knowledgeMapper.selectOne(AiKnowledgeDO::getName, name);
        return knowledge == null ? null : knowledge.getId();
    }

    @Override
    public List<String> listDocumentNames(Long knowledgeId) {
        return knowledgeDocumentMapper.selectListByKnowledgeId(knowledgeId).stream()
                .map(AiKnowledgeDocumentDO::getName).toList();
    }

}
