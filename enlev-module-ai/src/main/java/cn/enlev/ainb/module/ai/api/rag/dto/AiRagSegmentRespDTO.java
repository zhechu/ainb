package cn.enlev.ainb.module.ai.api.rag.dto;

import lombok.Data;

/**
 * 知识库检索结果 DTO
 *
 * @author 走进新时代
 */
@Data
public class AiRagSegmentRespDTO {

    /**
     * 段落编号
     */
    private Long id;

    /**
     * 文档编号
     */
    private Long documentId;

    /**
     * 文档名称，如「表单设计-常见坑.md」
     *
     * <h3>为什么这个字段不是可有可无的</h3>
     * 这套问答的全部价值建立在「答案有出处、用户能自己去核对」上 ——
     * 实测没接 RAG 时模型也能对着「联动为什么不生效」说出一堆听着合理的通用话。
     * 引用只给段落编号而不给文档名，用户拿着 {@code documentId=37} 无从查起，
     * 那跟没有出处没区别。
     *
     * <p>取不到时为 null（文档被删了而段落还在），前端据此退化成只显示段落。
     */
    private String documentName;

    /**
     * 文档的公开地址，如 {@code https://doc.enlev.cn/guide/01-system/user}
     *
     * <p>取自 {@code ai_knowledge_document.url}。有它，引用才是<b>可点</b>的 ——
     * 光给一个标题，用户还得自己去站内搜；给了链接，「答案有出处、能自己核对」这句话才成立。
     *
     * <p>🔴 前端渲染成 {@code <a href>} 前<b>必须自己再校验一道</b>协议与主机白名单：
     * 这个值最终来自灌库时写入的字符串，一旦库被污染，{@code javascript:} 或站外地址就会顺着这里
     * 变成前端的一个 XSS / 开放重定向面。后端给了值不等于前端可以无条件信。
     *
     * <p>取不到时为 null（文档不是从手册同步来的，比如管理员在界面上手建的），前端据此退化成纯文本。
     */
    private String documentUrl;

    /**
     * 文档标题，如「用户管理 ★」
     *
     * <p>🔴 <b>这一列库里没有</b> —— {@code ai_knowledge_document} 只有 name/url/content，
     * 加列要改上游 DO 且要跑一次 DDL（prod 漏跑就是「配了但不生效」）。所以它是<b>现算</b>的：
     * 取 {@code content} 的首个一级标题。回填发生在 {@code AiRagApiImpl#fillDocumentName}，
     * 那里本来就为取 {@code documentName} 批量查了整行（含 content），故不多打一次库、不多传一个字节。
     *
     * <p>代价是标题永远与正文一致（改文档就改标题，不会漂）；取不到时回落成 {@code documentName}。
     */
    private String documentTitle;

    /**
     * 知识库编号
     */
    private Long knowledgeId;

    /**
     * 段落内容
     */
    private String content;

    /**
     * token 数
     */
    private Integer tokens;

    /**
     * 相关度得分，越大越相关
     */
    private Double score;

}
