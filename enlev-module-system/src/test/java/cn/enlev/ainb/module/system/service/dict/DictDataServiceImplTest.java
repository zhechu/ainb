package cn.enlev.ainb.module.system.service.dict;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.collection.ArrayUtils;
import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataExcelVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataImportRespVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataPageReqVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataSaveReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.dict.DictDataDO;
import cn.enlev.ainb.module.system.dal.dataobject.dict.DictTypeDO;
import cn.enlev.ainb.module.system.dal.mysql.dict.DictDataMapper;
import cn.enlev.ainb.module.system.dal.redis.dict.DictCacheRedisDAO;
import cn.enlev.ainb.module.system.dal.redis.dict.DictDataCache;
import cn.enlev.ainb.module.system.enums.dict.DictTypeNodeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static cn.enlev.ainb.framework.common.util.object.ObjectUtils.cloneIgnoreId;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertList;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertPojoEquals;
import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.framework.test.core.util.RandomUtils.*;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.*;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Import(DictDataServiceImpl.class)
public class DictDataServiceImplTest extends BaseDbUnitTest {

    @Resource
    private DictDataServiceImpl dictDataService;

    @Resource
    private DictDataMapper dictDataMapper;
    @MockitoBean
    private DictTypeService dictTypeService;
    @MockitoBean
    private DictCacheRedisDAO dictCacheRedisDAO;

    @Test
    public void testGetDictDataList() {
        // mock 数据
        DictDataDO dictDataDO01 = randomDictDataDO().setDictType("yunai").setSort(2)
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
        dictDataMapper.insert(dictDataDO01);
        DictDataDO dictDataDO02 = randomDictDataDO().setDictType("yunai").setSort(1)
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
        dictDataMapper.insert(dictDataDO02);
        DictDataDO dictDataDO03 = randomDictDataDO().setDictType("yunai").setSort(3)
                .setStatus(CommonStatusEnum.DISABLE.getStatus());
        dictDataMapper.insert(dictDataDO03);
        DictDataDO dictDataDO04 = randomDictDataDO().setDictType("yunai2").setSort(3)
                .setStatus(CommonStatusEnum.DISABLE.getStatus());
        dictDataMapper.insert(dictDataDO04);
        // 准备参数
        Integer status = CommonStatusEnum.ENABLE.getStatus();
        String dictType = "yunai";

        // 调用
        List<DictDataDO> dictDataDOList = dictDataService.getDictDataList(status, dictType);
        // 断言
        assertEquals(2, dictDataDOList.size());
        assertPojoEquals(dictDataDO02, dictDataDOList.get(0));
        assertPojoEquals(dictDataDO01, dictDataDOList.get(1));
    }

    @Test
    public void testGetDictDataPage() {
        // mock 数据
        DictDataDO dbDictData = randomPojo(DictDataDO.class, o -> { // 等会查询到
            o.setLabel("芋艿");
            o.setDictType("yunai");
            o.setStatus(CommonStatusEnum.ENABLE.getStatus());
            applyFlatTreeFields(o);
        });
        dictDataMapper.insert(dbDictData);
        // 测试 label 不匹配
        dictDataMapper.insert(cloneIgnoreId(dbDictData, o -> o.setLabel("艿")));
        // 测试 dictType 不匹配
        dictDataMapper.insert(cloneIgnoreId(dbDictData, o -> o.setDictType("nai")));
        // 测试 status 不匹配
        dictDataMapper.insert(cloneIgnoreId(dbDictData, o -> o.setStatus(CommonStatusEnum.DISABLE.getStatus())));
        // 准备参数
        DictDataPageReqVO reqVO = new DictDataPageReqVO();
        reqVO.setLabel("芋");
        reqVO.setDictType("yunai");
        reqVO.setStatus(CommonStatusEnum.ENABLE.getStatus());

        // 调用
        PageResult<DictDataDO> pageResult = dictDataService.getDictDataPage(reqVO);
        // 断言
        assertEquals(1, pageResult.getTotal());
        assertEquals(1, pageResult.getList().size());
        assertPojoEquals(dbDictData, pageResult.getList().get(0));
    }

    @Test
    public void testGetDictData() {
        // mock 数据
        DictDataDO dbDictData = randomDictDataDO();
        dictDataMapper.insert(dbDictData);
        // 准备参数
        Long id = dbDictData.getId();

        // 调用
        DictDataDO dictData = dictDataService.getDictData(id);
        // 断言
        assertPojoEquals(dbDictData, dictData);
    }

