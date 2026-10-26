package cn.enlev.ainb.module.infra.dal.mysql.file;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;

import java.util.Collection;
import java.util.List;
import cn.enlev.ainb.module.infra.controller.admin.file.vo.file.FilePageReqVO;
import cn.enlev.ainb.module.infra.dal.dataobject.file.FileDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件操作 Mapper
 *
 * @author 芋道源码
 */
@Mapper
public interface FileMapper extends BaseMapperX<FileDO> {

    default PageResult<FileDO> selectPage(FilePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<FileDO>()
                .likeIfPresent(FileDO::getPath, reqVO.getPath())
                .likeIfPresent(FileDO::getType, reqVO.getType())
                .betweenIfPresent(FileDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(FileDO::getId));
    }

    /**
     * 按访问地址批量查询
     *
     * 给表单设计的上传值补元信息用：一次提交里可能有多个文件字段、每个字段多个文件，
     * 逐个查会把一次保存变成 N 次查询，所以做成批量的。
     *
     * ⚠️ url 上没有索引。这里能接受是因为调用点在【提交】路径上（低频）、
     * 且一次只查这次提交涉及的那几个地址；不要把它挪到列表或详情那种读路径上去。
     *
     * @param urls 访问地址，调用方保证非空且已去重
     * @return 文件清单。查不到的地址不会出现在结果里
     */
    default List<FileDO> selectListByUrls(Collection<String> urls) {
        return selectList(new LambdaQueryWrapperX<FileDO>().in(FileDO::getUrl, urls));
    }

    default FileDO selectLatestByConfigIdAndPath(Long configId, String path) {
        return selectLastOne(new LambdaQueryWrapperX<FileDO>()
                .eq(FileDO::getConfigId, configId)
                .eq(FileDO::getPath, path)
                .orderByAsc(FileDO::getId));
    }

}
