package cn.enlev.ainb.module.agent.framework;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * agent 模块的架构约束测试
 *
 * <p>这几条禁令光靠约定挡不住 —— 它们要防的恰恰是「照着隔壁模块抄」这个最自然的动作。
 * 用源码扫描而不是 ArchUnit，是因为要拦的是<b>字面用法</b>而非类型依赖，
 * 扫文本更直接、也不必为此引入新依赖。
 *
 * <p>🔴 每条禁令都配一句「反向自检」。文本扫描有个共通的失效方式：<b>把要守的代码删掉，禁令就更绿</b>
 * （判定没了，违规自然没了；目录挪走了，{@code Files.walk} 什么都不返回）。
 * 所以每条禁令旁边都钉一句「那段东西必须还在 / 那个目录必须扫得到」，
 * 让「静默失效」变成「撞红」。方案 §4.2 管这叫变异探针。
 *
 * @author 走进新时代
 */
public class AgentArchitectureTest {

    /** 本模块的 main 源码根目录 */
    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");

    private List<Path> javaFiles() throws IOException {
        return javaFilesUnder(SOURCE_ROOT);
    }

    /** 找出包含指定片段的源文件（忽略整行注释，避免注释里提到就误报） */
    private List<String> findViolations(String fragment) throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path file : javaFiles()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (isCommentLine(line)) {
                    continue;
                }
                if (line.contains(fragment)) {
                    hits.add(file + ":" + (i + 1) + "  " + line.trim());
                }
            }
        }
        return hits;
    }

    @Test
    @DisplayName("🔴 禁止使用 TenantUtils.executeIgnore —— 它把租户隔离整个关掉")
    void testNoTenantExecuteIgnore() throws IOException {
        List<String> violations = findViolations("executeIgnore");
        assertTrue(violations.isEmpty(),
                """
                agent 模块不允许出现 TenantUtils.executeIgnore。

                它会让 TenantDatabaseInterceptor 直接 return true、不拼 tenant_id，
                等于把租户隔离整个关掉。ai 模块有 8 处这样的用法，注释写的是
                「忽略租户，因为 Flux 异步无法透传租户」—— 但实测（见 AgentDiagnosticController）
                表明异步链路上上下文大多是拿得到的，真正拿不到的是预先创建好的线程池。

                正确做法：用 AgentExecutionContext.snapshot() 在 Servlet 线程上捕获身份，
                再在任意线程上 execute(...) 显式重建。拿不到身份就拒绝执行，不要降级放行。

                违规位置：
                """ + String.join("\n", violations));
    }

    // ==================== 租户隔离：注解形式也在禁令内（E11 = B4-6） ====================

    /**
     * {@code TenantIgnore} 的白名单：相对 {@code src/main/java} 的路径（正斜杠）→ 理由与<b>恰好</b>几处
     *
     * <p>🔴 钉「恰好几处」而不是「这个文件豁免」：文件级豁免正是新违规的藏身处 ——
     * 谁在同一个文件里再开一处 {@code @TenantIgnore}，禁令照样绿。钉了处数，多一处就红，
     * 而红的出路是「回到本表把理由重新写一遍」，那正是想要的那次停顿。
     * 口径与 {@code CommentLineRefBanTest} 类注释里那句「白名单正是新违规藏身之处」一致。
     *
     * <p>⚠️ 路径用正斜杠：Windows 上 {@code Path#toString()} 是反斜杠，直接拿它当 Map 键必然对不上，
     * 于是白名单静默失效、豁免过的那处立刻变成违规（这种失效会红，算是运气好；反过来「该红的没红」才致命）。
     */
    private static final Map<String, Allowance> TENANT_IGNORE_ALLOWED = Map.of(
            "cn/enlev/ainb/module/agent/job/AgentUsageLogRetentionJob.java",
            new Allowance("""
                    跨租户的运维动作：把已过保留期的 agent_usage_log.input_text（用户提问原文快照）置空。
                    它成立靠三件事同时满足 —— ① 判据是每行自己的 retention_days，逐行自证，不跨租户取数；
                    ② 只置空、不删行，且不把任何数据返回给任何用户（返回值只是一个条数）；
                    ③ 定时任务线程上压根没有当前租户，不忽略就等于一行都清不掉、还不报错。
                    🔴 别拿它当先例去豁免「读租户数据再据此行动」的活：那两个 scheduler
                    （WorkflowScheduleTriggerScheduler / WorkflowWaitResumeScheduler）必须逐租户
                    TenantUtils.execute(tenantId, ...)。本条能豁免是因为它不读、只销毁。""", 2));

    /** 白名单的一条：为什么允许 + 该文件里恰好允许出现几处 */
    private record Allowance(String reason, int occurrences) {
    }

    /** 一处命中：行号（从 1 起）与该行 trim 后的原文 */
    private record Hit(int line, String text) {
    }

    /**
     * 扫一个文件里<b>非注释行</b>上的 {@code TenantIgnore}，覆盖 import、注解与全限定名三种写法
     *
     * <p>🔴 刻意扫裸 token {@code TenantIgnore} 而不是 {@code @TenantIgnore}：只扫带 {@code @} 的那种，
     * 会漏掉 {@code import} 行（它没有 {@code @}）、也会漏掉全限定名写法。而多扫出来的 import 行
     * 恰好把「这个文件确实在用注解」与「注解那一行」绑成同一处豁免，正是白名单要钉处数的原因。
     *
     * <p>沿用 {@link #isCommentLine} 的整行前缀口径：javadoc 里「刻意不用 {@code @TenantIgnore}」
     * 这类<b>反面</b>提及不该报（2026-10-06 实测：src/main 里含 {@code TenantIgnore} 的 9 行中
     * <b>7 行是注释</b>、多为「绝不用」这种反面提及，只有 2 行是代码且都在白名单那一个文件里）。
     * ⚠️ 代价是行尾注释会被当代码 —— 方向是偏严（可能误报），对禁令来说可以接受。
     *
     * <p>⚠️ 另一处已实测到的偏严：扫的是<b>裸 token</b>，故<b>标识符名字里含这四个词</b>的也会命中 ——
     * 变异探针那次就撞上了（探针类名叫 {@code MutationProbeTenantIgnore}，它的<b>类声明行</b>被一并报出来，
     * 于是预期 2 处实际报了 3 处）。这里刻意<b>不</b>收窄成正则：对一条安全禁令来说，
     * 「多报一个名字里带 TenantIgnore 的类」的代价是改个名，而「少报一种没想到的写法」的代价是隔离被静默关掉。
     * 真要收窄，得同时保证 {@code @TenantIgnore} / 全限定名 / {@code import} 三种形状一个不漏 ——
     * 收益不抵风险。命中了但不是注解用法时，改名即可，别加白名单。
     */
    private List<Hit> tenantIgnoreHitsIn(Path file) throws IOException {
        List<Hit> hits = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!isCommentLine(line) && line.contains("TenantIgnore")) {
                hits.add(new Hit(i + 1, line.trim()));
            }
        }
        return hits;
    }

    /** 相对 {@code src/main/java} 的路径，正斜杠（供白名单当键、也供报告用） */
    private static String relOf(Path file) {
        return SOURCE_ROOT.relativize(file).toString().replace('\\', '/');
    }

    @Test
    @DisplayName("🔴 禁止 @TenantIgnore —— 它与 executeIgnore 设的是同一个标志位，只是换了个写法")
    void testNoTenantIgnoreAnnotation() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String rel = relOf(file);
            if (TENANT_IGNORE_ALLOWED.containsKey(rel)) {
                continue;
            }
            for (Hit hit : tenantIgnoreHitsIn(file)) {
                violations.add(rel + ":" + hit.line() + "  " + hit.text());
            }
        }
        assertTrue(violations.isEmpty(),
                """
                agent 模块不允许出现 TenantIgnore —— 注解形式与 executeIgnore 是同一个洞。

                🔴 为什么必须单独扫一遍注解：TenantIgnoreAspect 与 TenantUtils.executeIgnore 结构同型
                （都是「存下旧值 → TenantContextHolder.setIgnore(true) → 执行 → finally 还原旧值」，
                前者只多一步 SpEL 条件判断），框架自己的 javadoc 就写着「整体逻辑的实现，
                和 TenantUtils#executeIgnore(Runnable) 需要保持一致」。而上面那条 testNoTenantExecuteIgnore 调的是 findViolations("executeIgnore")，
                纯字面量 contains ⟹ 写注解的人一个字都不用提 executeIgnore，结构上进不了那个扫描域。
                本模块现在就有 1 处活的 @TenantIgnore（AgentUsageLogRetentionJob，已进白名单），
                旧断言从头到尾看不见它 —— 这就是缺陷 E11。

                🔴 注解形式还比 executeIgnore 更宽：@Target 含 TYPE 且 @Inherited，
                贴在 DO 类上等于把整张表加进 ignoreTables、贴在 Controller 类上等于把整个 URL
                加进 ignoreUrls（infra 的 CodegenTableDO / ConfigDO / FileConfigDO 就是这么用的）；
                它还有一个支持 SpEL 的 enable()，可以「按条件」忽略。
                这几种形状字面量扫 executeIgnore 一个都看不见。

                正确做法（与 executeIgnore 那条完全相同）：
                  · 跨租户要【读】数据再据此行动 → 逐租户 TenantUtils.execute(tenantId, ...)，
                    照 WorkflowScheduleTriggerScheduler / WorkflowWaitResumeScheduler 抄
                  · 异步链路上身份透传不过去 → AgentExecutionContext.snapshot() 在 Servlet 线程上捕获，
                    再在任意线程上 execute(...) 显式重建；拿不到身份就拒绝执行，不要降级放行
                  · 确实属于「逐行自证 + 只销毁不外泄 + 线程上没有当前租户」的运维动作 → 才考虑豁免，
                    并到 TENANT_IGNORE_ALLOWED 里把理由与【恰好处数】一起写清楚
                    （只写理由不钉处数 = 文件级豁免 = 新违规的藏身处）

                违规位置：
                """ + String.join("\n", violations));
    }

    /**
     * 反向自检：白名单不得是空头支票
     *
     * <p>🔴 少了这一条，「把要守的东西删掉，禁令就更绿」这个共通失效方式在白名单上会<b>加倍</b>成立：
     * 豁免的那个文件被改名/挪走时，{@code relOf} 对不上键 ⟹ 该文件不再被跳过，但同时它也扫不到了
     * （不在 {@code src/main/java} 下），于是禁令<b>照旧全绿</b>，而白名单里那条理由已经指向一个不存在的东西。
     * 处数与「必须至少有一处是注解形式」两条一起钉，才能让「静默失效」变成「撞红」。
     *
     * <p>🔴 第三条尤其要紧：它钉的是「注解形式<b>确实进了扫描域</b>」。若哪天那处改成别的写法，
     * 而本自检没有这一句，它就会退化成一条恒绿的空话 —— 而 E11 这个盲区正是这么来的。
     */
    @Test
    @DisplayName("🔴 反向自检：白名单那一处必须真的还在、处数没变、且真的就是注解形式")
    void testTenantIgnoreAllowlistIsNotStale() throws IOException {
        for (Map.Entry<String, Allowance> entry : TENANT_IGNORE_ALLOWED.entrySet()) {
            Path file = SOURCE_ROOT.resolve(entry.getKey());
            assertTrue(Files.isRegularFile(file),
                    "白名单里的 " + entry.getKey() + " 在 src/main/java 下已经找不到了。"
                            + "若该类被改名/挪走，请把白名单的键跟着改到新路径；"
                            + "若它已经不再需要忽略租户，请连这一条白名单一起删掉 —— "
                            + "留着一条指向不存在文件的豁免，等于给未来的同名文件预留了一张空白支票");

            List<Hit> hits = tenantIgnoreHitsIn(file);
            Allowance allowed = entry.getValue();
            assertEquals(allowed.occurrences(), hits.size(),
                    "白名单钉的是「" + entry.getKey() + " 里恰好 " + allowed.occurrences()
                            + " 处 TenantIgnore」，实际扫到 " + hits.size() + " 处：" + hits + "。"
                            + "多了 = 有人在这个文件里又开了一处（文件级豁免正是新违规的藏身处）；"
                            + "少了 = 那处已经改掉。两种都请回到 TENANT_IGNORE_ALLOWED 重新核一遍理由与处数。"
                            + " 原理由：" + allowed.reason());
            assertTrue(hits.stream().anyMatch(h -> h.text().startsWith("@TenantIgnore")),
                    "白名单这一处已经不是【注解形式】了：" + hits + "。"
                            + "本条自检要钉的恰恰是「注解形式确实进了扫描域」—— 它一旦退化成别的写法，"
                            + "这条自检就变成恒绿的空话，而 E11 那个盲区正是这么来的");
        }
    }

    @Test
    @DisplayName("🔴 禁止自定义系统表前缀常量 —— 名单只能有一份")
    void testNoCustomTablePrefixConstants() throws IOException {
        List<String> violations = new ArrayList<>();
        // 这些前缀字面量一旦出现在 agent 模块，几乎必然是在自己攒一份名单
        for (String prefix : List.of("\"system_\"", "\"infra_\"", "\"bpm_\"", "\"act_\"",
                "\"qrtz_\"", "\"sys_\"", "\"flw_\"")) {
            violations.addAll(findViolations(prefix));
        }
        assertTrue(violations.isEmpty(),
                """
                agent 模块不得自己定义表名前缀常量。

                这份名单在 S0 之前后端有 5 份副本、三套口径，其中表单设计那 3 份只认
                system_/infra_/bpm_ —— 于是 sys_grant 能被绑定为可写主表，绕过整个权限判定链。
                修复方式就是收敛成唯一一份 SystemTablePolicy，再多一份就是退回去。

                正确做法：调 SystemTablePolicy.isAllowed(tableName, TableOperation.XXX)，
                按操作类型取对应口径（READ 无限制 / WRITE 8 个前缀 / DDL 与 REGISTER 24 个）。

                违规位置：
                """ + String.join("\n", violations));
    }

    @Test
    @DisplayName("🔴 跨模块只能走 api/ 层 —— 这是「行权限 fail-open 够不着 AI」的唯一支撑")
    void testOnlyDependOnApiLayer() throws IOException {
        // 权限边界清单第 7 条要验的是「让 AI 查询用户无权查看的数据 → 行权限生效」。
        // 当前它验不出东西，因为<b>没有任何工具能拿到业务行数据</b> —— 而这不是碰巧：
        // agent 只能调其它模块的 api/ 层，那一层至今没有暴露任何返回业务行数据的方法。
        //
        // 🔴 这个前提一旦破了，第 7 条立刻从「不可达」变成「未覆盖的真实风险」：
        //    行权限在拿不到 userId 时是<b>放行</b>的（DataRowFilterResolver:144-146），
        //    字段权限同样（PermissionFieldHelper:80-87），两者都只打一条 warn。
        //    它们的设计前提是「走到这里的只有定时任务与 MQ，结果不流向用户」，
        //    而 AI 的执行结果恰恰要流向用户。
        //
        // 所以这条测试挡的不是「耦合」，是「有人给 agent 开一条直连 Service 的口子，
        // 而没人意识到那等于绕过了行权限」。
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (!trimmed.startsWith("import cn.enlev.ainb.module.")) {
                    continue;
                }
                String path = trimmed.substring("import cn.enlev.ainb.module.".length());
                String[] segments = path.split("\\.");
                // 本模块自己的类不管；其它模块的必须是 <module>.api.*
                if (segments.length < 2 || "agent".equals(segments[0])
                        || "api".equals(segments[1])) {
                    continue;
                }
                violations.add(file + ":" + (i + 1) + "  " + trimmed);
            }
        }
        assertTrue(violations.isEmpty(),
                """
                agent 模块只能依赖其它模块的 api/ 层。

                这不只是分层洁癖。权限边界清单第 7 条（行权限 fail-open）之所以「验不出东西」，
                正是因为 api/ 层没有任何返回业务行数据的方法 —— 攻击面压根不存在。
                直连 Service / Mapper 会立刻把这个前提打掉，而行权限在拿不到 userId 时是放行的，
                失效的表现是静默的越权读取，不报错。

                要新增跨模块能力，请在对方模块的 api/ 包里加接口；
                如果新方法会返回业务行数据，先回到本测试的注释，把第 7 条重新验一遍。

                违规位置：
                """ + String.join("\n", violations));
    }

    @Test
    @DisplayName("扫描确实覆盖到了源码 —— 防止测试本身空跑")
    void testScannerActuallyCoversSources() throws IOException {
        List<Path> files = javaFiles();
        assertTrue(files.size() >= 2,
                "扫描到的源文件只有 " + files.size() + " 个，工作目录或路径大概率不对，"
                        + "这会让上面两条禁令变成永远通过的空测试");
        // 反向自检：诊断端点里一定有 "ContextSnapshot" 这个词，扫得到才说明扫描逻辑有效
        assertTrue(!findViolations("ContextSnapshot").isEmpty(),
                "扫描逻辑失效：连已知存在的标识符都找不到");
    }

    // ==================== 风险等级只准读「声明值」（迭代四 §8.1 第 3 条） ====================

    /**
     * 只准读 {@code getDeclaredRisk()} 的两条路径：DSL 校验、Planner prompt 装配。
     *
     * <p>为什么是这两个目录而不是全模块：生效值 {@code getRisk()} 在管理页 / 设计器名称目录 /
     * 诊断端点上<b>本来就该</b>读——那几处要给管理员看「你维护之后现在是什么」，
     * 拿声明值糊上去反而骗人。要禁的是<b>拿它做判定</b>，而做判定的就集中在下面两处。
     */
    private static final List<String> DECLARED_RISK_ONLY_DIRS = List.of(
            "cn/enlev/ainb/module/agent/framework/agent/core/validate",
            "cn/enlev/ainb/module/agent/framework/agent/core/plan");

    /**
     * {@code isRequireConfirm()} 唯一允许的接收者名字：一个从「声明风险级」取来的 {@link cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel} 局部变量。
     *
     * <p>🔴 为什么不能一刀切禁掉 {@code isRequireConfirm()}：它是 {@code RiskLevel} 上的<b>合法</b>方法，
     * 「这个声明档要不要确认」本身就是校验要问的问题（{@code WorkflowDslValidator:693} 正在这么用）。
     * 要禁的是 {@code ToolDefinition#isRequireConfirm()} —— 那个等价于 {@code getRisk().isRequireConfirm()}，
     * 走的是租户生效值。文本扫描分不出接收者的<b>类型</b>，只能分名字，故退一步按接收者名字放行。
     *
     * <p>🔴 放行不等于免检：{@link #assertReceiverComesFromDeclaredRisk} 还要回到该文件里找这个变量的
     * 赋值行，验它确实来自 {@code getDeclaredRisk()} / {@code declaredRiskOf}。
     * 否则把 {@code getRisk()} 的结果命名成 {@code declared} 就能绕过整条禁令 ——
     * 那需要刻意作假，但「刻意作假才绕得过」和「顺手一写就绕得过」是两种完全不同的防线强度。
     */
    private static final Set<String> DECLARED_RISK_RECEIVERS = Set.of("declared", "declaredRisk");

    /** 赋值右侧只要出现这两个之一，就认它取的是声明风险级 */
    private static final Pattern DECLARED_RISK_SOURCE =
            Pattern.compile("getDeclaredRisk\\s*\\(|declaredRiskOf\\s*\\(");

    private static final Pattern REQUIRE_CONFIRM_CALL =
            Pattern.compile("(\\w+)\\s*\\.\\s*isRequireConfirm\\s*\\(");

    @Test
    @DisplayName("🔴 校验路径与 Planner prompt 装配路径禁用 getRisk()/ToolDefinition#isRequireConfirm()")
    void testDeclaredRiskOnlyOnGatePaths() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String dir : DECLARED_RISK_ONLY_DIRS) {
            Path root = SOURCE_ROOT.resolve(dir);
            List<Path> files = javaFilesUnder(root);
            // 🔴 目录不存在时 Files.walk 什么都不返回，禁令会变成永远通过的空测试。
            //    包被改名/挪走是重构里最寻常不过的动作，这一句让那种情况先撞红而不是静默失效。
            assertTrue(!files.isEmpty(), "目录 " + dir + " 下一个 .java 都没扫到 —— 是被挪走了吗？"
                    + "那这条禁令已经变成空测试，请把 DECLARED_RISK_ONLY_DIRS 跟着改到新位置");

            for (Path file : files) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (isCommentLine(line)) {
                        continue;
                    }
                    if (line.contains(".getRisk()")) {
                        violations.add(file + ":" + (i + 1) + "  用了生效值 getRisk()  " + line.trim());
                    }
                    Matcher m = REQUIRE_CONFIRM_CALL.matcher(line);
                    while (m.find()) {
                        String receiver = m.group(1);
                        if (!DECLARED_RISK_RECEIVERS.contains(receiver)) {
                            violations.add(file + ":" + (i + 1) + "  接收者「" + receiver
                                    + "」不在声明值白名单里（若它是个 RiskLevel 局部变量，"
                                    + "请按 declared/declaredRisk 命名；若是 ToolDefinition，那就是违规）  "
                                    + line.trim());
                        } else {
                            assertReceiverComesFromDeclaredRisk(file, lines, i, receiver, violations);
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                """
                校验路径与 Planner prompt 装配路径只准读【声明】风险级 getDeclaredRisk()。

                生效值 getRisk()/ToolDefinition#isRequireConfirm() 由 agent_tool_override 一行数据决定，
                而那张表是可以在管理页在线改写的。用它做判定，等于把「某个不可逆工具要不要拦」
                这个安全结论交给了一行可被自助维护的数据 —— 这正是迭代三花了一整刀才拆掉的东西，
                也是方案 §3.1 第 5 条红线（🔴 校验路径必须读 getDeclaredRisk()，绝不读 getRisk()）。

                改回声明值即可。要展示「维护后现在是什么」，请去管理页 / 设计器名称目录 /
                诊断端点那几处，它们读生效值是【对的】，不在本禁令范围内。

                违规位置：
                """ + String.join("\n", violations));
    }

    /**
     * 反向自检：禁令之所以通过，必须是因为那两个目录<b>确实在读声明值</b>，
     * 而不是因为「要守的那段判定被整个删掉了」。
     *
     * <p>🔴 少了这一句，把 {@code WorkflowDslValidator} 的 V3/V11 与 Planner 的工具目录分桶
     * 一起删掉，上面那条禁令会<b>更绿</b> —— 判定没了，违规自然也没了。
     * 方案 §4.2 管这叫变异探针：一条禁令得同时钉住「不许这样」与「必须还在那儿」。
     */
    @Test
    @DisplayName("🔴 反向自检：那两条路径确实还在读 getDeclaredRisk() —— 防止删掉判定让禁令变绿")
    void testDeclaredRiskActuallyUsedOnGatePaths() throws IOException {
        for (String dir : DECLARED_RISK_ONLY_DIRS) {
            List<String> hits = new ArrayList<>();
            for (Path file : javaFilesUnder(SOURCE_ROOT.resolve(dir))) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (!isCommentLine(lines.get(i)) && lines.get(i).contains("getDeclaredRisk()")) {
                        hits.add(file + ":" + (i + 1));
                    }
                }
            }
            assertTrue(!hits.isEmpty(),
                    "目录 " + dir + " 里已经没有任何一处【非注释】的 getDeclaredRisk() 调用了。"
                            + "上面那条禁令现在通过，是因为要守的判定被删光了 —— "
                            + "请把它加回来，或（若确实整体废弃）连这条自检一起删掉并说明理由");
        }
    }

    /** 回到同一文件里找 receiver 的赋值行，验它取自声明风险级 */
    private void assertReceiverComesFromDeclaredRisk(Path file, List<String> lines, int useIndex,
                                                     String receiver, List<String> violations) {
        Pattern assignment = Pattern.compile("RiskLevel\\s+" + Pattern.quote(receiver) + "\\s*=");
        for (int i = useIndex; i >= 0; i--) {
            String line = lines.get(i);
            if (!assignment.matcher(line).find()) {
                continue;
            }
            // 赋值可能跨行（如 WorkflowDslValidator:2773-2774 的三元表达式），故连下两行一起看
            String rhs = String.join(" ", lines.subList(i, Math.min(lines.size(), i + 3)));
            if (!DECLARED_RISK_SOURCE.matcher(rhs).find()) {
                violations.add(file + ":" + (useIndex + 1) + "  接收者「" + receiver + "」虽在白名单里，"
                        + "但它的赋值行（:" + (i + 1) + "）不是取自 getDeclaredRisk()/declaredRiskOf："
                        + line.trim());
            }
            return;
        }
        violations.add(file + ":" + (useIndex + 1) + "  找不到接收者「" + receiver
                + "」在本文件里的 RiskLevel 赋值行，无法证明它来自声明值");
    }

    private List<Path> javaFilesUnder(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        }
    }

    /** 与 {@link #findViolations} 同一套口径：整行是注释就跳过（行尾注释不跳，宁可误报也别漏报） */
    private boolean isCommentLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }

}