    @Test
    public void testCreateDictData_success() {
        // 准备参数
        DictDataSaveReqVO reqVO = randomPojo(DictDataSaveReqVO.class,
                o -> o.setStatus(randomCommonStatus()))
                .setId(null) // 防止 id 被赋值
                .setParentId(DictDataDO.PARENT_ID_ROOT); // 非树形用例统一挂在根下
        // mock 方法
        when(dictTypeService.getDictType(eq(reqVO.getDictType()))).thenReturn(randomDictTypeDO(reqVO.getDictType()));

        // 调用
        Long dictDataId = dictDataService.createDictData(reqVO);
        // 断言
        assertNotNull(dictDataId);
        // 校验记录的属性是否正确
        DictDataDO dictData = dictDataMapper.selectById(dictDataId);
        assertPojoEquals(reqVO, dictData, "id");
    }

    @Test
    public void testUpdateDictData_success() {
        // mock 数据
        DictDataDO dbDictData = randomDictDataDO();
        dictDataMapper.insert(dbDictData);// @Sql: 先插入出一条存在的数据
        // 准备参数
        DictDataSaveReqVO reqVO = randomPojo(DictDataSaveReqVO.class, o -> {
            o.setId(dbDictData.getId()); // 设置更新的 ID
            o.setStatus(randomCommonStatus());
            o.setParentId(DictDataDO.PARENT_ID_ROOT); // 非树形用例统一挂在根下
        });
        // mock 方法，字典类型
        when(dictTypeService.getDictType(eq(reqVO.getDictType()))).thenReturn(randomDictTypeDO(reqVO.getDictType()));

        // 调用
        dictDataService.updateDictData(reqVO);
        // 校验是否更新正确
        DictDataDO dictData = dictDataMapper.selectById(reqVO.getId()); // 获取最新的
        assertPojoEquals(reqVO, dictData);
    }

    @Test
    public void testDeleteDictData_success() {
        // mock 数据
        DictDataDO dbDictData = randomDictDataDO();
        dictDataMapper.insert(dbDictData);// @Sql: 先插入出一条存在的数据
        // 准备参数
        Long id = dbDictData.getId();

        // 调用
        dictDataService.deleteDictData(id, false);
        // 校验数据不存在了
        assertNull(dictDataMapper.selectById(id));
    }

    @Test
    public void testValidateDictDataExists_success() {
        // mock 数据
        DictDataDO dbDictData = randomDictDataDO();
        dictDataMapper.insert(dbDictData);// @Sql: 先插入出一条存在的数据

        // 调用成功
        dictDataService.validateDictDataExists(dbDictData.getId());
    }

    @Test
    public void testValidateDictDataExists_notExists() {
        assertServiceException(() -> dictDataService.validateDictDataExists(randomLongId()), DICT_DATA_NOT_EXISTS);
    }

    @Test
    public void testValidateDictTypeExists_success() {
        // mock 方法，数据类型被禁用
        String type = randomString();
        when(dictTypeService.getDictType(eq(type))).thenReturn(randomDictTypeDO(type));

        // 调用, 成功
        dictDataService.validateDictTypeExists(type);
    }

    @Test
    public void testValidateDictTypeExists_notExists() {
        assertServiceException(() -> dictDataService.validateDictTypeExists(randomString()), DICT_TYPE_NOT_EXISTS);
    }

    @Test
    public void testValidateDictTypeExists_notEnable() {
        // mock 方法，数据类型被禁用
        String dictType = randomString();
        when(dictTypeService.getDictType(eq(dictType))).thenReturn(
                randomPojo(DictTypeDO.class, o -> o.setStatus(CommonStatusEnum.DISABLE.getStatus())));

        // 调用, 并断言异常
        assertServiceException(() -> dictDataService.validateDictTypeExists(dictType), DICT_TYPE_NOT_ENABLE);
    }

    @Test
    public void testValidateDictDataValueUnique_success() {
        // 调用，成功
        dictDataService.validateDictDataValueUnique(randomLongId(), randomString(), randomString());
    }

    @Test
    public void testValidateDictDataValueUnique_valueDuplicateForCreate() {
        // 准备参数
        String dictType = randomString();
        String value = randomString();
        // mock 数据
        dictDataMapper.insert(randomDictDataDO(o -> {
            o.setDictType(dictType);
            o.setValue(value);
        }));

        // 调用，校验异常
        assertServiceException(() -> dictDataService.validateDictDataValueUnique(null, dictType, value),
                DICT_DATA_VALUE_DUPLICATE);
    }

