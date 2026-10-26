package cn.enlev.ainb.module.infra.framework.formdesign.core;

import cn.hutool.core.util.StrUtil;

import java.util.List;

/**
 * 报错文案的拼装
 *
 * 只放「多处同类错误怎么概括」这类**用户可见格式**。抽出来是因为格式必须在
 * 各个校验器之间保持一致 —— 子表行数校验说「第 1、2、3 等 30 行」、
 * 格式校验说「第 1,2,3... 共 30 行」，用户会以为是两套不同的东西。
 *
 * @author 走进新时代
 */
public final class FormErrorText {

    /**
     * 最多列出几个行号，超出部分用「等 N 行」概括
     *
     * 取 5：30 行都出错时报 30 个行号是刷屏，用户反而找不到重点；
     * 而只报一个行号又会让用户改完一行再提交、再被拦下一行。
     */
    private static final int MAX_LISTED_ROWS = 5;

    private FormErrorText() {
    }

    /**
     * 把行号列表拼成文案
     *
     * 行号由调用方保证是【从 1 开始】的展示序号，与界面上看到的一致 ——
     * 报下标 0 会让用户去数第 0 行。
     *
     * @param rowNumbers 行号列表，从 1 开始
     * @return 如「1、2、3」或「1、2、3、4、5 等 30 行」
     */
    public static String rows(List<Integer> rowNumbers) {
        if (rowNumbers == null || rowNumbers.isEmpty()) {
            return "";
        }
        if (rowNumbers.size() <= MAX_LISTED_ROWS) {
            return StrUtil.join("、", rowNumbers);
        }
        return StrUtil.join("、", rowNumbers.subList(0, MAX_LISTED_ROWS))
                + " 等 " + rowNumbers.size() + " 行";
    }

}
