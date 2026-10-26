package cn.enlev.ainb.module.infra.dal.mysql.formdesign;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignPageReqVO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 表单设计 Mapper
 *
 * @author 走进新时代
 */
@Mapper
public interface FormDesignMapper extends BaseMapperX<FormDesignDO> {

    default FormDesignDO selectByCode(String code) {
        return selectOne(FormDesignDO::getCode, code);
    }

    /**
     * 软删，同时把 code 改成墓碑值
     *
     * <p>🔴 为什么不能只置 {@code deleted=1}：{@code uk_code} 是<b>不含 deleted 的</b>全局唯一索引，
     * 而逻辑删除的行仍然占着索引。于是「删掉表单 → 用同一个编码重建」必然撞
     * {@code Duplicate entry 'xxx' for key 'uk_code'}，而这个编码在页面上<b>查不到任何表单</b>
     * （selectByCode 带 deleted=0），用户和 AI 都无从判断是谁占了它。实测就是这么炸的：
     * 流程走到 form_save_draft 才抛一串 MyBatis 的 SQL 异常。
     *
     * <p>不改索引而改 code，是因为 {@code (code, deleted)} 这种组合唯一索引只容得下<b>一条</b>
     * 同编码的已删行 —— 建→删→再建→再删就撞第二次，正好是 AI 反复重试的路径。
     * 墓碑值带上 id（全局唯一），删多少次都不会互相顶。
     *
     * <h3>🔴 版本条件是手写的，因为拦截器结构上够不着这里</h3>
     * {@code OptimisticLockerInnerInterceptor} 要从参数 Map 里取出 {@code et}（实体）才能读到
     * version 字段。本方法是手写的 {@code @Update}，参数里只有两个 {@code @Param}、没有实体，
     * 于是拦截器<b>静默什么都不做</b> —— 而 {@code @Version} 注解还在 DO 上，
     * 看上去像是「已经加了锁」。这就是 B4-4 说的「加了注解只算 10%」的具体形态之一。
     *
     * <p>为什么这条<b>删除</b>路径也要上锁，而数据模型那边的 {@code deleteById} 不用：
     * 调用方在删之前刚判过「这张表单没有绑定菜单」，判据是它读到的那一行。
     * 若有人在这中间发布了它、绑上了菜单，不加锁就会删掉一张刚建好菜单的表单，
     * 留下一批指向空处的 {@code system_menu} 记录 —— 正是调用方那段注释声称要避免的结局。
     * 版本条件让「我看到的」与「我要删的」必须是同一行，那道判据才成立。
     *
     * @param id            表单设计编号
     * @param tombstoneCode 墓碑编码，由调用方按列宽截好
     * @param version       调用方读到的乐观锁版本
     * @return 影响行数。<b>0 表示版本已变、什么都没删</b>，调用方必须检查并报错，
     *         否则会以为删掉了
     */
    @Update("UPDATE infra_form_design SET deleted = 1, code = #{tombstoneCode}, version = version + 1 "
            + "WHERE id = #{id} AND deleted = 0 AND version = #{version}")
    int updateToDelete(@Param("id") Long id, @Param("tombstoneCode") String tombstoneCode,
                       @Param("version") Integer version);

    /**
     * 按表单编码批量查询
     *
     * 给服务端扩展点的启动期模式错配扫描用：一次把所有涉及的表单捞出来，
     * 而不是逐个 selectByCode —— 扩展点数量不确定，逐个查在启动路径上不合适。
     */
    default List<FormDesignDO> selectListByCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Collections.emptyList();
        }
        return selectList(new LambdaQueryWrapperX<FormDesignDO>()
                .in(FormDesignDO::getCode, codes));
    }

    default PageResult<FormDesignDO> selectPage(FormDesignPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<FormDesignDO>()
                .likeIfPresent(FormDesignDO::getName, reqVO.getName())
                .likeIfPresent(FormDesignDO::getCode, reqVO.getCode())
                .eqIfPresent(FormDesignDO::getCategory, reqVO.getCategory())
                .eqIfPresent(FormDesignDO::getFormType, reqVO.getFormType())
                .eqIfPresent(FormDesignDO::getStatus, reqVO.getStatus())
                .orderByDesc(FormDesignDO::getId));
    }

}
