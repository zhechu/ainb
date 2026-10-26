package cn.enlev.ainb.framework.datapermission.core.field;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * {@link PermissionFieldCrop} 的切面（S7a）
 *
 * <h3>为什么是切面，而不是让每个 Controller 自己调一行</h3>
 *
 * 同一个数据集常常有好几个读取出口（get / page / export / 下拉列表…）。
 * 靠自觉逐个调的话，<b>漏掉的那个出口就是一扇后门</b> ——
 * 在线表单那边已经踩过两次：S4b-2 漏了 {@code plain-field}（TODO #19）、
 * S4c 才补上 {@code export}（TODO #12）。两次的表现都是「抓包就能看到明文」。
 *
 * 切面至少让「接没接」变成方法签名上看得见的一件事。
 *
 * <h3>裁剪失败为什么不抛异常</h3>
 *
 * 抛出去会让整个接口 500。字段裁剪是<b>收紧</b>性质的加工，它失败时正确的
 * 降级方向是有争议的：不裁 = 可能泄露，全裁 = 接口等于不可用。
 * 这里选「记 ERROR 并原样返回」，因为把一次偶发的反射失败放大成
 * 全量数据丢失，会让人以为数据坏了。真正的防线在写路径与行级过滤上。
 *
 * @author 走进新时代
 */
@Aspect
@Slf4j
public class PermissionFieldCropAspect {

    private final PermissionFieldHelper fieldHelper;

    public PermissionFieldCropAspect(PermissionFieldHelper fieldHelper) {
        this.fieldHelper = fieldHelper;
    }

    @Around("@annotation(cn.enlev.ainb.framework.datapermission.core.field.PermissionFieldCrop) "
            + "|| @within(cn.enlev.ainb.framework.datapermission.core.field.PermissionFieldCrop)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Object result = joinPoint.proceed();
        try {
            PermissionFieldCrop annotation = resolveAnnotation(joinPoint);
            if (annotation == null) {
                return result;
            }
            Object data = result instanceof CommonResult<?> commonResult
                    ? commonResult.getData() : result;
            List<String> fields = Arrays.asList(annotation.fields());
            fieldHelper.crop(data, annotation.value(), fields, annotation.column());
        } catch (Exception ex) {
            log.error("[around][{}#{} 的字段裁剪失败，本次返回未裁剪的数据]",
                    joinPoint.getSignature().getDeclaringTypeName(),
                    joinPoint.getSignature().getName(), ex);
        }
        return result;
    }

    /**
     * 方法上的声明优先于类上的 —— 同一个 Controller 里可能有几个不同数据集的接口
     */
    private PermissionFieldCrop resolveAnnotation(ProceedingJoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        PermissionFieldCrop annotation = method.getAnnotation(PermissionFieldCrop.class);
        if (annotation != null) {
            return annotation;
        }
        return method.getDeclaringClass().getAnnotation(PermissionFieldCrop.class);
    }

}
