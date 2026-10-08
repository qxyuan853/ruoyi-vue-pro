package cn.iocoder.yudao.module.member.dal.mysql.user;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.member.dal.dataobject.user.MemberUserDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;

import javax.annotation.Resource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link MemberUserMapper} 积分扣减的单元测试
 */
public class MemberUserMapperPointTest extends BaseDbUnitTest {

    @Resource
    private MemberUserMapper memberUserMapper;

    @Test
    public void testUpdatePointDecr_enough() {
        Long id = insertUser(100);

        assertEquals(1, memberUserMapper.updatePointDecr(id, -100));
        assertEquals(0, selectPoint(id));
    }

    @Test
    public void testUpdatePointDecr_notEnough() {
        Long id = insertUser(100);

        assertEquals(0, memberUserMapper.updatePointDecr(id, -101));
        assertEquals(100, selectPoint(id));
    }

    @Test
    public void testUpdatePointDecr_secondDeductionOfSameBalanceRejected() {
        Long id = insertUser(100);

        // 两个请求读到同一余额 100、各自扣减 100：只有一个能成功，积分不能变成负数
        assertEquals(1, memberUserMapper.updatePointDecr(id, -100));
        assertEquals(0, memberUserMapper.updatePointDecr(id, -100));
        assertEquals(0, selectPoint(id));
    }

    private Long insertUser(int point) {
        MemberUserDO user = new MemberUserDO();
        user.setMobile("13800138000");
        user.setPassword("");
        user.setStatus(CommonStatusEnum.ENABLE.getStatus());
        user.setRegisterIp("127.0.0.1");
        user.setNickname("point-test");
        user.setAvatar("");
        user.setPoint(point);
        memberUserMapper.insert(user);
        return user.getId();
    }

    private int selectPoint(Long id) {
        // 只查 point 列：单测 H2 的 member_user 表不包含 MemberUserDO 的全部字段
        return ((Number) memberUserMapper.selectObjs(new LambdaQueryWrapper<MemberUserDO>()
                .select(MemberUserDO::getPoint).eq(MemberUserDO::getId, id)).get(0)).intValue();
    }

}