    @Test
    public void testValidateDictDataValueUnique_valueDuplicateForUpdate() {
        // 准备参数
        Long id = randomLongId();
        String dictType = randomString();
        String value = randomString();
        // mock 数据
        dictDataMapper.insert(randomDictDataDO(o -> {
            o.setDictType(dictType);
            o.setValue(value);
        }));

        // 调用，校验异常
        assertServiceException(() -> dictDataService.validateDictDataValueUnique(id, dictType, value),
                DICT_DATA_VALUE_DUPLICATE);
    }

    @Test
    public void testGetDictDataCountByDictType() {
        // mock 数据
        dictDataMapper.insert(randomDictDataDO(o -> o.setDictType("yunai")));
        dictDataMapper.insert(randomDictDataDO(o -> o.setDictType("tudou")));
        dictDataMapper.insert(randomDictDataDO(o -> o.setDictType("yunai")));
        // 准备参数
        String dictType = "yunai";

        // 调用
        long count = dictDataService.getDictDataCountByDictType(dictType);
        // 校验
        assertEquals(2L, count);
    }

    @Test
    public void testValidateDictDataList_success() {
        // mock 数据
        DictDataDO dictDataDO = randomDictDataDO().setStatus(CommonStatusEnum.ENABLE.getStatus());
        dictDataMapper.insert(dictDataDO);
        // 准备参数
        String dictType = dictDataDO.getDictType();
        List<String> values = singletonList(dictDataDO.getValue());

        // 调用，无需断言
        dictDataService.validateDictDataList(dictType, values);
    }

    @Test
    public void testValidateDictDataList_notFound() {
        // 准备参数
        String dictType = randomString();
        List<String> values = singletonList(randomString());

        // 调用, 并断言异常
        assertServiceException(() -> dictDataService.validateDictDataList(dictType, values), DICT_DATA_NOT_EXISTS);
    }

    @Test
    public void testValidateDictDataList_notEnable() {
        // mock 数据
        DictDataDO dictDataDO = randomDictDataDO().setStatus(CommonStatusEnum.DISABLE.getStatus());
        dictDataMapper.insert(dictDataDO);
        // 准备参数
        String dictType = dictDataDO.getDictType();
        List<String> values = singletonList(dictDataDO.getValue());

        // 调用, 并断言异常
        assertServiceException(() -> dictDataService.validateDictDataList(dictType, values),
                DICT_DATA_NOT_ENABLE, dictDataDO.getLabel());
    }

    @Test
    public void testGetDictData_dictType() {
        // mock 数据
        DictDataDO dictDataDO = randomDictDataDO().setDictType("yunai").setValue("1");
        dictDataMapper.insert(dictDataDO);
        DictDataDO dictDataDO02 = randomDictDataDO().setDictType("yunai").setValue("2");
        dictDataMapper.insert(dictDataDO02);
        // 准备参数
        String dictType = "yunai";
        String value = "1";

        // 调用
        DictDataDO dbDictData = dictDataService.getDictData(dictType, value);
        // 断言
        assertEquals(dictDataDO, dbDictData);
    }

    @Test
    public void testParseDictData() {
        // mock 数据
        DictDataDO dictDataDO = randomDictDataDO().setDictType("yunai").setLabel("1");
        dictDataMapper.insert(dictDataDO);
        DictDataDO dictDataDO02 = randomDictDataDO().setDictType("yunai").setLabel("2");
        dictDataMapper.insert(dictDataDO02);
        // 准备参数
        String dictType = "yunai";
        String label = "1";

        // 调用
        DictDataDO dbDictData = dictDataService.parseDictData(dictType, label);
        // 断言
        assertEquals(dictDataDO, dbDictData);
    }

    // ========== 树形 ==========

    /**
     * 新建子节点时，物化路径与层级要基于父节点续上
     */
    @Test
    public void testCreateDictData_child() {
        mockTreeDictType("area");
        Long rootId = createNode("area", DictDataDO.PARENT_ID_ROOT, "广东省", "gd");

        Long childId = createNode("area", rootId, "深圳市", "sz");

        DictDataDO child = dictDataMapper.selectById(childId);
        assertEquals(rootId, child.getParentId());
        assertEquals(2, child.getTreeLevel());
        assertEquals("," + rootId + "," + childId + ",", child.getIdPath());
    }

