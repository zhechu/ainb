package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SubjectDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SubjectMemberDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.SubjectMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.SubjectMemberMapper;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectGraph;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.util.List;

import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SubjectServiceImpl} 的单元测试类
 *
 * 重点在**写入时的四道校验**：自成员 / 重复 / 成环 / 超深。
 * 这四道都是「配错了不会立刻出事，但迟早会在登录路径上炸」的那种，
 * 必须在配置的那一刻就拦住 —— 拦不住的话，事后没有任何线索指向它
 *
 * @author 走进新时代
 */
@Import({SubjectServiceImpl.class, SubjectGraphLoader.class})
public class SubjectServiceImplTest extends BaseDbUnitTest {

    @Resource
    private SubjectServiceImpl subjectService;

    @Resource
    private SubjectMapper subjectMapper;
    @Resource
    private SubjectMemberMapper subjectMemberMapper;

    @Test
    public void testGetOrCreateSubjectId_按需落行且只落一次() {
        SubjectKey key = SubjectKey.ofRole(100L);

        Long first = subjectService.getOrCreateSubjectId(key, "销售");
        Long second = subjectService.getOrCreateSubjectId(key, "销售（重复调用）");

        assertEquals(first, second);
        assertEquals(1L, subjectMapper.selectCount());
        SubjectDO subject = subjectMapper.selectByKey(key);
        assertEquals("ROLE", subject.getType());
        assertEquals(100L, subject.getRefId());
        assertNull(subject.getCode());
        assertEquals(CommonStatusEnum.ENABLE.getStatus(), subject.getStatus());
    }

    @Test
    public void testGetOrCreateSubjectId_SYSTEM用code寻址() {
        Long id = subjectService.getOrCreateSubjectId(SubjectKey.ofSystem("bpm-engine"), "流程引擎");

        SubjectDO subject = subjectMapper.selectById(id);
        assertEquals("SYSTEM", subject.getType());
        assertNull(subject.getRefId());
        assertEquals("bpm-engine", subject.getCode());
        // 再查一次，确认 code 这条寻址路径能命中
        assertEquals(id, subjectService.getOrCreateSubjectId(SubjectKey.ofSystem("bpm-engine"), null));
        assertEquals(1L, subjectMapper.selectCount());
    }

    @Test
    public void testCreateGroupSubject_refId回写成自身() {
        Long id = subjectService.createGroupSubject("研发中心值班组");

        SubjectDO subject = subjectMapper.selectById(id);
        assertEquals("GROUP", subject.getType());
        // 用户组没有业务表，ref_id 指向自己，这样它才能和别的类型一样用 (type, ref_id) 寻址
        assertEquals(id, subject.getRefId());
        assertEquals(id, subjectMapper.selectByKey(SubjectKey.ofGroup(id)).getId());
    }

    @Test
    public void testAddMember_成功() {
        // 「高级销售 = 销售 + 额外权限」→ 容器是销售，成员是高级销售
        SubjectKey sales = SubjectKey.ofRole(1L);
        SubjectKey seniorSales = SubjectKey.ofRole(2L);

        Long memberId = subjectService.addMember(sales, seniorSales);

        SubjectMemberDO member = subjectMemberMapper.selectById(memberId);
        assertEquals(subjectMapper.selectByKey(sales).getId(), member.getSubjectId());
        assertEquals("ROLE", member.getMemberType());
        assertEquals(2L, member.getMemberRefId());
        // 容器被按需落行了，成员没有 —— 成员不需要主体行
        assertEquals(1L, subjectMapper.selectCount());
    }

    @Test
    public void testAddMember_自己不能是自己的成员() {
        SubjectKey role = SubjectKey.ofRole(1L);

        assertServiceException(() -> subjectService.addMember(role, role), SUBJECT_MEMBER_SELF);
    }

    @Test
    public void testAddMember_重复() {
        SubjectKey container = SubjectKey.ofRole(1L);
        SubjectKey member = SubjectKey.ofRole(2L);
        subjectService.addMember(container, member);

        assertServiceException(() -> subjectService.addMember(container, member), SUBJECT_MEMBER_DUPLICATE);
        assertEquals(1L, subjectMemberMapper.selectCount());
    }

    @Test
    public void testAddMember_直接成环() {
        SubjectKey a = SubjectKey.ofRole(1L);
        SubjectKey b = SubjectKey.ofRole(2L);
        subjectService.addMember(a, b); // b 是 a 的成员

        // 再让 a 当 b 的成员，就成了 a ↔ b
        assertServiceException(() -> subjectService.addMember(b, a),
                SUBJECT_MEMBER_CYCLE, "ROLE:2 → ROLE:1");
    }

