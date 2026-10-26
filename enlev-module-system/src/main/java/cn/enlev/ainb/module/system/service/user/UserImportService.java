package cn.enlev.ainb.module.system.service.user;

import cn.enlev.ainb.module.system.controller.admin.user.vo.user.UserImportExecuteReqVO;
import cn.enlev.ainb.module.system.controller.admin.user.vo.user.UserImportPreviewRespVO;
import cn.enlev.ainb.module.system.controller.admin.user.vo.user.UserImportResultRespVO;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 用户导入 Service 接口
 *
 * <h3>为什么导入要分成预检与执行两次调用</h3>
 *
 * 改造前是一次调用直接落库，用户在点「确定」的那一刻并不知道会发生什么。
 * 一份 500 行的文件里混着 3 行错的，等结果出来时 497 行已经进库了 ——
 * 而写入是不可撤销的。
 *
 * 拆成两次之后，第一次只读不写，把「会发生什么」完整地摆给用户看；
 * 第二次才写，且写的就是他刚才看过的那一份。
 *
 * @author 芋道源码
 */
public interface UserImportService {

    /**
     * 预检：解析文件、匹配表头、逐行校验，但<b>不写库</b>
     *
     * @param file        Excel 文件
     * @param maxRows     行数上限
     * @param previewRows 最多返回多少行明细给前端
     * @return 预检结果，含执行时要带回的 importToken
     */
    UserImportPreviewRespVO preview(MultipartFile file, int maxRows, int previewRows) throws IOException;

    /**
     * 执行：把上一次预检的结果真正写进库
     *
     * @param reqVO 执行参数，含 importToken
     * @return 执行结果
     */
    UserImportResultRespVO execute(UserImportExecuteReqVO reqVO);

}
