package cn.iocoder.yudao.module.member.service.signin;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.date.DateUtils;
import cn.iocoder.yudao.framework.common.util.object.ObjectUtils;
import cn.iocoder.yudao.module.member.controller.admin.signin.vo.record.MemberSignInRecordPageReqVO;
import cn.iocoder.yudao.module.member.controller.app.signin.vo.record.AppMemberSignInRecordSummaryRespVO;
import cn.iocoder.yudao.module.member.convert.signin.MemberSignInRecordConvert;
import cn.iocoder.yudao.module.member.dal.dataobject.signin.MemberSignInConfigDO;
import cn.iocoder.yudao.module.member.dal.dataobject.signin.MemberSignInRecordDO;
import cn.iocoder.yudao.module.member.dal.dataobject.user.MemberUserDO;
import cn.iocoder.yudao.module.member.dal.mysql.signin.MemberSignInRecordMapper;
import cn.iocoder.yudao.module.member.enums.MemberExperienceBizTypeEnum;
import cn.iocoder.yudao.module.member.enums.point.MemberPointBizTypeEnum;
import cn.iocoder.yudao.module.member.service.level.MemberLevelService;
import cn.iocoder.yudao.module.member.service.point.MemberPointRecordService;
import cn.iocoder.yudao.module.member.service.user.MemberUserService;
import cn.hutool.extra.spring.SpringUtil;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.iocoder.yudao.module.member.enums.ErrorCodeConstants.SIGN_IN_RECORD_TODAY_EXISTS;

