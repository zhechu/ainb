package cn.enlev.ainb.module.system.dal.dataobject.permission;

import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SubjectDO#toKey()} 对脏行的处理
 *
 * <h3>为什么一个 DO 上的转换方法值得单独钉住</h3>
 *
 * 它是「库里的行 → 寻址串」的入口，而 {@code sys_subject.ref_id} 与 {@code code}
 * 在表结构上<b>都是可空列</b>，所以「type 与标识不配对」的行建得出来（直接改库就能改出来）。
 * 唯一调用方 {@code SubjectGraphLoader} 在<b>登录链路</b>上，它对 null 是明确容忍的 ——
 * 拿到 null 就跳过那条边，注释写着「抛的话，一行坏数据会让所有人都登不上来」。
 *
 * <p>而 {@code toKey()} 原先只挡了 type 解析不出来这一种，少给标识那一支会把参数直接塞进
 * {@code SubjectKey} 的紧凑构造器：ref 型缺 refId 得到 {@code NullPointerException}，
 * code 型缺 code 得到 {@code IllegalArgumentException}。两者都没有全局 handler，
 * 于是调用方明确要避免的那件事又发生了 —— 一行脏数据，全员登不上来。
 *
 * <p>同类入口 {@link SubjectKey#parse} 早就是一律返回 null 的口径（它的注释：
 * 「一行脏数据不应该让整个权限求值炸掉」），本方法现在与它对齐。
 *
 * @author 走进新时代
 */
public class SubjectDOTest {

    private static SubjectDO subject(String type, Long refId, String code) {
        SubjectDO subject = new SubjectDO();
        subject.setType(type);
        subject.setRefId(refId);
        subject.setCode(code);
        return subject;
    }

    // ============================= 正常行：照常还原 =============================

    @Test
    @DisplayName("ref 型正常行：还原成 ROLE:refId")
    void testToKeyRefBasedOk() {
        assertEquals(SubjectKey.ofRole(9L), subject("ROLE", 9L, null).toKey());
    }

    @Test
    @DisplayName("code 型正常行：还原成 SYSTEM:code")
    void testToKeyCodeBasedOk() {
        assertEquals(SubjectKey.ofSystem("bpm-engine"), subject("SYSTEM", null, "bpm-engine").toKey());
    }

    // ============================= 脏行：一律 null，绝不抛 =============================

    @Test
    @DisplayName("ref 型但 ref_id 为空：返回 null，而不是 requireNonNull 的 NPE")
    void testToKeyRefBasedMissingRefId() {
        // 🔴 ref_id 是可空列，库里现在没有这种行（实测 bad_ref_rows=0），
        // 但表结构不拦，所以这是「一行脏数据 ⇒ 全员登不上来」的引信，不是理论问题
        assertNull(subject("ROLE", null, null).toKey());
        assertNull(subject("USER", null, "被误填的 code 也救不回来").toKey());
    }

    @Test
    @DisplayName("code 型但 code 为空：返回 null，而不是 IllegalArgumentException")
    void testToKeyCodeBasedMissingCode() {
        assertNull(subject("SYSTEM", null, null).toKey());
        assertNull(subject("SYSTEM", null, "").toKey());
        // 空白串同样算脏：SubjectKey 用的是 StrUtil.isBlank，口径必须一致，
        // 否则这里放过去、构造器里再炸，等于白挡
        assertNull(subject("SYSTEM", null, "   ").toKey());
        assertNull(subject("ALL", null, null).toKey());
    }

    @Test
    @DisplayName("type 认不出来：返回 null（这一支改动前后一致）")
    void testToKeyUnparseableType() {
        assertNull(subject("ROLES", 9L, null).toKey()); // 复数，最常见的笔误
        assertNull(subject("role", 9L, null).toKey());  // 小写，枚举是精确匹配
        assertNull(subject(null, 9L, "x").toKey());
        assertNull(subject("", null, "").toKey());
    }

    @Test
    @DisplayName("type 与标识张冠李戴：以 type 为准，多余的那个忽略掉")
    void testToKeyMismatchedIdentifier() {
        // ref 型只认 refId：code 有值也不看（SubjectKey 的紧凑构造器会把它置空，
        // 为的是避免「refId 相同但 code 不同」的两个 key 不相等）
        assertEquals(SubjectKey.ofRole(9L), subject("ROLE", 9L, "多填的 code").toKey());
        // code 型只认 code：refId 有值也不看
        assertEquals(SubjectKey.ofSystem("bpm-engine"), subject("SYSTEM", 9L, "bpm-engine").toKey());
    }

}