    /**
     * 平铺字典不允许挂父节点
     */
    @Test
    public void testCreateDictData_parentOnFlatDict() {
        DictTypeDO flatType = randomDictTypeDO("sex"); // randomDictTypeDO 默认 treeEnable = 0
        when(dictTypeService.getDictType(eq("sex"))).thenReturn(flatType);
        Long rootId = createNode("sex", DictDataDO.PARENT_ID_ROOT, "男", "male");

        assertServiceException(() -> createNode("sex", rootId, "少年", "boy"),
                DICT_DATA_TREE_NOT_ENABLE);
    }

    /**
     * 移动节点时，整棵子树的路径与层级都要跟着重写
     */
    @Test
    public void testMoveDictData_rewritesSubTree() {
        mockTreeDictType("area");
        //   广东省
        //     └ 深圳市
        //         └ 南山区
        //   湖南省
        Long gd = createNode("area", DictDataDO.PARENT_ID_ROOT, "广东省", "gd");
        Long sz = createNode("area", gd, "深圳市", "sz");
        Long ns = createNode("area", sz, "南山区", "ns");
        Long hn = createNode("area", DictDataDO.PARENT_ID_ROOT, "湖南省", "hn");

        // 把深圳市（连同南山区）挪到湖南省下
        dictDataService.moveDictData(sz, hn, 1);

        DictDataDO szAfter = dictDataMapper.selectById(sz);
        assertEquals(hn, szAfter.getParentId());
        assertEquals(2, szAfter.getTreeLevel());
        assertEquals("," + hn + "," + sz + ",", szAfter.getIdPath());
        // 子孙也要一起改，否则整棵子树的路径就和实际父子关系对不上了
        DictDataDO nsAfter = dictDataMapper.selectById(ns);
        assertEquals(3, nsAfter.getTreeLevel());
        assertEquals("," + hn + "," + sz + "," + ns + ",", nsAfter.getIdPath());
    }

    /**
     * 不能把节点挂到自己的子孙下面，否则这棵子树会从树上脱落成一个环
     */
    @Test
    public void testMoveDictData_cycle() {
        mockTreeDictType("area");
        Long gd = createNode("area", DictDataDO.PARENT_ID_ROOT, "广东省", "gd");
        Long sz = createNode("area", gd, "深圳市", "sz");
        Long ns = createNode("area", sz, "南山区", "ns");

        assertServiceException(() -> dictDataService.moveDictData(gd, ns, 1),
                DICT_DATA_PARENT_IS_CHILD);
        assertServiceException(() -> dictDataService.moveDictData(gd, gd, 1),
                DICT_DATA_PARENT_IS_SELF);
    }

    /**
     * 移动不会误伤 id 前缀相同的兄弟节点
     *
     * 路径首尾都带逗号就是为了这个：如果写成 "12,45" 而不是 ",12,45,"，
     * LIKE '1%' 会把 id=1 和 id=15 的子树一起改掉
     */
    @Test
    public void testMoveDictData_noPrefixCollision() {
        mockTreeDictType("area");
        Long a = createNode("area", DictDataDO.PARENT_ID_ROOT, "A", "a");
        Long b = createNode("area", DictDataDO.PARENT_ID_ROOT, "B", "b");
        Long childOfB = createNode("area", b, "B-1", "b1");
        String pathBefore = dictDataMapper.selectById(childOfB).getIdPath();

        // 移动 A，B 的子树不应受影响
        dictDataService.moveDictData(a, b, 1);

        assertEquals(pathBefore, dictDataMapper.selectById(childOfB).getIdPath());
    }

    /**
     * 默认不允许删除还有子节点的节点；开启级联后连同整棵子树一起删
     */
    @Test
    public void testDeleteDictData_children() {
        mockTreeDictType("area");
        Long gd = createNode("area", DictDataDO.PARENT_ID_ROOT, "广东省", "gd");
        Long sz = createNode("area", gd, "深圳市", "sz");
        Long ns = createNode("area", sz, "南山区", "ns");

        assertServiceException(() -> dictDataService.deleteDictData(gd, false),
                DICT_DATA_HAS_CHILDREN);

        dictDataService.deleteDictData(gd, true);
        assertNull(dictDataMapper.selectById(gd));
        assertNull(dictDataMapper.selectById(sz));
        assertNull(dictDataMapper.selectById(ns));
    }