/**
 * 签到记录 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
public class MemberSignInRecordServiceImpl implements MemberSignInRecordService {

    /**
     * 签到的分布式锁
     *
     * KEY 格式：member_sign_in:lock:{userId}
     * VALUE 数据格式：HASH // RLock.class：Redisson 的 Lock 锁，使用 Hash 数据结构
     */
    private static final String SIGN_IN_LOCK = "member_sign_in:lock:%d";
    private static final long SIGN_IN_LOCK_TIMEOUT_MILLIS = 30 * 1000L;

    @Resource
    private MemberSignInRecordMapper signInRecordMapper;

    @Resource
    private RedissonClient redissonClient;
    @Resource
    private MemberSignInConfigService signInConfigService;
    @Resource
    private MemberPointRecordService pointRecordService;
    @Resource
    private MemberLevelService memberLevelService;

    @Resource
    private MemberUserService memberUserService;

    @Override
    public AppMemberSignInRecordSummaryRespVO getSignInRecordSummary(Long userId) {
        // 1. 初始化默认返回信息
        AppMemberSignInRecordSummaryRespVO summary = new AppMemberSignInRecordSummaryRespVO();
        summary.setTotalDay(0);
        summary.setContinuousDay(0);
        summary.setTodaySignIn(false);

        // 2. 获取用户签到的记录数
        Long signCount = signInRecordMapper.selectCountByUserId(userId);
        if (ObjUtil.equal(signCount, 0L)) {
            return summary;
        }
        summary.setTotalDay(signCount.intValue()); // 设置总签到天数

        // 3. 校验当天是否有签到
        MemberSignInRecordDO lastRecord = signInRecordMapper.selectLastRecordByUserId(userId);
        if (lastRecord == null) {
            return summary;
        }
        summary.setTodaySignIn(DateUtils.isToday(lastRecord.getCreateTime()));

        // 4.1 检查今天是否未签到且记录不是昨天创建的，如果是则直接返回
        if (!summary.getTodaySignIn() && !DateUtils.isYesterday(lastRecord.getCreateTime())) {
            return summary;
        }

        // 4.2 要么是今天签到了，要么是昨天的记录，设置连续签到天数
        summary.setContinuousDay(lastRecord.getDay());
        return summary;
    }

    @Override
    public PageResult<MemberSignInRecordDO> getSignInRecordPage(MemberSignInRecordPageReqVO pageReqVO) {
        // 根据用户昵称查询出用户ids
        Set<Long> userIds = null;
        if (StringUtils.isNotBlank(pageReqVO.getNickname())) {
            List<MemberUserDO> users = memberUserService.getUserListByNickname(pageReqVO.getNickname());
            // 如果查询用户结果为空直接返回无需继续查询
            if (CollUtil.isEmpty(users)) {
                return PageResult.empty();
            }
            userIds = convertSet(users, MemberUserDO::getId);
        }
        // 分页查询
        return signInRecordMapper.selectPage(pageReqVO, userIds);
    }

    @Override
    public PageResult<MemberSignInRecordDO> getSignRecordPage(Long userId, PageParam pageParam) {
        return signInRecordMapper.selectPage(userId, pageParam);
    }

    @Override
    public MemberSignInRecordDO createSignRecord(Long userId) {
        // 【安全】串行化同一用户的并发签到。
        //
        // validateSigned() 只是一条普通 SELECT（读最新一条签到记录，判断是不是今天），
        // 它和后面的 insert 签到记录 + 加积分之间没有行锁、CAS，member_sign_in_record 上
        // 也没有「每人每天一条」的唯一索引，所以并发的 N 个请求会全部读到
        // 「今天还没签」并全部发放奖励。积分的加法本身是原子的（point = point + ?），
        // 但「加几次」没有被控制。
        //
        // 锁必须加在事务【外面】：如果在事务内加锁，就会在提交前 unlock，
        // 后一个事务拿到锁后建立的一致性读快照仍可能看不到前一个事务刚插入的记录。
        //
        // 这里用 Redis 锁而不是 SELECT ... FOR UPDATE，因为 FOR UPDATE 在本项目支持的
        // SQL Server 上是语法错误，而 Redisson 是所有部署都已经依赖的组件。
        RLock lock = redissonClient.getLock(String.format(SIGN_IN_LOCK, userId));
        lock.lock(SIGN_IN_LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        try {
            return getSelf().createSignRecord0(userId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public MemberSignInRecordDO createSignRecord0(Long userId) {
        // 1. 获取当前用户最近的签到
        MemberSignInRecordDO lastRecord = signInRecordMapper.selectLastRecordByUserId(userId);
        // 1.1. 判断是否重复签到
        validateSigned(lastRecord);

        // 2.1. 获取所有的签到规则
        List<MemberSignInConfigDO> signInConfigs = signInConfigService.getSignInConfigList(CommonStatusEnum.ENABLE.getStatus());
        // 2.2. 组合数据
        MemberSignInRecordDO record = MemberSignInRecordConvert.INSTANCE.convert(userId, lastRecord, signInConfigs);

        // 3. 插入签到记录
        signInRecordMapper.insert(record);

        // 4. 增加积分
        if (!ObjectUtils.equalsAny(record.getPoint(), null, 0)) {
            pointRecordService.createPointRecord(userId, record.getPoint(), MemberPointBizTypeEnum.SIGN, String.valueOf(record.getId()));
        }
        // 5. 增加经验
        if (!ObjectUtils.equalsAny(record.getExperience(), null, 0)) {
            memberLevelService.addExperience(userId, record.getExperience(), MemberExperienceBizTypeEnum.SIGN_IN, String.valueOf(record.getId()));
        }
        return record;
    }

    /**
     * 获得自身的代理对象，解决 AOP 生效问题
     *
     * @return 自己
     */
    private MemberSignInRecordServiceImpl getSelf() {
        return SpringUtil.getBean(getClass());
    }

    private void validateSigned(MemberSignInRecordDO signInRecordDO) {
        if (signInRecordDO == null) {
            return;
        }
        if (DateUtils.isToday(signInRecordDO.getCreateTime())) {
            throw exception(SIGN_IN_RECORD_TODAY_EXISTS);
        }
    }

}
