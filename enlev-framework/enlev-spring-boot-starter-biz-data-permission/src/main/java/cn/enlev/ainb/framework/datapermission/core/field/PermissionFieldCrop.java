package cn.enlev.ainb.framework.datapermission.core.field;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 按字段权限裁剪接口返回值（S7a）
 *
 * <h3>它解决的问题：原生模块此前没有字段级权限</h3>
 *
 * 在线表单的字段裁剪走的是 {@code FormFieldCropper}，而原生模块返回的是
 * 各自的 VO，没有统一的「字段清单」可裁。于是在授权页上给
 * {@code system:user:fld:mobile} 配一条 DENY，配了也不会有任何效果。
 *
 * 标上本注解后，方法返回的 {@code CommonResult} 里的数据对象会被逐个检查，
 * 判成 HIDDEN 的属性置空。
 *
 * <pre>
 * &#64;PermissionFieldCrop("system:user")
 * &#64;GetMapping("/get")
 * public CommonResult&lt;UserRespVO&gt; getUser(...) { ... }
 * </pre>
 *
 * <h3>⚠️ 只处理 HIDDEN，不处理 READONLY</h3>
 *
 * READONLY 说的是「能看不能改」，那是<b>写路径</b>的事。
 * 在读接口上把只读字段也置空，等于把「不能改」执行成了「看不见」——
 * 用户会以为数据丢了。原生模块的写保护要在 Service 层用
 * {@link PermissionFieldHelper#stripNonWritable} 做。
 *
 * <h3>⚠️ 置空而不是删键</h3>
 *
 * VO 是强类型的，删不掉属性。置空之后前端渲染成空白 ——
 * 与在线表单「整条规则剔除」的效果不完全一样，但对「值不能出去」这件事是等价的。
 * 要做到连字段都不出现，得由 S8 在前端按字段模式隐藏。
 *
 * @author 走进新时代
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PermissionFieldCrop {

    /**
     * 数据集的权限标识，如 {@code system:user}
     *
     * ⚠️ 必须与 {@code @PermissionDataset} 上写的那个<b>完全一致</b>。
     * 写岔了的表现是「字段权限配了不生效」，因为查的是另一个根本不存在的资源串
     */
    String value();

    /**
     * 要参与判定的字段名。留空表示「返回对象的全部属性」
     *
     * 显式列出是更省的做法：字段资源本来就是按需创建的，
     * 一个几十属性的 VO 里通常只有两三个配过权限，
     * 把全部属性都送去判定只是把同一个「查不到资源行」的结论算几十遍
     */
    String[] fields() default {};

    /**
     * 按<b>列级</b>（{@code :col:}）还是<b>字段级</b>（{@code :fld:}）权限裁剪
     *
     * <ul>
     *   <li>{@code true} —— 列表类接口（page / list）。管的是「列表里这一列显不显示」</li>
     *   <li>{@code false}（默认）—— 详情类接口（get）。管的是「详情里这个字段能不能看」</li>
     * </ul>
     *
     * ⚠️ 标错的后果是<b>查了另一套资源</b>：在列表接口上用 false，
     * 读到的是详情字段的授权，于是「列表里隐藏手机号」这条配置永远不生效，
     * 且不会有任何报错 —— 只能靠人工比对两套资源才能发现
     */
    boolean column() default false;

}