    /**
     * 关键字过滤要把命中项的祖先一并带出来，否则树在界面上会断掉
     */
    @Test
    public void testGetDictDataTreeList_keywordKeepsAncestors() {
        mockTreeDictType("area");
        Long gd = createNode("area", DictDataDO.PARENT_ID_ROOT, "广东省", "gd");
        Long sz = createNode("area", gd, "深圳市", "sz");
        Long ns = createNode("area", sz, "南山区", "ns");
        createNode("area", DictDataDO.PARENT_ID_ROOT, "湖南省", "hn"); // 无关分支，不该出现

        List<DictDataDO> result = dictDataService.getDictDataTreeList("area", "南山");

        // 命中的是南山区，但广东省、深圳市作为祖先必须一起返回
        assertEquals(3, result.size());
        Set<Long> ids = convertSet(result, DictDataDO::getId);
        assertTrue(ids.containsAll(List.of(gd, sz, ns)));
    }

    /**
     * 拖拽排序要真正生效
     *
     * 同级节点的 sort 默认都是 0，如果只把被拖动节点的 sort 设成目标下标，
     * 它仍与其它节点并列，顺序退化成按 id 排——用户拖完看不到任何变化。
     * 移动时必须把同级整体重新编号。
     */
    @Test
    public void testMoveDictData_resortsSiblings() {
        mockTreeDictType("area");
        // 三个同级节点，sort 全是 0（新建时的默认值）
        Long a = createNode("area", DictDataDO.PARENT_ID_ROOT, "A", "a");
        Long b = createNode("area", DictDataDO.PARENT_ID_ROOT, "B", "b");
        Long c = createNode("area", DictDataDO.PARENT_ID_ROOT, "C", "c");

        // 把 C 拖到最前面
        dictDataService.moveDictData(c, DictDataDO.PARENT_ID_ROOT, 0);

        List<DictDataDO> roots = dictDataService.getDictDataTreeList("area", null);
        assertEquals(List.of(c, a, b), convertList(roots, DictDataDO::getId));
        // 同级 sort 两两不同，否则下次排序又会退化成按 id
        List<Integer> sorts = convertList(roots, DictDataDO::getSort);
        assertEquals(List.of(0, 1, 2), sorts);
    }

    /**
     * 跨父级移动时，目标父级下的同级也要重新编号
     */
    @Test
    public void testMoveDictData_resortsAcrossParents() {
        mockTreeDictType("area");
        Long p1 = createNode("area", DictDataDO.PARENT_ID_ROOT, "P1", "p1");
        Long p2 = createNode("area", DictDataDO.PARENT_ID_ROOT, "P2", "p2");
        Long x = createNode("area", p1, "X", "x");
        Long y = createNode("area", p2, "Y", "y");
        Long z = createNode("area", p2, "Z", "z");

        // 把 X 从 P1 挪到 P2 的中间
        dictDataService.moveDictData(x, p2, 1);

        List<DictDataDO> children = dictDataService.getDictDataChildren("area", p2);
        assertEquals(List.of(y, x, z), convertList(children, DictDataDO::getId));
        assertEquals(List.of(0, 1, 2), convertList(children, DictDataDO::getSort));
    }

    /**
     * 建节点失败时不能留下半棵树
     *
     * 新建是「insert + 回填 idPath」两条写入，只插入而没补上 idPath 的节点，
     * 会让后续所有子树操作（取子孙、防环、移动）都失准。
     */
    @Test
    public void testCreateDictData_idPathAlwaysFilled() {
        mockTreeDictType("area");
        Long root = createNode("area", DictDataDO.PARENT_ID_ROOT, "根", "root");
        Long child = createNode("area", root, "子", "child");

        assertEquals("," + root + ",", dictDataMapper.selectById(root).getIdPath());
        assertEquals("," + root + "," + child + ",", dictDataMapper.selectById(child).getIdPath());
    }

    // ========== 导入导出 ==========

    private DictDataExcelVO excelRow(String label, String value, String parentValue) {
        return DictDataExcelVO.builder().label(label).value(value).parentValue(parentValue)
                .sort(0).status(CommonStatusEnum.ENABLE.getStatus()).build();
    }

