package cn.enlev.ainb.module.system.framework.permission.core.scan;

import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import cn.enlev.ainb.module.system.framework.permission.core.scan.PermissionStringResolver.Resolution;
import cn.enlev.ainb.module.system.framework.permission.core.scan.PermissionStringResolver.Tier;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionStringResolver} 的单元测试类
 *
 * 用例大量取自阶段 0 勘察报告的实测数据，目的是把「勘察时量到的形态」钉成回归：
 * 将来谁改了动词映射表，这里会直接指出他动了哪一档、多少行数据会跟着变。
 *
 * @author 走进新时代
 */
public class PermissionStringResolverTest {

    private final PermissionStringResolver resolver = new PermissionStringResolver();

    // ========== 档 A：六个标准 CRUD 动词 ==========

    @Test
    public void testResolve_档A_六个标准动词() {
        Map<String, GrantActionEnum> expected = new LinkedHashMap<>();
        expected.put("system:user:query", GrantActionEnum.VIEW);
        expected.put("system:user:create", GrantActionEnum.CREATE);
        expected.put("system:user:update", GrantActionEnum.UPDATE);
        expected.put("system:user:delete", GrantActionEnum.DELETE);
        expected.put("system:user:export", GrantActionEnum.EXPORT);
        expected.put("system:user:import", GrantActionEnum.IMPORT);

        expected.forEach((permission, action) -> {
            Resolution r = resolver.resolve(permission);
            assertEquals("system:user", r.getResourceKey(), permission + " 的资源身份不对");
            assertEquals(action, r.getAction(), permission + " 的动作不对");
            assertEquals(Tier.STANDARD_VERB, r.getTier(), permission + " 的档位不对");
            assertTrue(r.isCollapsed(), permission + " 应该发生塌缩");
        });
    }

    // ========== 档 B：其余可映射动词 ==========

    @Test
    public void testResolve_档B_reference必须映射到REFERENCE() {
        // 🔴 回归：阶段 0 的第一版映射表漏了 reference，把 39 行误判进档 C。
        // 档 C 的处置是「保留为子资源」，等于白白放弃 39 行本可以直接归入动作维度的资源。
        // REFERENCE 是 GrantActionEnum 的正式成员（v9 §2 被引用查询），不是业务专有动词
        Resolution r = resolver.resolve("infra:form-design-run:assessment_object_form:reference");

        assertEquals("infra:form-design-run:assessment_object_form", r.getResourceKey());
        assertEquals(GrantActionEnum.REFERENCE, r.getAction());
        assertEquals(Tier.MAPPED_VERB, r.getTier());
        assertTrue(r.isCollapsed());
    }

    @Test
    public void testResolve_档B_其余动词() {
        Map<String, GrantActionEnum> expected = new LinkedHashMap<>();
        expected.put("system:user:list", GrantActionEnum.VIEW);
        expected.put("system:user:get", GrantActionEnum.VIEW);
        expected.put("infra:file:download", GrantActionEnum.EXPORT);
        expected.put("bpm:task:print", GrantActionEnum.PRINT);
        expected.put("system:user:save", GrantActionEnum.UPDATE);
        expected.put("system:user:update-status", GrantActionEnum.UPDATE);
        expected.put("system:menu:update-order", GrantActionEnum.UPDATE);

        expected.forEach((permission, action) -> {
            Resolution r = resolver.resolve(permission);
            assertEquals(action, r.getAction(), permission + " 的动作不对");
            assertEquals(Tier.MAPPED_VERB, r.getTier(), permission + " 的档位不对");
            assertTrue(r.isCollapsed(), permission + " 应该发生塌缩");
        });
    }

    // ========== 档 C：业务专有动词，保留为子资源 ==========

