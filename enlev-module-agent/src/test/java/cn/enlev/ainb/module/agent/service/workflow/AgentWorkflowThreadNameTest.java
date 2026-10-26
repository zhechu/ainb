package cn.enlev.ainb.module.agent.service.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentWorkflowThreadName} 的「单一出处」强制点（迭代七 B3-5）。
 *
 * <h3>为什么一个三行静态方法要配六条断言</h3>
 * {@code thread_name} 是 SAA checkpoint 的<b>唯一</b>寻址键，而 SAA 那两张 GRAPH_* 表
 * 既没有 tenant_id 列、也没有本平台的 executionId —— 「租户在不在格式里」这件事
 * <b>没有任何数据库约束会替你兜</b>。写歪的表现也不是报错，而是 resume 静默命中
 * <b>另一个租户</b>的快照（数据串号级别）。这种缺陷单靠「调用一次看返回值对不对」测不出来：
 * 改前正式路径自己就是对的，错的是<b>沙箱那半另有一种形状</b>，以及<b>下一个调用点无从知道自己该照哪半抄</b>。
 * 所以本类既钉值、也扫源码，还自带探测器自检。
 *
 * <h3>六条断言各守什么，为什么互不冗余</h3>
 * <ol>
 *   <li>{@link #formatIsPinnedLiterally} 钉<b>值</b>（右边是字面量，非恒真）——
 *       🔴 这就是本轮要求的<b>变异探针</b>：把分隔符改一位（{@code :} → {@code -}）本条必红。</li>
 *   <li>{@link #differentTenantsWithSameExecutionIdNeverCollide} 钉<b>跨租户不撞</b>（本轮点名的那条）。
 *       第 1 条改分隔符也会红，但第 1 条红的是「形状变了」，本条红的是「隔离没了」——
 *       真把租户从格式里删掉时，只有本条说得清后果。</li>
 *   <li>{@link #separatorKeepsTenantAndExecutionIdUnambiguous} 钉<b>分隔符不可省</b>：
 *       {@code t1:12} 与 {@code t11:2} 必须不同。删掉分隔符（拼成 {@code t112}）时
 *       前两条<b>都可能照绿</b>（形状仍一致、租户仍在串里），只有这条能红。</li>
 *   <li>{@link #bothPathsCallTheSingleAuthority} 钉<b>双向</b>：正式与沙箱两侧源码都调权威、
 *       且都不再自己拼 —— 本轮判据「两侧各调一次、产物逐字相同」的<b>源码级</b>落点。
 *       只比返回值比不出这条：两个各自实现的方法在同一组入参下也能返回相同串，纪律却已破。</li>
 *   <li>{@link #formatIsDefinedExactlyOnceInMainSources} 钉<b>唯一定义处</b>：
 *       全 src/main 里那处拼接字面量恰好 1 命中，且落在权威方法体内。</li>
 *   <li>{@link #scannerIsNotVacuous} 是第 4、5 条的<b>自检</b>：把改造前那两段原文喂给探测器必须扫得出来。
 *       探测器扫不出东西时，前两条是<b>假绿</b>。</li>
 * </ol>
 *
 * <h3>⚠️ 扫描是文本级的</h3>
 * 分不清注释与代码，所以产品源码的注释里<b>不许出现带引号的那两处旧字面量</b>
 * （与 {@code KnownTypesHintSingleSourceTest} / {@code NodeIoContractTest} 同一取舍）。
 * 点名旧形状请写成不带引号的散文。
 *
 * @author 走进新时代
 */
class AgentWorkflowThreadNameTest {

    /**
     * 权威方法体的签名锚点。
     *
     * <p>🔴 用<b>方法名</b>定位而不是行号：行号会漂（本轮同一批文件还有 B3-3 要改）。
     */
    private static final String AUTHORITY_SIGNATURE =
            "public static String threadName(Long tenantId, Long executionId)";

    /** 改造前正式路径的拼接写法。全 src/main 只允许它在权威方法体内出现 1 次 */
    private static final String FORMAT_LITERAL = "\"t\" + tenantId";

    /** 改造前沙箱路径的机制。产品源码里应彻底消失（含注释也不许带随机 UUID 调用） */
    private static final String SANDBOX_OLD_MECHANISM = "UUID.randomUUID()";

    /** 两侧调用权威时共用的调用形态 —— 「双向都改成调它」靠它证 */
    private static final String AUTHORITY_CALL = "AgentWorkflowThreadName.threadName(";

    private static final String FORMAL_IMPL = "AgentWorkflowExecutionServiceImpl.java";
    private static final String SANDBOX_IMPL = "AgentWorkflowSandboxServiceImpl.java";
    private static final String AUTHORITY_IMPL = "AgentWorkflowThreadName.java";

    @Test
    @DisplayName("🔴 变异探针：格式逐字钉住 t{tenantId}:{executionId}，分隔符改一位本条必红")
    void formatIsPinnedLiterally() {
        // 右边是字面量、不是把左边再算一遍，所以这不是恒真断言
        assertEquals("t1:42", AgentWorkflowThreadName.threadName(1L, 42L),
                """
                thread_name 的形状与钉住的字面量不一致。两种成因都得人来判：
                ① 有意改格式 —— 那要连带迁移存量：agent_workflow_execution.thread_name 是 unique key，
                   且 SAA 的 GRAPH_THREAD / GRAPH_CHECKPOINT 里存着旧形状的在途快照，
                   改了格式 = 所有 INTERRUPTED 的执行再也 resume 不回来；
                ② 无意改坏 —— 直接改回去。这一列漂了不是报错，是跨租户串号。
                """);
        assertEquals("t2:42", AgentWorkflowThreadName.threadName(2L, 42L));
        // 沙箱那半用的合成执行 id 是 -1，负号必须原样进串（它是沙箱与真实执行在形状上的唯一区别）
        assertEquals("t1:-1", AgentWorkflowThreadName.threadName(1L, -1L));
    }

    @Test
    @DisplayName("🔴 不同租户 + 相同 executionId → threadName 必不同（本轮点名的那条）")
    void differentTenantsWithSameExecutionIdNeverCollide() {
        assertNotEquals(AgentWorkflowThreadName.threadName(1L, 42L),
                AgentWorkflowThreadName.threadName(2L, 42L),
                "同一个 executionId 在两个租户下算出了同一个 thread_name —— checkpoint 会撞车，"
                        + "resume 将静默命中另一个租户的快照");

        // 不止测相邻两个租户：批量扫一遍，杜绝「靠某个巧合的租户号才不撞」
        long executionId = 42L;
        Set<String> names = new HashSet<>();
        for (long tenantId = 1; tenantId <= 200; tenantId++) {
            names.add(AgentWorkflowThreadName.threadName(tenantId, executionId));
        }
        assertEquals(200, names.size(),
                "200 个租户配同一个 executionId 只算出 " + names.size() + " 个不同的 thread_name");

        // 反向也要成立：同租户不同执行必须互不相同，否则 resume 会命中同租户的另一条执行
        Set<String> sameTenant = new HashSet<>();
        for (long id = 1; id <= 200; id++) {
            sameTenant.add(AgentWorkflowThreadName.threadName(1L, id));
        }
        assertEquals(200, sameTenant.size(), "同租户下 200 个执行算出了重复的 thread_name");
    }

    @Test
    @DisplayName("🔴 分隔符不可省：t1:12 与 t11:2 必须不同（删掉分隔符时前两条都可能照绿）")
    void separatorKeepsTenantAndExecutionIdUnambiguous() {
        assertNotEquals(AgentWorkflowThreadName.threadName(1L, 12L),
                AgentWorkflowThreadName.threadName(11L, 2L),
                "租户号与执行号之间没有分隔符时会拼出同一个串（t112）—— "
                        + "两条不同租户不同执行的 checkpoint 就此合并");
        assertNotEquals(AgentWorkflowThreadName.threadName(1L, 23L),
                AgentWorkflowThreadName.threadName(12L, 3L));
    }

    @Test
    @DisplayName("🔴 双向：正式路径与沙箱路径的源码都调权威，且都不再自己拼")
    void bothPathsCallTheSingleAuthority() throws Exception {
        String formal = readServiceSource(FORMAL_IMPL);
        String sandbox = readServiceSource(SANDBOX_IMPL);

        // 两侧都必须真的调那个静态方法 —— 这就是判据②「两侧各调一次」的源码级证据
        assertTrue(formal.contains(AUTHORITY_CALL),
                FORMAL_IMPL + " 没调 " + AUTHORITY_CALL + "，正式路径的格式又变成自己拼的了");
        assertTrue(sandbox.contains(AUTHORITY_CALL),
                SANDBOX_IMPL + " 没调 " + AUTHORITY_CALL + "，沙箱路径的格式又变成自己拼的了");

        // 两侧都不许留着改造前那套机制
        assertEquals(0, occurrences(formal, FORMAT_LITERAL),
                FORMAL_IMPL + " 里仍有第二处格式拼接 —— 定义处不再唯一，将来改格式必然只改一处");
        assertEquals(0, occurrences(sandbox, FORMAT_LITERAL),
                SANDBOX_IMPL + " 里出现了格式拼接 —— 沙箱不该自己拼 thread_name");
        assertEquals(0, occurrences(sandbox, SANDBOX_OLD_MECHANISM),
                SANDBOX_IMPL + " 仍在用随机 UUID 拼 thread_name：那种形状<b>不含租户</b>，"
                        + "是 B3-5 要消掉的分岔本身。沙箱的隔离由每次 new 的 MemorySaver 保证，不靠随机串");

        // 🔴 逐字相同的直接证据：两侧算出来的串必须相等（同入参 → 同产物，因为只有一个实现）
        String fromFormalSide = AgentWorkflowThreadName.threadName(1L, 42L);
        String fromSandboxSide = AgentWorkflowThreadName.threadName(1L, 42L);
        assertEquals(fromFormalSide, fromSandboxSide, "两侧调同一个静态方法却得到不同产物");
    }

    @Test
    @DisplayName("🔴 唯一定义处：全 src/main 里那处拼接恰好 1 命中，且落在权威方法体内")
    void formatIsDefinedExactlyOnceInMainSources() throws Exception {
        List<String> hits = new ArrayList<>();
        for (Path file : mainSources()) {
            String src = Files.readString(file, StandardCharsets.UTF_8);
            int count = occurrences(src, FORMAT_LITERAL);
            if (count > 0) {
                hits.add(file.getFileName() + " ×" + count);
            }
        }
        assertEquals(List.of(AUTHORITY_IMPL + " ×1"), hits,
                """
                thread_name 的格式拼接不止一处（或不在权威类里）。命中清单 → """ + hits + """

                这一列是 checkpoint 的唯一寻址键，格式分岔的后果不是报错而是跨租户串号，
                所以格式只允许 AgentWorkflowThreadName#threadName 里那一份实现。
                新调用点请直接调它，不要照抄字面量。
                ⚠️ 本扫描是文本级的：若命中的是注释，请把引号去掉、改写成散文。
                """);

        // 命中的那 1 处必须在方法体内，不能是躲在类注释里的第二份「定义」
        String authority = Files.readString(
                Paths.get("src", "main", "java", "cn", "enlev", "ainb", "module", "agent",
                        "service", "workflow", AUTHORITY_IMPL), StandardCharsets.UTF_8);
        String body = methodBody(authority);
        assertEquals(1, occurrences(body, FORMAT_LITERAL),
                "权威方法体内应有且仅有 1 处格式拼接，实际 " + occurrences(body, FORMAT_LITERAL)
                        + "。方法体 → " + body);
        assertEquals(occurrences(authority, FORMAT_LITERAL), occurrences(body, FORMAT_LITERAL),
                "权威类里方法体之外还有一处拼接（多半在注释里）—— 那也是一份会各自漂移的副本");
    }

    @Test
    @DisplayName("🔴 探测器自检：改造前那两段原文必须被扫出来，否则上面两条源码断言是假绿")
    void scannerIsNotVacuous() {
        // 逐字抄自 git 历史（B3-5 改造前）：正式路径 AgentWorkflowExecutionServiceImpl、沙箱 AgentWorkflowSandboxServiceImpl
        String formalBefore = "        String threadName = \"t\" + tenantId + \":\" + executionId;";
        String sandboxBefore = "        String threadName = \"sandbox:\" + UUID.randomUUID();";

        assertEquals(1, occurrences(formalBefore, FORMAT_LITERAL),
                "探测器扫不出改造前正式路径那处拼接 ⟹ formatIsDefinedExactlyOnceInMainSources 是假绿");
        assertEquals(1, occurrences(sandboxBefore, SANDBOX_OLD_MECHANISM),
                "探测器扫不出改造前沙箱那处随机 UUID ⟹ bothPathsCallTheSingleAuthority 是假绿");
        assertEquals(0, occurrences(sandboxBefore, AUTHORITY_CALL),
                "改造前的沙箱源码本不该调权威；探测器若在这里报命中，口径太宽");

        // 反向：改造后的真实写法必须扫不出旧机制（否则上面两条测试永远红，等于没有强制点）
        String formalAfter = "        String threadName = AgentWorkflowThreadName.threadName(tenantId, executionId);";
        String sandboxAfter =
                "        String threadName = AgentWorkflowThreadName.threadName(sandboxCtx.getTenantId(), SANDBOX_EXECUTION_ID);";
        assertEquals(0, occurrences(formalAfter, FORMAT_LITERAL), "派生写法被误判成手抄，探测器口径太宽");
        assertEquals(0, occurrences(sandboxAfter, SANDBOX_OLD_MECHANISM), "派生写法被误判成随机 UUID");
        assertTrue(occurrences(formalAfter, AUTHORITY_CALL) == 1 && occurrences(sandboxAfter, AUTHORITY_CALL) == 1,
                "探测器认不出改造后的调用形态，两侧断言会退化成恒真");
    }

    // ==================== 探测器 ====================

    /** 子串出现次数（文本级，不区分注释与代码） */
    private static int occurrences(String src, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = src.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    /** 取权威方法体（含签名与收尾大括号）；找不到或大括号不配对都直接失败，不静默返回空串 */
    private static String methodBody(String source) {
        int start = source.indexOf(AUTHORITY_SIGNATURE);
        assertTrue(start >= 0,
                "找不到签名「" + AUTHORITY_SIGNATURE + "」—— 方法被改名/改可见性/删了？"
                        + "改名会让本类的源码断言一起失去对象，必须同步改 AUTHORITY_SIGNATURE");
        int depth = 0;
        for (int i = source.indexOf('{', start); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
        }
        throw new AssertionError("「" + AUTHORITY_SIGNATURE + "」的方法体大括号不配对，扫描结果不作数");
    }

    /** 读 service/workflow 下某个实现类的源码 */
    private static String readServiceSource(String fileName) throws IOException {
        Path file = Paths.get("src", "main", "java", "cn", "enlev", "ainb", "module", "agent",
                "service", "workflow", fileName);
        assertTrue(Files.isRegularFile(file),
                "找不到 " + file.toAbsolutePath() + "（工作目录应是模块根，与 NodeIoContractTest#mainSources 同一口径）");
        String src = Files.readString(file, StandardCharsets.UTF_8);
        // 下限自检：路径口径写窄只会静少扫，而少扫的强制点看着一样绿
        assertTrue(src.split("\n", -1).length >= 100,
                fileName + " 只有 " + src.split("\n", -1).length + " 行 —— 扫到的不是那个文件，本条断言不作数");
        return src;
    }

    /**
     * 全模块 src/main 的 Java 源文件（排序，便于失败信息复现）。
     *
     * <p>带一条<b>数量下限</b>的自检：口径写错（比如工作目录不是模块根）时 {@code Files.walk} 会抛异常，
     * 或者只扫到几个文件 —— 那样「唯一定义处」就成了假绿。
     */
    private static List<Path> mainSources() throws IOException {
        Path root = Paths.get("src", "main", "java");
        assertTrue(Files.isDirectory(root),
                "找不到 " + root.toAbsolutePath() + "（工作目录应是模块根，与 AgentArchitectureTest 同一口径）");
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
            assertTrue(files.size() >= 100,
                    "只扫到 " + files.size() + " 个源文件，少于 100，扫描口径坏了（别把这条测试删掉了事）");
            return files;
        }
    }
}