    /**
     * 导出的父子关系用「上级键值」表达，且父节点排在子节点前面
     *
     * 用编号表达父子的话，导出的文件换个环境导入就全乱了——自增主键跨环境不一致。
     */
    @Test
    public void testExportDictDataList() {
        mockTreeDictType("area");
        Long gd = createNode("area", DictDataDO.PARENT_ID_ROOT, "广东省", "gd");
        Long sz = createNode("area", gd, "深圳市", "sz");
        createNode("area", sz, "南山区", "ns");
        createNode("area", DictDataDO.PARENT_ID_ROOT, "湖南省", "hn");

        List<DictDataExcelVO> rows = dictDataService.exportDictDataList("area");

        assertEquals(List.of("gd", "sz", "ns", "hn"), convertList(rows, DictDataExcelVO::getValue));
        assertEquals(List.of("广东省", "深圳市", "南山区", "湖南省"),
                convertList(rows, DictDataExcelVO::getLabel));
        assertNull(rows.get(0).getParentValue()); // 顶层节点的上级为空
        assertEquals("gd", rows.get(1).getParentValue());
        assertEquals("sz", rows.get(2).getParentValue());
    }

    /**
     * 导入时子行排在父行前面也要能正确落库
     *
     * Excel 的行序完全由用户决定，指望父行一定在前面是不成立的。
     */
    @Test
    public void testImportDictDataList_childBeforeParent() {
        mockTreeDictType("area");

        DictDataImportRespVO resp = dictDataService.importDictDataList("area", List.of(
                excelRow("南山区", "ns", "sz"),   // 孙，排最前
                excelRow("深圳市", "sz", "gd"),   // 子
                excelRow("广东省", "gd", null)    // 父，排最后
        ), false);

        assertTrue(resp.getFailureValues().isEmpty(), resp.getFailureValues().toString());
        assertEquals(3, resp.getCreateValues().size());
        Map<String, DictDataDO> byValue = convertMap(
                dictDataService.getDictDataTreeList("area", null), DictDataDO::getValue);
        assertEquals(byValue.get("gd").getId(), byValue.get("sz").getParentId());
        assertEquals(byValue.get("sz").getId(), byValue.get("ns").getParentId());
        assertEquals(3, byValue.get("ns").getTreeLevel());
        // 路径必须完整，否则后续取子孙、防环、移动都会失准
        assertEquals("," + byValue.get("gd").getId() + "," + byValue.get("sz").getId()
                + "," + byValue.get("ns").getId() + ",", byValue.get("ns").getIdPath());
    }

    /**
     * 互相引用成环的行不能被静默挂到根上
     *
     * 悄悄改成顶层节点，层级关系就被无声地改错了，比直接报错难查得多。
     */
    @Test
    public void testImportDictDataList_cycle() {
        mockTreeDictType("area");

        DictDataImportRespVO resp = dictDataService.importDictDataList("area", List.of(
                excelRow("甲", "a", "b"),
                excelRow("乙", "b", "a"),
                excelRow("正常", "ok", null)
        ), false);

        assertEquals(List.of("ok"), resp.getCreateValues());
        assertEquals(Set.of("a", "b"), resp.getFailureValues().keySet());
        assertTrue(resp.getFailureValues().get("a").contains("成环"),
                resp.getFailureValues().get("a"));
    }

    /** 上级键值在文件和库里都找不到时，报错而不是挂到根上 */
    @Test
    public void testImportDictDataList_parentNotFound() {
        mockTreeDictType("area");

        DictDataImportRespVO resp = dictDataService.importDictDataList("area", List.of(
                excelRow("孤儿", "orphan", "not_exist")
        ), false);

        assertTrue(resp.getCreateValues().isEmpty());
        assertEquals(Set.of("orphan"), resp.getFailureValues().keySet());
    }

    /** 上级可以是库里已有的节点，不必出现在同一个文件里 */
    @Test
    public void testImportDictDataList_parentFromDb() {
        mockTreeDictType("area");
        Long gd = createNode("area", DictDataDO.PARENT_ID_ROOT, "广东省", "gd");

        DictDataImportRespVO resp = dictDataService.importDictDataList("area", List.of(
                excelRow("深圳市", "sz", "gd")
        ), false);

        assertTrue(resp.getFailureValues().isEmpty(), resp.getFailureValues().toString());
        DictDataDO sz = convertMap(dictDataService.getDictDataTreeList("area", null),
                DictDataDO::getValue).get("sz");
        assertEquals(gd, sz.getParentId());
        assertEquals(2, sz.getTreeLevel());
    }

    /** 键值已存在时，默认跳过；开启更新才覆盖 */
    @Test
    public void testImportDictDataList_updateSupport() {
        mockTreeDictType("area");
        createNode("area", DictDataDO.PARENT_ID_ROOT, "广东省", "gd");

        DictDataImportRespVO skipped = dictDataService.importDictDataList("area", List.of(
                excelRow("广东", "gd", null)
        ), false);
        assertEquals(Set.of("gd"), skipped.getFailureValues().keySet());

        DictDataImportRespVO updated = dictDataService.importDictDataList("area", List.of(
                excelRow("广东", "gd", null)
        ), true);
        assertEquals(List.of("gd"), updated.getUpdateValues());
        assertEquals("广东", convertMap(dictDataService.getDictDataTreeList("area", null),
                DictDataDO::getValue).get("gd").getLabel());
    }