    @Test
    public void testResolve_档C_业务专有动词不塌缩() {
        // 阶段 0 实测 system 模块里的 9 个档 C 动词
        List<String> permissions = List.of(
                "system:user:update-password",
                "system:permission:assign-role-menu",
                "system:permission:assign-user-role",
                "system:permission:assign-role-data-scope",
                "system:mail-template:send-mail",
                "system:notify-template:send-notify",
                "system:sms-template:send-sms",
                "system:oauth2-token:page",
                "infra:portal:run");

        for (String permission : permissions) {
            Resolution r = resolver.resolve(permission);
            assertEquals(permission, r.getResourceKey(), permission + " 应整串作为资源身份保留");
            assertEquals(GrantActionEnum.VIEW, r.getAction(), permission + " 应沿用现状的 VIEW");
            assertEquals(Tier.BUSINESS_VERB, r.getTier(), permission + " 的档位不对");
            assertFalse(r.isCollapsed(), permission + " 不应塌缩");
        }
    }

    @Test
    public void testResolve_档C_在线表单查看明文必须不塌缩() {
        // 🔴 跨模块不变量：产生方在 infra（FormDesignServiceImpl#buildMenuConfig 的第 8 个动作
        //    与 FormDesignRuntimeController#getPlainField 的第二道门），判定方在本类。
        //    两边隔着模块边界，没有任何编译期约束能把它们绑在一起，只能靠这条测试。
        //
        // view-plain 刻意【不在】VERB_TO_ACTION 里。一旦有人把它「顺手规整」进去
        // （最像的写法是 map.put("view-plain", GrantActionEnum.VIEW)），
        // 它就会塌成 (infra:form-design-run:{code}, VIEW) —— 与 query 共用同一行资源、
        // 同一个动作 ⟹ 能查列表的人自动能看脱敏字段明文。
        //
        // 那正是这次拆分要消除的等式，而且失效是【静默】的：
        // 授权树里按钮还在、名字还叫「查看明文」，只是它已经不再单独判定任何东西。
        // 本仓库反复记录的那类「配了、看着生效了、就是不生效」，这就是一个。
        String permission = "infra:form-design-run:employee:view-plain";

        Resolution r = resolver.resolve(permission);

        assertEquals(permission, r.getResourceKey(), "应整串作为资源身份保留，不与 query 并成一行");
        assertEquals(Tier.BUSINESS_VERB, r.getTier());
        assertFalse(r.isCollapsed(), "塌缩 ⟹「能查列表」等于「能看明文」，拆分静默失效");
    }

    // ========== 档 D：两段式资源身份 ==========

    @Test
    public void testResolve_档D_两段式末段是名词() {
        // ⚠️ 末段不是动词 ⟹ 它是名词，整串本来就是资源身份，不是「资源:动作」。
        // 阶段 0 的第一版把 system:tenant / infra:codegen 当动词处理了
        for (String permission : List.of("system:tenant", "system:tenant-package",
                "infra:codegen", "bpm:model", "system:grant")) {
            Resolution r = resolver.resolve(permission);
            assertEquals(permission, r.getResourceKey(), permission + " 应整串作为资源身份");
            assertEquals(GrantActionEnum.VIEW, r.getAction());
            assertEquals(Tier.RESOURCE_IDENTITY, r.getTier(), permission + " 的档位不对");
            assertFalse(r.isCollapsed());
        }
    }

    // ========== 树顶层模块根 ==========

    @Test
    public void testResolve_单段串是模块根而非脏数据() {
        // 🔴 阶段 0 实测这 5 行是 type=MENU、menu_id 为空的树顶层节点，
        // 由 PermissionTreeDeriver 按约定建出来。第一版报告把它们标成「脏数据」，是错的
        for (String permission : List.of("system", "infra", "bpm", "ai", "agent")) {
            Resolution r = resolver.resolve(permission);
            assertEquals(permission, r.getResourceKey());
            assertEquals(GrantActionEnum.VIEW, r.getAction());
            assertEquals(Tier.MODULE_ROOT, r.getTier(), permission + " 的档位不对");
            assertFalse(r.isCollapsed());
        }
    }

