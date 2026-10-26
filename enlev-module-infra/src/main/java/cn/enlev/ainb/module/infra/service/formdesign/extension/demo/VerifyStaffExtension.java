package cn.enlev.ainb.module.infra.service.formdesign.extension.demo;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.framework.formdesign.core.extension.FormDesignExtension;
import cn.enlev.ainb.module.infra.framework.formdesign.core.extension.FormSubmitContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * 校验示例 7 的服务端扩展点：员工入职登记
 *
 * <h3>它演示什么</h3>
 *
 * 三层扩展机制的第二层之一。前端脚本管交互、沙箱里没有任何后端调用能力；
 * 凡是<b>需要后端参与</b>的逻辑都落在这里：
 *
 * <ul>
 *   <li><b>正则表达不了的校验</b> —— 身份证最后一位是加权和校验码，
 *       要做模 11 运算。内置正则库里的 ID_CARD 明说了只校验格式，
 *       「真正需要严格校验的场景应当在服务端扩展点里做」，这里把那句话兑现</li>
 *   <li><b>查库校验</b> —— 同一个身份证不得重复入职。前端拿不到全表数据，
 *       这条只能在服务端做</li>
 *   <li><b>补全字段</b> —— 工号按规则生成后写回。表单上它是只读的：
 *       让前端传工号，等于让调用方自选工号</li>
 *   <li><b>状态机约束</b> —— 已转正的员工不允许再改入职日期、也不允许删除</li>
 * </ul>
 *
 * <h3>⚠️ 表单必须切到「服务端扩展点」模式，否则本类一次都不会被调用</h3>
 *
 * 新建表单的默认模式是「前端脚本」。实现了接口却没切模式，钩子静默不执行、
 * 也没有任何报错 —— 启动时 {@code FormDesignExtensionRegistry} 会打 WARN，
 * 设计器里也会提示。本示例的构建脚本 {@code demo17_verify_extension.py}
 * 在建表单时就把 extensionMode 设成了 2。
 *
 * <h3>为什么用 addError 而不是抛异常</h3>
 *
 * 抛异常只能报出第一条问题，用户改完再提交又被第二条拦下。
 * 加进 errors 的会被汇总后一次性抛出，一次看到全部问题。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class VerifyStaffExtension implements FormDesignExtension {

    /**
     * 本扩展点作用的表单编码，与 infra_form_design.code 对应
     */
    public static final String FORM_CODE = "test_demo_verify_staff";

    /**
     * 业务表名
     *
     * 直接写表名而不是从表单配置里读：扩展点本来就是「为这张表单定制」的代码，
     * 读配置反而会让它看起来像通用逻辑，下一个人就会去复用它。
     */
    private static final String TABLE = "test_demo_verify_staff";

    /**
     * 身份证前 17 位的加权因子。这一串是国标 GB11643 定死的，不是随便取的
     */
    private static final int[] ID_CARD_WEIGHTS =
            {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /**
     * 加权和模 11 之后，对应的校验码。索引就是余数
     */
    private static final char[] ID_CARD_CHECK_CODES =
            {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    /**
     * 工号前缀
     */
    private static final String STAFF_NO_PREFIX = "EMP";

    /**
     * 默认试用期（月）
     */
    private static final int DEFAULT_PROBATION_MONTHS = 3;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public String formCode() {
        return FORM_CODE;
    }

    // ==================== 新增 ====================

    @Override
    public void beforeCreate(FormSubmitContext ctx) {
        String idCard = asText(ctx.get("id_card"));

        // 1. 校验码。格式由控件上的内置正则管，这里只补它做不到的那一步
        checkIdCardChecksum(ctx, idCard);

        // 2. 查库查重。⚠️ 这不能代替数据库唯一索引 —— 并发下两个请求可能都查到「不存在」。
        //    本示例的表刻意没建唯一索引，为的是让「扩展点到底有没有生效」看得出来；
        //    真实业务里两者都该有（理由见校验示例 4）
        if (StrUtil.isNotBlank(idCard) && countByIdCard(idCard, null) > 0) {
            ctx.addError(StrUtil.format("身份证号 {} 已存在入职记录，不能重复登记", mask(idCard)));
        }

        // 3. 补全字段。工号由服务端生成后写回 —— 表单上它是只读的
        if (StrUtil.isBlank(asText(ctx.get("staff_no")))) {
            ctx.set("staff_no", nextStaffNo());
        }
        if (ctx.get("probation_months") == null) {
            ctx.set("probation_months", DEFAULT_PROBATION_MONTHS);
        }

        // 4. 入职日期不得早于一年前 —— 这条其实能用跨字段规则表达（与常量比较），
        //    但常量是「今天」，声明式模型里没有「今天」这个右值，只能落到这里
        LocalDate entryDate = asDate(ctx.get("entry_date"));
        if (entryDate != null && entryDate.isBefore(LocalDate.now().minusYears(1))) {
            ctx.addError("入职日期不得早于一年前。补录历史员工请走数据导入");
        }
    }

    @Override
    public void afterCreate(FormSubmitContext ctx) {
        // ⚠️ after 钩子里抛异常会连带回滚整个提交事务，这通常不是想要的 ——
        // 发通知、写日志这类副作用必须自己兜住
        try {
            log.info("[afterCreate][员工入职登记完成 id={} 工号={} 姓名={}]",
                    ctx.getPrimaryKey(), ctx.get("staff_no"), ctx.get("staff_name"));
        } catch (Exception e) {
            log.error("[afterCreate][入职登记的后置副作用失败，已吞掉以免回滚主流程]", e);
        }
    }

    // ==================== 更新 ====================

    @Override
    public void beforeUpdate(FormSubmitContext ctx) {
        String idCard = asText(ctx.get("id_card"));
        checkIdCardChecksum(ctx, idCard);

        Object pk = ctx.getPrimaryKey();
        if (StrUtil.isNotBlank(idCard) && countByIdCard(idCard, pk) > 0) {
            ctx.addError(StrUtil.format("身份证号 {} 已被其他员工使用", mask(idCard)));
        }

        Map<String, Object> old = loadById(pk);
        if (old == null) {
            return;
        }

        // 状态机：已转正之后，入职日期就是既成事实，不能再改 ——
        // 它是工龄、年假、社保的计算基准
        boolean wasRegular = toBool(old.get("regular_flag"));
        if (wasRegular) {
            LocalDate oldEntry = asDate(old.get("entry_date"));
            LocalDate newEntry = asDate(ctx.get("entry_date"));
            if (!Objects.equals(oldEntry, newEntry)) {
                ctx.addError("该员工已转正，入职日期不允许修改");
            }
        }

        // 工号同理：它已经散落在各处（考勤、门禁、邮箱），改了对不上
        String oldNo = asText(old.get("staff_no"));
        String newNo = asText(ctx.get("staff_no"));
        if (StrUtil.isNotBlank(oldNo) && StrUtil.isNotBlank(newNo) && !oldNo.equals(newNo)) {
            ctx.addError("工号由系统生成，不允许修改");
        }
        if (StrUtil.isBlank(newNo)) {
            // 前端没传（字段只读时前端可能不提交），用库里的值补回去，
            // 否则这一列会被更新成空
            ctx.set("staff_no", oldNo);
        }
    }

    // ==================== 删除 ====================

    @Override
    public void beforeDelete(FormSubmitContext ctx) {
        Map<String, Object> old = loadById(ctx.getPrimaryKey());
        if (old != null && toBool(old.get("regular_flag"))) {
            ctx.addError("该员工已转正，不允许删除入职记录。离职请走离职流程");
        }
    }

    // ==================== 校验与查询 ====================

    /**
     * 身份证校验码：前 17 位加权求和，模 11 得到最后一位
     *
     * 这是正则表达不了的那一步 —— 正则只能判「18 位、日期段合法、末位是数字或 X」。
     */
    private void checkIdCardChecksum(FormSubmitContext ctx, String idCard) {
        if (StrUtil.isBlank(idCard)) {
            return; // 空值不校验；「必须填」由必填那条路径管
        }
        if (idCard.length() != 18) {
            // 长度不对是格式问题，控件上的内置正则已经报过了，这里不重复报
            return;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char c = idCard.charAt(i);
            if (c < '0' || c > '9') {
                return; // 同上，交给格式校验去报
            }
            sum += (c - '0') * ID_CARD_WEIGHTS[i];
        }
        char expected = ID_CARD_CHECK_CODES[sum % 11];
        char actual = Character.toUpperCase(idCard.charAt(17));
        if (expected != actual) {
            ctx.addError(StrUtil.format(
                    "身份证号校验码不正确（末位应为 {}）。格式对不代表号码真实存在", expected));
        }
    }

    /**
     * 按身份证统计已有记录
     *
     * @param excludePk 排除的主键。更新时要排掉自己，否则永远报重复
     */
    private long countByIdCard(String idCard, Object excludePk) {
        String sql = "SELECT COUNT(1) FROM " + TABLE + " WHERE id_card = ? AND deleted = 0";
        Long count;
        if (excludePk == null) {
            count = jdbcTemplate.queryForObject(sql, Long.class, idCard);
        } else {
            count = jdbcTemplate.queryForObject(sql + " AND id != ?", Long.class,
                    idCard, excludePk);
        }
        return count == null ? 0 : count;
    }

    /**
     * 生成下一个工号
     *
     * 取当前最大序号 +1。⚠️ 这在并发下会撞号 —— 真实业务应当用单据编号模板
     * （它有 Redis 号段，示例 1 与销售订单示例都用了）。这里手写是为了演示
     * 「扩展点可以补全字段」，不是在推荐这种发号方式。
     */
    private String nextStaffNo() {
        String sql = "SELECT MAX(CAST(SUBSTRING(staff_no, " + (STAFF_NO_PREFIX.length() + 1)
                + ") AS UNSIGNED)) FROM " + TABLE
                + " WHERE staff_no LIKE ? AND deleted = 0";
        Long max = jdbcTemplate.queryForObject(sql, Long.class, STAFF_NO_PREFIX + "%");
        long next = (max == null ? 0 : max) + 1;
        return String.format("%s%05d", STAFF_NO_PREFIX, next);
    }

    private Map<String, Object> loadById(Object pk) {
        if (pk == null) {
            return null;
        }
        String sql = "SELECT staff_no, entry_date, regular_flag FROM " + TABLE
                + " WHERE id = ? AND deleted = 0";
        return jdbcTemplate.queryForList(sql, pk).stream().findFirst().orElse(null);
    }

    // ==================== 取值辅助 ====================

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    /**
     * 取日期
     *
     * 提交上来的可能是字符串（前端送的），也可能是 java.sql.Date（库里读的），
     * 两种都要认 —— 只认一种的后果是「更新时永远判定入职日期变了」。
     */
    private LocalDate asDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof java.util.Date date) {
            return new java.sql.Date(date.getTime()).toLocalDate();
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            // 兼容 "2026-09-01" 与 "2026-09-01 00:00:00"
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (Exception e) {
            log.debug("[asDate][无法解析的日期值：{}]", text);
            return null;
        }
    }

    /**
     * 取布尔
     *
     * tinyint 读出来是 Integer/Boolean，前端送上来可能是 1 / "1" / true。
     */
    private boolean toBool(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(value).trim();
        return "1".equals(text) || "true".equalsIgnoreCase(text);
    }

    /**
     * 报错文案里的身份证要脱敏
     *
     * 错误消息会回到前端、也会进日志。把完整号码原样写进去，
     * 等于给了一个「拿重复提示反查号码」的口子。
     */
    private String mask(String idCard) {
        if (idCard == null || idCard.length() < 8) {
            return "***";
        }
        return idCard.substring(0, 3) + "***********" + idCard.substring(idCard.length() - 4);
    }

}