    /** 同一个文件里重复的键值，无法判断以哪行为准，拒绝后一行 */
    @Test
    public void testImportDictDataList_duplicatedInFile() {
        mockTreeDictType("area");

        DictDataImportRespVO resp = dictDataService.importDictDataList("area", List.of(
                excelRow("甲", "a", null),
                excelRow("甲重复", "a", null)
        ), false);

        assertEquals(List.of("a"), resp.getCreateValues());
        assertEquals(Set.of("a"), resp.getFailureValues().keySet());
    }

    /** 平铺字典的导入文件不允许带上级键值 */
    @Test
    public void testImportDictDataList_parentOnFlatDict() {
        DictTypeDO flat = randomDictTypeDO("sex"); // randomDictTypeDO 默认 treeEnable = 0
        when(dictTypeService.getDictType(eq("sex"))).thenReturn(flat);

        DictDataImportRespVO resp = dictDataService.importDictDataList("sex", List.of(
                excelRow("男", "male", null),
                excelRow("少年", "boy", "male")
        ), false);

        assertEquals(List.of("male"), resp.getCreateValues());
        assertEquals(Set.of("boy"), resp.getFailureValues().keySet());
    }

    /** 标签或键值为空的行直接拒绝，不能落一条半截数据进去 */
    @Test
    public void testImportDictDataList_blankRequired() {
        mockTreeDictType("area");

        DictDataImportRespVO resp = dictDataService.importDictDataList("area", List.of(
                excelRow("", "a", null),
                excelRow("乙", "", null)
        ), false);

        assertTrue(resp.getCreateValues().isEmpty());
        assertEquals(2, resp.getFailureValues().size());
    }

    /** 导出再导入必须闭环：同一份数据往返一次，层级和标签都不变 */
    @Test
    public void testImportDictDataList_roundTrip() {
        mockTreeDictType("area");
        Long gd = createNode("area", DictDataDO.PARENT_ID_ROOT, "广东省", "gd");
        Long sz = createNode("area", gd, "深圳市", "sz");
        createNode("area", sz, "南山区", "ns");
        List<DictDataExcelVO> exported = dictDataService.exportDictDataList("area");

        // 清空后用导出的内容原样导回
        dictDataService.deleteDictData(gd, true);
        assertTrue(dictDataService.getDictDataTreeList("area", null).isEmpty());
        DictDataImportRespVO resp = dictDataService.importDictDataList("area", exported, false);

        assertTrue(resp.getFailureValues().isEmpty(), resp.getFailureValues().toString());
        List<DictDataExcelVO> again = dictDataService.exportDictDataList("area");
        assertEquals(convertList(exported, DictDataExcelVO::getValue),
                convertList(again, DictDataExcelVO::getValue));
        assertEquals(convertList(exported, DictDataExcelVO::getParentValue),
                convertList(again, DictDataExcelVO::getParentValue));
        assertEquals(convertList(exported, DictDataExcelVO::getLabel),
                convertList(again, DictDataExcelVO::getLabel));
    }

    // ========== 树形用例的辅助方法 ==========

    /** 把某个字典类型 mock 成「已启用的树形字典」 */
    private void mockTreeDictType(String type) {
        when(dictTypeService.getDictType(eq(type)))
                .thenReturn(randomDictTypeDO(type).setTreeEnable(1));
    }

    private Long createNode(String dictType, Long parentId, String label, String value) {
        DictDataSaveReqVO reqVO = new DictDataSaveReqVO();
        reqVO.setDictType(dictType);
        reqVO.setParentId(parentId);
        reqVO.setLabel(label);
        reqVO.setValue(value);
        reqVO.setSort(0);
        reqVO.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return dictDataService.createDictData(reqVO);
    }

    // ========== 缓存 ==========