    @Test
    public void testAddMember_间接成环() {
        SubjectKey a = SubjectKey.ofRole(1L);
        SubjectKey b = SubjectKey.ofRole(2L);
        SubjectKey c = SubjectKey.ofRole(3L);
        subjectService.addMember(a, b);
        subjectService.addMember(b, c);

        // a → b → c → a，S0 里表单关联环是同一类问题：只拦自引用拦不住这个
        assertServiceException(() -> subjectService.addMember(c, a),
                SUBJECT_MEMBER_CYCLE, "ROLE:3 → ROLE:1");
        assertEquals(2L, subjectMemberMapper.selectCount());
    }

    @Test
    public void testAddMember_刚好卡在深度上限时放行() {
        // 建出 MAX_DEPTH 条边的链：1 ← 2 ← … ← 6
        for (int i = 1; i <= SubjectGraph.MAX_DEPTH; i++) {
            subjectService.addMember(SubjectKey.ofRole((long) i), SubjectKey.ofRole((long) i + 1));
        }

        assertEquals((long) SubjectGraph.MAX_DEPTH, subjectMemberMapper.selectCount());
    }

    @Test
    public void testAddMember_超过深度上限() {
        for (int i = 1; i <= SubjectGraph.MAX_DEPTH; i++) {
            subjectService.addMember(SubjectKey.ofRole((long) i), SubjectKey.ofRole((long) i + 1));
        }

        // 第 6 条边会让链长到 6
        assertServiceException(
                () -> subjectService.addMember(SubjectKey.ofRole((long) SubjectGraph.MAX_DEPTH + 1),
                        SubjectKey.ofRole((long) SubjectGraph.MAX_DEPTH + 2)),
                SUBJECT_MEMBER_DEPTH_EXCEED, SubjectGraph.MAX_DEPTH + 1, SubjectGraph.MAX_DEPTH);
        assertEquals((long) SubjectGraph.MAX_DEPTH, subjectMemberMapper.selectCount());
    }

    @Test
    public void testAddMember_两条短链被接成一条长链也要拦() {
        // 只看 container 或只看 member 那一段都不超限，接起来才超 ——
        // 所以深度必须在「加完这条边之后的整张图」上量
        subjectService.addMember(SubjectKey.ofRole(1L), SubjectKey.ofRole(2L));
        subjectService.addMember(SubjectKey.ofRole(2L), SubjectKey.ofRole(3L));
        subjectService.addMember(SubjectKey.ofRole(10L), SubjectKey.ofRole(11L));
        subjectService.addMember(SubjectKey.ofRole(11L), SubjectKey.ofRole(12L));
        subjectService.addMember(SubjectKey.ofRole(12L), SubjectKey.ofRole(13L));

        // 3 ← 10 会接出 1←2←3←10←11←12←13，共 6 条边
        assertServiceException(
                () -> subjectService.addMember(SubjectKey.ofRole(3L), SubjectKey.ofRole(10L)),
                SUBJECT_MEMBER_DEPTH_EXCEED, SubjectGraph.MAX_DEPTH + 1, SubjectGraph.MAX_DEPTH);
    }

    @Test
    public void testAddMember_SYSTEM不能当成员() {
        // SYSTEM 是「系统在做事」的身份。允许它当成员，就等于给那个容器开了后门
        assertServiceException(
                () -> subjectService.addMember(SubjectKey.ofRole(1L), SubjectKey.ofSystem("bpm-engine")),
                SUBJECT_MEMBER_TYPE_NOT_SUPPORTED, "SYSTEM");
    }

    @Test
    public void testRemoveMember() {
        SubjectKey container = SubjectKey.ofRole(1L);
        SubjectKey member = SubjectKey.ofRole(2L);
        subjectService.addMember(container, member);

        subjectService.removeMember(container, member);
        assertEquals(0L, subjectMemberMapper.selectCount());

        // 删不存在的：静默返回，不抛
        subjectService.removeMember(container, member);
        subjectService.removeMember(SubjectKey.ofRole(99L), member);
    }

    @Test
    public void testGetMemberList() {
        SubjectKey container = SubjectKey.ofRole(1L);
        subjectService.addMember(container, SubjectKey.ofRole(2L));
        subjectService.addMember(container, SubjectKey.ofUser(1024L));

        List<SubjectMemberDO> members = subjectService.getMemberList(container);
        assertEquals(2, members.size());
        // 没登记过的主体，返回空列表而不是 null
        assertTrue(subjectService.getMemberList(SubjectKey.ofRole(99L)).isEmpty());
    }

    @Test
    public void testUpdateSubjectStatus() {
        SubjectKey key = SubjectKey.ofRole(1L);
        subjectService.getOrCreateSubjectId(key, "销售");

        subjectService.updateSubjectStatus(key, CommonStatusEnum.DISABLE.getStatus());

        assertEquals(CommonStatusEnum.DISABLE.getStatus(), subjectMapper.selectByKey(key).getStatus());
    }

    @Test
    public void testUpdateSubjectStatus_主体不存在() {
        assertServiceException(
                () -> subjectService.updateSubjectStatus(SubjectKey.ofRole(99L), CommonStatusEnum.DISABLE.getStatus()),
                SUBJECT_NOT_EXISTS);
    }

}