    // ========== 空串 ==========

    @Test
    public void testResolve_空串的身份与动作都是null() {
        for (String permission : new String[]{null, "", "   "}) {
            Resolution r = resolver.resolve(permission);
            assertEquals(Tier.BLANK, r.getTier());
            // 🔴 刻意是 null 而不是空串/VIEW：忽略档位直接取用的调用方会立刻 NPE，
            // 而不是拿着空身份去查库、查不到、然后静默判成无权
            assertNull(r.getResourceKey(), "空串不该有资源身份");
            assertNull(r.getAction(), "空串不该有动作");
            assertFalse(r.isCollapsed());
        }
    }

    // ========== 🔴 阶段 0 第四节的静默收权案例 ==========

    @Test
    public void testResolve_systemUser家族塌缩后DENY必须能收窄() {
        // 阶段 0 实测：ROLE:9000000543（tenant 1）在 system:user:create/delete/export/import
        // 上有 4 条 DENY，action 全是 "*"。塌缩后这 4 个兄弟资源合并成同一个 system:user，
        // "*" 的作用域会从「一个动词」放大成「该资源的一切动作」，
        // 把原本放行的 VIEW（来自 query/list）与 UPDATE（来自 update）一起吞掉
        Set<String> denySources = Set.of("system:user:create", "system:user:delete",
                "system:user:export", "system:user:import");
        Set<GrantActionEnum> narrowed = new HashSet<>();
        for (String source : denySources) {
            Resolution r = resolver.resolve(source);
            assertEquals("system:user", r.getResourceKey(), "四条 DENY 应塌缩到同一个资源");
            assertTrue(r.isCollapsed());
            narrowed.add(r.getAction());
        }
        // 迁移规则：DENY 的 action="*" 收窄成来源串的动词所映射的动作
        assertEquals(Set.of(GrantActionEnum.CREATE, GrantActionEnum.DELETE,
                GrantActionEnum.EXPORT, GrantActionEnum.IMPORT), narrowed);

        // 收窄之后，原本放行的两个动作必须【不在】DENY 的动作集合里 —— 这就是「生效权限不变」的判据
        GrantActionEnum viewFromQuery = resolver.resolve("system:user:query").getAction();
        GrantActionEnum viewFromList = resolver.resolve("system:user:list").getAction();
        GrantActionEnum updateFromUpdate = resolver.resolve("system:user:update").getAction();
        assertFalse(narrowed.contains(viewFromQuery), "收窄后不该再吞掉 query 的 VIEW");
        assertFalse(narrowed.contains(viewFromList), "收窄后不该再吞掉 list 的 VIEW");
        assertFalse(narrowed.contains(updateFromUpdate), "收窄后不该再吞掉 update 的 UPDATE");

        // 而 update-password 是档 C，塌缩后仍是 system:user 的【子资源】，不参与这次合并
        Resolution updatePassword = resolver.resolve("system:user:update-password");
        assertFalse(updatePassword.isCollapsed());
        assertEquals("system:user:update-password", updatePassword.getResourceKey());
    }