    /**
     * 缓存为空时回源，且新缓存打的是「查库之前」读到的版本号
     *
     * 版本号偏旧只会让前端多刷一次；偏新则会让前端把旧数据当成最新的永久固化。
     */
    @Test
    public void testGetDictDataCache_miss() {
        // mock 数据
        DictDataDO dictData = randomDictDataDO(o -> o.setStatus(CommonStatusEnum.ENABLE.getStatus()));
        dictDataMapper.insert(dictData);
        // mock 方法：缓存为空，当前版本号为 7
        when(dictCacheRedisDAO.getVersion()).thenReturn(7L);
        when(dictCacheRedisDAO.getDataCache()).thenReturn(null);

        // 调用
        DictDataCache result = dictDataService.getDictDataCache();
        // 断言：回源拿到了数据，且版本号是查库前的 7
        assertEquals(7L, result.getVersion());
        assertEquals(1, result.getList().size());
        assertEquals(dictData.getId(), result.getList().get(0).getId());
        // 断言：回填了缓存
        verify(dictCacheRedisDAO).setDataCache(argThat(cache ->
                cache.getVersion().equals(7L) && cache.getList().size() == 1));
    }

    /**
     * 缓存命中且版本号仍是当前值时，直接返回缓存，不查库
     */
    @Test
    public void testGetDictDataCache_hit() {
        // mock 数据：库里有数据，用于证明命中缓存时并没有去查它
        dictDataMapper.insert(randomDictDataDO(o -> o.setStatus(CommonStatusEnum.ENABLE.getStatus())));
        // mock 方法：缓存里是一份空列表，版本号与当前一致
        DictDataCache cached = new DictDataCache(7L, List.of());
        when(dictCacheRedisDAO.getVersion()).thenReturn(7L);
        when(dictCacheRedisDAO.getDataCache()).thenReturn(cached);

        // 调用
        DictDataCache result = dictDataService.getDictDataCache();
        // 断言：拿到的就是缓存那一份
        assertSame(cached, result);
        verify(dictCacheRedisDAO, never()).setDataCache(any());
    }

    /**
     * 缓存命中但版本号已经不是当前值时，当作未命中重新回源
     *
     * 并发写与回源交错时，缓存里会残留一份「旧版本号 + 旧数据」。
     * 它本身是自洽的，但没必要反复分发给前端，比对一次就重建。
     */
    @Test
    public void testGetDictDataCache_staleVersion() {
        // mock 数据
        DictDataDO dictData = randomDictDataDO(o -> o.setStatus(CommonStatusEnum.ENABLE.getStatus()));
        dictDataMapper.insert(dictData);
        // mock 方法：缓存是版本 6 的空列表，而当前版本已经是 8
        when(dictCacheRedisDAO.getVersion()).thenReturn(8L);
        when(dictCacheRedisDAO.getDataCache()).thenReturn(new DictDataCache(6L, List.of()));

        // 调用
        DictDataCache result = dictDataService.getDictDataCache();
        // 断言：没有把陈旧的那份返回出去，而是重新查库并打上当前版本号
        assertEquals(8L, result.getVersion());
        assertEquals(1, result.getList().size());
        verify(dictCacheRedisDAO).setDataCache(any());
    }

    // ========== 随机对象 ==========

    /**
     * 树形字段的兜底值
     *
     * randomPojo 会给 parentId / treeLevel / idPath 灌随机值，那样造出来的不是一棵合法的树：
     * parentId 指向不存在的节点，treeLevel 还会溢出 tinyint。
     * 除非用例本身就在测树形，否则统一按「根节点」构造。
     */
    private static void applyFlatTreeFields(DictDataDO o) {
        o.setParentId(DictDataDO.PARENT_ID_ROOT);
        o.setTreeLevel(1);
        o.setIdPath(DictDataDO.PATH_SEPARATOR);
    }

    @SafeVarargs
    private static DictDataDO randomDictDataDO(Consumer<DictDataDO>... consumers) {
        Consumer<DictDataDO> consumer = (o) -> {
            o.setStatus(randomCommonStatus()); // 保证 status 的范围
            applyFlatTreeFields(o);
        };
        return randomPojo(DictDataDO.class, ArrayUtils.append(consumer, consumers));
    }

    /**
     * 生成一个有效的字典类型
     *
     * @param type 字典类型
     * @return DictTypeDO 对象
     */
    private static DictTypeDO randomDictTypeDO(String type) {
        return randomPojo(DictTypeDO.class, o -> {
            o.setType(type);
            o.setStatus(CommonStatusEnum.ENABLE.getStatus()); // 保证 status 是开启
            o.setNodeType(DictTypeNodeEnum.DICT.getType()); // 分组不能挂字典数据
            o.setTreeEnable(0); // 默认平铺
        });
    }

}
