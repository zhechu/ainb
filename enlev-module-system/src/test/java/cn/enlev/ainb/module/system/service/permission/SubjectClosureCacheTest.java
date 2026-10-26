package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.system.dal.redis.RedisKeyConstants;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionConfiguration;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.service.dept.DeptService;
import cn.enlev.ainb.module.system.service.dept.PostService;
import cn.enlev.ainb.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * 主体闭包的**缓存失效**测试
 *
 * 为什么单独一个类：{@link BaseDbUnitTest} 没有开 Spring Cache，
 * 所以 {@code @Cacheable}/{@code @CacheEvict} 在别的单测里是不生效的（每次都真算）。
 * 这里显式装一个内存 CacheManager 把注解激活。
 *
 * ⚠️ 也正因为缓存在这个上下文里是**跨测试方法共享**的（而 DB 每个方法结束都会清），
 * 缓存相关的用例只能待在这一个类里，且每个用例都必须自己先把缓存写脏再验证失效 ——
 * 混进别的测试类会出现「上一个方法留下的缓存让这个方法读到已经删掉的数据」
 *
 * @author 走进新时代
 */
@Import({SubjectClosureServiceImpl.class, SubjectServiceImpl.class, SubjectGraphLoader.class,
        PermissionConfiguration.class, SubjectClosureCacheTest.CacheConfiguration.class})
public class SubjectClosureCacheTest extends BaseDbUnitTest {

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    public static class CacheConfiguration {

        @Bean
        public CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(RedisKeyConstants.SUBJECT_ANCESTORS);
        }

    }

    @Resource
    private SubjectClosureService subjectClosureService;
    @Resource
    private SubjectService subjectService;

    @MockitoSpyBean
    private SubjectGraphLoader subjectGraphLoader;

    // 闭包 Service 依赖这几个别的模块的 Service，本类的用例用不到它们，
    // 但不给 Bean 的话上下文起不来
    @MockitoBean
    private PermissionService permissionService;
    @MockitoBean
    private RoleService roleService;
    @MockitoBean
    private PostService postService;
    @MockitoBean
    private DeptService deptService;
    @MockitoBean
    private AdminUserService userService;

    @Test
    public void testAncestorMap_命中缓存时不再读库() {
        subjectService.addMember(SubjectKey.ofRole(1L), SubjectKey.ofRole(2L));
        clearInvocations(subjectGraphLoader);

        subjectClosureService.getAncestorMapFromCache();
        subjectClosureService.getAncestorMapFromCache();
        subjectClosureService.getAncestorMapFromCache();

        // 三次调用只读一次库 —— 缓存真的挂上了
        verify(subjectGraphLoader, times(1)).load(false);
    }

    @Test
    public void testAncestorMap_加边后缓存失效() {
        subjectService.addMember(SubjectKey.ofRole(1L), SubjectKey.ofRole(2L));
        assertEquals(Set.of(SubjectKey.ofRole(1L)),
                subjectClosureService.getAncestorKeys(SubjectKey.ofRole(2L))); // 此时已建好缓存

        subjectService.addMember(SubjectKey.ofRole(2L), SubjectKey.ofRole(3L));

        // 不失效的话，这里会读到「ROLE:3 没有祖先」
        assertEquals(Set.of(SubjectKey.ofRole(2L), SubjectKey.ofRole(1L)),
                subjectClosureService.getAncestorKeys(SubjectKey.ofRole(3L)));
    }

    @Test
    public void testAncestorMap_删边后缓存失效() {
        subjectService.addMember(SubjectKey.ofRole(1L), SubjectKey.ofRole(2L));
        assertEquals(Set.of(SubjectKey.ofRole(1L)),
                subjectClosureService.getAncestorKeys(SubjectKey.ofRole(2L)));

        subjectService.removeMember(SubjectKey.ofRole(1L), SubjectKey.ofRole(2L));

        // 回收权限比授予权限更要求即时 —— 这一条失效漏了，就是「删了还有权限」
        assertTrue(subjectClosureService.getAncestorKeys(SubjectKey.ofRole(2L)).isEmpty());
    }

    @Test
    public void testAncestorMap_改状态后缓存失效() {
        subjectService.addMember(SubjectKey.ofRole(1L), SubjectKey.ofRole(2L));
        assertEquals(Set.of(SubjectKey.ofRole(1L)),
                subjectClosureService.getAncestorKeys(SubjectKey.ofRole(2L)));

        subjectService.updateSubjectStatus(SubjectKey.ofRole(1L), CommonStatusEnum.DISABLE.getStatus());

        assertTrue(subjectClosureService.getAncestorKeys(SubjectKey.ofRole(2L)).isEmpty());
    }

}