    @Test
    public void testResolve_七个兄弟串塌缩到同一个资源身份() {
        // 阶段 0 §2 的碰撞组：7 个动词串解析出同一个资源身份
        //
        // ⚠️ 但这一族<b>最终没有塌缩</b>：它是 R11（存活后代继承泄漏）整族拦下的那 20 个之一 ——
        // 锚点 system:user(2061, DATASET) 下面还有 system:user:update-password 等存活后代，
        // 上提授权会顺着资源树继承给它们。落库后实测这 7 行仍 deleted=0、锚点仍 default_effect=ALLOW。
        // 解析器的答案与 R11 无关（R11 是计划器的取舍，不是字符串解析的一部分），
        // 所以本用例照旧成立；只是别把它读成「这一族已经并成一行了」。
        // 本次迁移真实的授权改写量是 1802 条里改写 1127 条（口径见自校验报告的 [计划] 那行）
        Set<String> family = Set.of("system:user:query", "system:user:list", "system:user:create",
                "system:user:update", "system:user:delete", "system:user:export", "system:user:import");
        long distinctKeys = family.stream()
                .map(p -> resolver.resolve(p).getResourceKey())
                .distinct().count();
        assertEquals(1, distinctKeys, "七个动词应塌缩成同一个资源身份");

        // 但动作必须两两可区分，否则塌缩就是把细粒度抹平 —— 那是提权不是重构
        long distinctActions = family.stream()
                .map(p -> resolver.resolve(p).getAction())
                .distinct().count();
        // query 与 list 都是 VIEW，所以 7 个串对应 6 个不同动作
        assertEquals(6, distinctActions, "query/list 同为 VIEW，其余五个各自独立");
    }

    // ========== 四段串与 trim ==========

    @Test
    public void testResolve_四段串只摘末段动词() {
        // 表单设计器运行时生成的资源，形态是 模块:子系统:数据集:动词
        Resolution r = resolver.resolve("infra:form-design-run:assessment_write:query");
        assertEquals("infra:form-design-run:assessment_write", r.getResourceKey());
        assertEquals(GrantActionEnum.VIEW, r.getAction());
        assertEquals(Tier.STANDARD_VERB, r.getTier());
    }

    @Test
    public void testResolve_首尾空白被trim() {
        Resolution r = resolver.resolve("  system:user:query  ");
        assertEquals("system:user", r.getResourceKey());
        assertEquals(GrantActionEnum.VIEW, r.getAction());
        assertEquals(Tier.STANDARD_VERB, r.getTier());
    }

    // ========== 映射表本身 ==========

    @Test
    public void testVerbs_映射表内容与阶段0实测一致() {
        Map<String, GrantActionEnum> expected = new LinkedHashMap<>();
        expected.put("query", GrantActionEnum.VIEW);
        expected.put("create", GrantActionEnum.CREATE);
        expected.put("update", GrantActionEnum.UPDATE);
        expected.put("delete", GrantActionEnum.DELETE);
        expected.put("export", GrantActionEnum.EXPORT);
        expected.put("import", GrantActionEnum.IMPORT);
        expected.put("list", GrantActionEnum.VIEW);
        expected.put("get", GrantActionEnum.VIEW);
        expected.put("download", GrantActionEnum.EXPORT);
        expected.put("print", GrantActionEnum.PRINT);
        expected.put("reference", GrantActionEnum.REFERENCE);
        expected.put("save", GrantActionEnum.UPDATE);
        expected.put("update-status", GrantActionEnum.UPDATE);
        expected.put("update-order", GrantActionEnum.UPDATE);

        assertEquals(expected, resolver.verbs(),
                "动词映射表被改动了。改之前先读阶段 0 勘察报告第三节："
                        + "档 A 499 行 / 档 B 44 行的规模是照这张表量出来的");
    }

    @Test
    public void testVerbs_全部落到既有枚举成员() {
        // 本次改造的一个关键卖点：69.5% 的遗留资源行能落进 GrantActionEnum 的【现有】成员，
        // 一个新枚举值都不用加。这条断言就是那个卖点的机器验证
        for (GrantActionEnum action : resolver.verbs().values()) {
            assertTrue(action != GrantActionEnum.WILDCARD,
                    "动词不该映射到通配符 —— 那等于放弃动作维度");
        }
        assertEquals(14, resolver.verbs().size());
    }

    @Test
    public void testVerbs_不可变() {
        // 映射表是全局约定，任何调用方都能拿到 verbs()，所以必须是只读的
        Map<String, GrantActionEnum> verbs = resolver.verbs();
        assertThrows(UnsupportedOperationException.class,
                () -> verbs.put("hack", GrantActionEnum.MANAGE));
    }

}
