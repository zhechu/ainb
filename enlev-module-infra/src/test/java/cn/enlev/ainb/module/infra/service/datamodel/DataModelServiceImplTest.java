package cn.enlev.ainb.module.infra.service.datamodel;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.dal.dataobject.datamodel.DataModelDO;
import cn.enlev.ainb.module.infra.dal.mysql.datamodel.DataModelMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.DATA_MODEL_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

/**
 * 数据模型 Service 的单元测试
 *
 * <p>本类只钉一件事（台账 C5）：<b>「按 id 读」的四个方法，对同一个不存在的 id 必须给同一个答复。</b>
 *
 * <p>原先 {@code getDataModel} 是裸 {@code selectById}：查不到就返回 null，Controller 再包成
 * {@code success(null)}。而同文件另外三个方法都先过 {@code validateDataModelExists}，查不到就抛
 * {@code DATA_MODEL_NOT_EXISTS}。于是同一个 id、同一个 {@code infra:data-model:query} 权限，
 * 打 {@code /get} 得到「成功，数据是 null」，打 {@code /structure} 得到「数据模型不存在」——
 * <b>一个资源两种答复</b>。调用方（界面与 AI 工具）没法据 code 分支处理：
 * 「查不到」在一条路径上是业务错误、在另一条路径上是正常返回。
 *
 * <p>🔴 最后那条用例是本类的重点，它守的是<b>不变量</b>而不是单个方法：
 * 四个方法逐一断言同一个错误码。只断言 {@code getDataModel} 的话，
 * 将来有人给第五个「按 id 读」的方法又写一遍裸 {@code selectById}，测试照样全绿。
 *
 * <p>⚠️ 三个方法在 {@code validateDataModelExists} 之后才会碰 {@code JdbcTemplate}，
 * 所以「id 不存在」这条路径不需要真库、也不需要 mock jdbc —— 校验先抛出来了。
 * 这不是巧合而是顺序要求：存在性校验必须排在读真实表结构之前，
 * 否则不存在的 id 会先撞上一个语义完全不同的 {@code DATA_MODEL_TABLE_NOT_EXISTS}。
 *
 * @author 走进新时代
 */
public class DataModelServiceImplTest extends BaseMockitoUnitTest {

    @InjectMocks
    private DataModelServiceImpl dataModelService;

    @Mock
    private DataModelMapper dataModelMapper;

    /** 刻意用一个不可能存在的 id，避免与真实数据撞上而让断言失去意义 */
    private static final Long MISSING_ID = 999_999_999L;

    @Test
    @DisplayName("getDataModel：id 存在时原样返回 DO")
    public void testGetDataModelExists() {
        DataModelDO model = new DataModelDO();
        model.setId(1L);
        when(dataModelMapper.selectById(1L)).thenReturn(model);

        assertSame(model, dataModelService.getDataModel(1L));
    }

    @Test
    @DisplayName("getDataModel：id 不存在时抛「数据模型不存在」，而不是返回 null（C5）")
    public void testGetDataModelMissing() {
        when(dataModelMapper.selectById(MISSING_ID)).thenReturn(null);

        assertServiceException(() -> dataModelService.getDataModel(MISSING_ID), DATA_MODEL_NOT_EXISTS);
    }

    @Test
    @DisplayName("四个「按 id 读」的方法对同一个不存在的 id 给同一个错（C5 的不变量）")
    public void testAllIdReadersGiveSameAnswerForMissingId() {
        when(dataModelMapper.selectById(MISSING_ID)).thenReturn(null);

        assertServiceException(() -> dataModelService.getDataModel(MISSING_ID), DATA_MODEL_NOT_EXISTS);
        assertServiceException(() -> dataModelService.getDataModelColumns(MISSING_ID), DATA_MODEL_NOT_EXISTS);
        assertServiceException(() -> dataModelService.getDataModelStructure(MISSING_ID), DATA_MODEL_NOT_EXISTS);
        assertServiceException(() -> dataModelService.getTableDataPage(MISSING_ID, 1, 10),
                DATA_MODEL_NOT_EXISTS);
    }

}
