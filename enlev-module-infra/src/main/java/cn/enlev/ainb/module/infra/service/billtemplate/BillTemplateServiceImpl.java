package cn.enlev.ainb.module.infra.service.billtemplate;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo.BillTemplateLogPageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo.BillTemplatePageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo.BillTemplatePreviewReqVO;
import cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo.BillTemplateSaveReqVO;
import cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo.BillTemplateTestRespVO;
import cn.enlev.ainb.module.infra.dal.dataobject.billtemplate.BillTemplateDO;
import cn.enlev.ainb.module.infra.dal.dataobject.billtemplate.BillTemplateLogDO;
import cn.enlev.ainb.module.infra.dal.mysql.billtemplate.BillTemplateLogMapper;
import cn.enlev.ainb.module.infra.dal.mysql.billtemplate.BillTemplateMapper;
import cn.enlev.ainb.module.infra.dal.redis.billtemplate.BillTemplateRedisDAO;
import cn.enlev.ainb.module.infra.enums.billtemplate.BillDateFormats;
import cn.enlev.ainb.module.infra.enums.billtemplate.BillGenTypeEnum;
import cn.enlev.ainb.module.infra.framework.billtemplate.core.BillNumberGenerator;
import cn.enlev.ainb.module.infra.framework.billtemplate.core.BillNumberSpec;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * 单据模板 Service 实现类
 *
 * <h3>职责边界</h3>
 * 本类只做编排：配置 CRUD、参数校验、取序号、拼号、回写统计。
 * 真正的拼号算法在 {@link BillNumberGenerator}（纯逻辑，可单测），
 * 序号自增在 {@link BillTemplateRedisDAO}。
 *
 * <h3>为什么出号方法不加事务</h3>
 * 出号是「取 Redis 序号 + 回写 DB 统计」两步。回写只是给管理员看的展示值，
 * 失败了不该把调用方的业务事务一起拖垮，因此吞掉异常只打日志。
 * 真正需要事务保证的是调用方（比如表单保存），出号会自动加入其事务。
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class BillTemplateServiceImpl implements BillTemplateService {

    /**
     * 随机数方式允许的位数
     */
    private static final List<Integer> RANDOM_DIGITS = List.of(16, 32);

    /**
     * 取号来源：业务出号（表单保存 / 跨模块 API）
     */
    private static final int SOURCE_GENERATE = 1;
    /**
     * 取号来源：表单预占
     */
    private static final int SOURCE_OCCUPY = 2;
    /**
     * 取号来源：管理页测试取号（真实模式）
     */
    private static final int SOURCE_TEST = 3;
    /**
     * 日志失败原因摘要的最大长度，与 error_msg 列宽一致
     */
    private static final int LOG_ERROR_MSG_MAX_LENGTH = 500;

    @Resource
    private BillTemplateMapper billTemplateMapper;
    @Resource
    private BillTemplateRedisDAO billTemplateRedisDAO;
    @Resource
    private BillTemplateLogMapper billTemplateLogMapper;
    /**
     * 事务管理器：取号日志要在独立事务里提交，见 {@link #saveGenerateLog}
     */
    @Resource
    private PlatformTransactionManager transactionManager;

    // ==================== 配置 CRUD ====================

    @Override
    public Long createBillTemplate(BillTemplateSaveReqVO reqVO) {
        validateConfig(reqVO.getGenType(), reqVO.getDateFormat(), reqVO.getDigit(),
                reqVO.getRandomDigit(), reqVO.getRandomType());
        validateCodeUnique(null, reqVO.getCode());

        BillTemplateDO entity = BeanUtils.toBean(reqVO, BillTemplateDO.class);
        applyDefaults(entity);
        entity.setExample(BillNumberGenerator.example(BillNumberSpec.of(entity)));
        entity.setCurrentNumber(0L);
        billTemplateMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public void updateBillTemplate(BillTemplateSaveReqVO reqVO) {
        validateConfig(reqVO.getGenType(), reqVO.getDateFormat(), reqVO.getDigit(),
                reqVO.getRandomDigit(), reqVO.getRandomType());
        BillTemplateDO exists = validateBillTemplateExists(reqVO.getId());
        validateCodeUnique(reqVO.getId(), reqVO.getCode());

        BillTemplateDO entity = BeanUtils.toBean(reqVO, BillTemplateDO.class);
        applyDefaults(entity);
        entity.setExample(BillNumberGenerator.example(BillNumberSpec.of(entity)));
        // 统计字段不接受前端传值：它们是出号的副产物，只能由 updateStatistics 写。
        // 让前端能改这几个字段，等于让前端能篡改流水进度
        entity.setCurrentNumber(exists.getCurrentNumber());
        entity.setLastNo(exists.getLastNo());
        entity.setLastPeriod(exists.getLastPeriod());
        billTemplateMapper.updateById(entity);
    }

    @Override
    public void deleteBillTemplate(Long id) {
        BillTemplateDO entity = validateBillTemplateExists(id);
        // 已出过号的模板不给删：号码已散落在业务数据里，删掉模板就无从追溯这批单号的规则
        if (StrUtil.isNotBlank(entity.getLastNo())) {
            throw exception(BILL_TEMPLATE_DELETE_FAIL_USED, entity.getName());
        }
        billTemplateMapper.deleteById(id);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        validateBillTemplateExists(id);
        BillTemplateDO update = new BillTemplateDO();
        update.setId(id);
        update.setStatus(status);
        billTemplateMapper.updateById(update);
    }

    @Override
    public BillTemplateDO getBillTemplate(Long id) {
        return billTemplateMapper.selectById(id);
    }

    @Override
    public BillTemplateDO getBillTemplateByCode(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        return billTemplateMapper.selectByCode(code);
    }

    @Override
    public List<BillTemplateDO> getBillTemplateListByCodes(Collection<String> codes) {
        if (CollUtil.isEmpty(codes)) {
            return Collections.emptyList();
        }
        return billTemplateMapper.selectListByCodes(codes);
    }

    @Override
    public PageResult<BillTemplateDO> getBillTemplatePage(BillTemplatePageReqVO reqVO) {
        return billTemplateMapper.selectPage(reqVO);
    }

    @Override
    public List<BillTemplateDO> getEnabledBillTemplateList() {
        return billTemplateMapper.selectList(BillTemplateDO::getStatus, CommonStatusEnum.ENABLE.getStatus());
    }

    // ==================== 号码生成 ====================

    @Override
    public String preview(BillTemplatePreviewReqVO reqVO) {
        validateConfig(reqVO.getGenType(), reqVO.getDateFormat(), reqVO.getDigit(),
                reqVO.getRandomDigit(), reqVO.getRandomType());
        BillTemplateDO entity = BeanUtils.toBean(reqVO, BillTemplateDO.class);
        applyDefaults(entity);
        // 试算不落库，也就没有编码可作计数作用域。BillNumberGenerator 只在
        // 流水位数溢出的告警日志里用到它，这里给个可辨识的占位值即可
        entity.setCode("__preview__");
        return BillNumberGenerator.example(BillNumberSpec.of(entity));
    }

    @Override
    public String generate(String code) {
        return generateInternal(validateEnabledByCode(code), SOURCE_GENERATE).no();
    }

    /**
     * 真实出号的公共路径：消耗流水 + 回写统计 + 记日志（按来源区分）
     */
    private GenerateResult generateInternal(BillTemplateDO rule, int source) {
        BillNumberSpec spec = BillNumberSpec.of(rule);
        GenerateResult result;
        try {
            result = doGenerate(spec, LocalDateTime.now());
        } catch (Exception e) {
            // 失败也要留痕：严格追溯场景恰恰最关心「尝试取号但失败」
            saveGenerateLog(rule, null, source, false, e.getMessage());
            throw e;
        }
        writeBackStatistics(rule, spec, result.no());
        saveGenerateLog(rule, result, source, true, null);
        return result;
    }

    @Override
    public BillTemplateTestRespVO testGenerate(Long id, boolean real) {
        BillTemplateDO rule = validateBillTemplateExists(id);
        if (!Objects.equals(rule.getStatus(), CommonStatusEnum.ENABLE.getStatus())) {
            throw exception(BILL_TEMPLATE_DISABLED, rule.getName());
        }
        BillTemplateTestRespVO respVO = new BillTemplateTestRespVO();
        respVO.setReal(real);
        if (real) {
            GenerateResult result = generateInternal(rule, SOURCE_TEST);
            respVO.setNo(result.no());
            respVO.setSeq(result.seq());
            respVO.setPeriod(result.period());
            return respVO;
        }
        // 模拟取号：只读干跑，不 INCR、不回写统计、不记日志 ——
        // 严格取号场景下测试绝不能烧号，否则正式单据会跳号
        BillNumberSpec spec = BillNumberSpec.of(rule);
        LocalDateTime now = LocalDateTime.now();
        String period = BillNumberGenerator.period(spec, now);
        Long seq = null;
        if (spec.needSequence()) {
            seq = billTemplateRedisDAO.currentSequence(
                    spec.getCounterScope(), BillNumberGenerator.groupKey(spec), period) + 1;
        }
        respVO.setNo(BillNumberGenerator.generate(spec, now, seq == null ? 1L : seq));
        respVO.setSeq(seq);
        respVO.setPeriod(period);
        return respVO;
    }

    @Override
    public String generateBySpec(BillNumberSpec spec) {
        return doGenerate(spec, LocalDateTime.now()).no();
    }

    /**
     * 一次真实出号的中间结果：号码 + 消耗的序号 + 周期标记，取号日志需要后两者
     */
    private record GenerateResult(String no, Long seq, String period) {
    }

    private GenerateResult doGenerate(BillNumberSpec spec, LocalDateTime now) {
        Long seq = null;
        if (spec.needSequence()) {
            // 只有时间流水方式才消耗计数。随机数与 UUID 每次独立生成，
            // 白白 INCR 一次会让「当前流水值」这个展示值失去意义
            seq = billTemplateRedisDAO.nextSequence(
                    spec.getCounterScope(),
                    BillNumberGenerator.groupKey(spec),
                    BillNumberGenerator.period(spec, now),
                    spec.ttl());
        }
        String no = BillNumberGenerator.generate(spec, now, seq == null ? 1L : seq);
        return new GenerateResult(no, seq, BillNumberGenerator.period(spec, now));
    }

    /**
     * 回写统计字段
     *
     * 纯展示用途，失败只打日志 —— 不能让一次展示值回写失败把调用方的业务事务拖垮。
     */
    private void writeBackStatistics(BillTemplateDO rule, BillNumberSpec spec, String no) {
        try {
            LocalDateTime now = LocalDateTime.now();
            String period = BillNumberGenerator.period(spec, now);
            long current = spec.needSequence()
                    ? billTemplateRedisDAO.currentSequence(spec.getCounterScope(), BillNumberGenerator.groupKey(spec), period)
                    : 0L;
            billTemplateMapper.updateStatistics(rule.getId(), current, no, period);
        } catch (Exception e) {
            log.warn("[writeBackStatistics][单据模板({}) 统计回写失败，不影响出号]", rule.getCode(), e);
        }
    }

    // ==================== 号码预占 ====================

    @Override
    public String occupy(BillNumberSpec spec) {
        return occupy(spec, null);
    }

    @Override
    public String occupyByCode(String code) {
        BillTemplateDO template = validateEnabledByCode(code);
        // 传入 DO：预占若真的取了号，同样要回写统计。少了这一步，
        // 「当前流水」永远停在 0，而 lastNo 为空还会让删除保护形同虚设 ——
        // 明明已经发出去的号，模板却还能被删掉
        return occupy(BillNumberSpec.of(template), template);
    }

    /**
     * 预占实现
     *
     * @param template 模板模式传对应的 DO，用于回写统计；内联模式没有模板行，传 null
     */
    private String occupy(BillNumberSpec spec, BillTemplateDO template) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        String scope = spec.getCounterScope();
        // 已有预占直接复用：用户反复打开同一张表单不该每次烧一个号。
        // 复用时不回写统计、不记日志 —— 这次并没有消耗计数
        String occupied = billTemplateRedisDAO.getOccupied(userId, scope);
        if (StrUtil.isNotBlank(occupied)) {
            return occupied;
        }
        GenerateResult result = doGenerate(spec, LocalDateTime.now());
        billTemplateRedisDAO.occupy(userId, scope, result.no());
        if (template != null) {
            writeBackStatistics(template, spec, result.no());
            saveGenerateLog(template, result, SOURCE_OCCUPY, true, null);
        }
        return result.no();
    }

    @Override
    public boolean releaseOccupied(String scope, String no) {
        return billTemplateRedisDAO.release(SecurityFrameworkUtils.getLoginUserId(), scope, no);
    }

    @Override
    public String getOccupied(String scope) {
        return billTemplateRedisDAO.getOccupied(SecurityFrameworkUtils.getLoginUserId(), scope);
    }

    // ==================== 取号日志 ====================

    @Override
    public PageResult<BillTemplateLogDO> getLogPage(BillTemplateLogPageReqVO reqVO) {
        return billTemplateLogMapper.selectPage(reqVO);
    }

    /**
     * 记录取号日志（模板开启 logEnabled 时）
     *
     * <h3>为什么必须独立事务</h3>
     * generate 会被调用方的业务事务包裹（比如表单保存）。业务回滚时号码并不归还
     * （跳号是有意取舍，见 {@link BillTemplateRedisDAO} 类注释），若日志随外层事务
     * 一起回滚，跳号就死无对证 —— 而「回滚导致的跳号」恰恰是最需要留痕的场景。
     *
     * <h3>为什么吞异常</h3>
     * 日志是审计副产品，写失败（比如极端情况下无租户上下文）不能影响取号主流程。
     */
    private void saveGenerateLog(BillTemplateDO template, GenerateResult result,
                                 int source, boolean success, String errorMsg) {
        if (!Boolean.TRUE.equals(template.getLogEnabled())) {
            return;
        }
        try {
            BillTemplateLogDO logDO = new BillTemplateLogDO();
            logDO.setTemplateId(template.getId());
            logDO.setTemplateCode(template.getCode());
            logDO.setBillNo(result != null ? result.no() : "");
            logDO.setGenType(template.getGenType());
            logDO.setPeriod(result != null ? result.period() : "");
            logDO.setSeq(result != null ? result.seq() : null);
            logDO.setSource(source);
            logDO.setSuccess(success);
            logDO.setErrorMsg(truncate(errorMsg, LOG_ERROR_MSG_MAX_LENGTH));
            logDO.setUserId(SecurityFrameworkUtils.getLoginUserId());
            TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
            requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            requiresNew.executeWithoutResult(status -> billTemplateLogMapper.insert(logDO));
        } catch (Exception ex) {
            log.error("[saveGenerateLog][单据模板({}) 记录取号日志失败]", template.getCode(), ex);
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength
                ? text.substring(0, maxLength) + "...(已截断)" : text;
    }

    // ==================== 校验与默认值 ====================

    private BillTemplateDO validateBillTemplateExists(Long id) {
        BillTemplateDO entity = billTemplateMapper.selectById(id);
        if (entity == null) {
            throw exception(BILL_TEMPLATE_NOT_EXISTS);
        }
        return entity;
    }

    /**
     * 按编码取模板，并要求它存在且已启用
     *
     * 出号与预占都走这里 —— 停用的模板不该还能继续吐号，否则「停用」形同虚设。
     */
    private BillTemplateDO validateEnabledByCode(String code) {
        BillTemplateDO rule = getBillTemplateByCode(code);
        if (rule == null) {
            throw exception(BILL_TEMPLATE_NOT_EXISTS);
        }
        if (!Objects.equals(rule.getStatus(), CommonStatusEnum.ENABLE.getStatus())) {
            throw exception(BILL_TEMPLATE_DISABLED, rule.getName());
        }
        return rule;
    }

    private void validateCodeUnique(Long id, String code) {
        BillTemplateDO exists = billTemplateMapper.selectByCode(code);
        if (exists == null || Objects.equals(exists.getId(), id)) {
            return;
        }
        throw exception(BILL_TEMPLATE_CODE_DUPLICATE, code);
    }

    /**
     * 跨字段校验
     *
     * 单字段的非空 / 范围交给 Bean Validation（见 {@link BillTemplateSaveReqVO} 的注解），
     * 这里只管「哪些字段在哪种生成方式下才必填」这类注解表达不了的规则。
     *
     * 参数逐个传而不是传 VO：保存与试算是两个不同的 VO（试算不要求名称与编码），
     * 但要校验的规则完全一样。
     */
    private void validateConfig(Integer genTypeValue, String dateFormat, Integer digit,
                                Integer randomDigit, Integer randomType) {
        BillGenTypeEnum genType = BillGenTypeEnum.typeOf(genTypeValue);
        if (genType == null) {
            throw exception(BILL_TEMPLATE_CONFIG_INVALID, StrUtil.format("不支持的生成方式({})", genTypeValue));
        }
        switch (genType) {
            case TIME:
                if (!BillDateFormats.isValid(dateFormat)) {
                    throw exception(BILL_TEMPLATE_CONFIG_INVALID,
                            StrUtil.format("日期格式({})不合法，可选值：{}", dateFormat, BillDateFormats.ALL));
                }
                if (digit == null) {
                    throw exception(BILL_TEMPLATE_CONFIG_INVALID, "时间流水方式必须填写流水位数");
                }
                break;
            case RANDOM:
                if (!RANDOM_DIGITS.contains(randomDigit)) {
                    throw exception(BILL_TEMPLATE_CONFIG_INVALID,
                            StrUtil.format("随机位数({})不合法，可选值：{}", randomDigit, RANDOM_DIGITS));
                }
                if (randomType != null && randomType != 1 && randomType != 2) {
                    throw exception(BILL_TEMPLATE_CONFIG_INVALID,
                            StrUtil.format("随机类型({})不合法，可选值：1-纯数字 2-字母+数字", randomType));
                }
                break;
            case UUID:
            default:
                break;
        }
    }

    /**
     * 补默认值
     *
     * 让前端可以只传自己关心的字段。默认值集中在这里而不是散落在 DDL 的 DEFAULT 里，
     * 是因为 preview 走的是尚未落库的对象，拿不到 DDL 默认值。
     */
    private void applyDefaults(BillTemplateDO entity) {
        entity.setPrefix(StrUtil.nullToEmpty(entity.getPrefix()));
        entity.setSuffix(StrUtil.nullToEmpty(entity.getSuffix()));
        if (StrUtil.isBlank(entity.getDateFormat())) {
            entity.setDateFormat(BillDateFormats.NONE);
        }
        if (StrUtil.isBlank(entity.getResetCycle())) {
            entity.setResetCycle("AUTO");
        }
        if (entity.getStartNumber() == null) {
            entity.setStartNumber(1);
        }
        if (entity.getStep() == null || entity.getStep() <= 0) {
            entity.setStep(1);
        }
        if (entity.getGroupByPrefix() == null) {
            entity.setGroupByPrefix(false);
        }
        if (entity.getRandomDigit() == null) {
            entity.setRandomDigit(16);
        }
        if (entity.getRandomType() == null) {
            entity.setRandomType(1);
        }
        if (entity.getLogEnabled() == null) {
            entity.setLogEnabled(false);
        }
        if (entity.getStatus() == null) {
            entity.setStatus(CommonStatusEnum.ENABLE.getStatus());
        }
        if (entity.getSort() == null) {
            entity.setSort(0);
        }
        if (ObjUtil.isNull(entity.getDigit()) || entity.getDigit() <= 0) {
            entity.setDigit(BillGenTypeEnum.TIME.getType().equals(entity.getGenType()) ? 4 : 0);
        }
    }

}
