package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result createSeckillVoucherOrder(Long voucherId) {
        /*SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);

        // 判断是否在可购时间内
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        LocalDateTime endTime = seckillVoucher.getEndTime();
        if (beginTime.isAfter(LocalDateTime.now())) {
            return Result.fail("优惠券未到可购买时间");
        }
        if (endTime.isBefore(LocalDateTime.now())) {
            return Result.fail("优惠券已过可购买时间");
        }

        // 判断库存是否充足
        Integer stock = seckillVoucher.getStock();
        if (stock.compareTo(0) < 0) {
            return Result.fail("优惠券已售罄");
        }

        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 获取锁失败，代表一个用户在并发抢票，要阻止，而不是重试
            return Result.fail("不允许重复下单");
        }
        try {

            // this.createVoucherOrder(voucherId)，不是代理对象，事务生效是因为spring对this这个类做了动态代理，对代理对象做了事务处理，所以直接调没有事务功能。
            // 这是spring事务失效场景之一

            // 从AopContext中获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);

        } finally {
            lock.unlock();
        }*/

        // 改用redis提高响应速度
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString());
        int resultInt = result.intValue();
        if (result == 1) {
            return Result.fail("优惠券已售罄");
        }
        if (result == 2) {
            return Result.fail("您已购买过该优惠券");
        }
        if (resultInt == 0) {
            // TODO 保存阻塞队列
            long orderId = redisIdWorker.nextId("order");
            return Result.ok(orderId);
        }
        return Result.ok();
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 一人一单
        Long userId = UserHolder.getUser().getId();

        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        if (count > 0) {
            return Result.fail("您已购买过该优惠券");
        }

        // 扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .ge("stock", 1)
                .update();
        if (!success) {
            return Result.fail("优惠券已售罄");
        }

        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("voucherOrder");
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        // 因为是静态方法，所以可以不用创建对象直接调用
        voucherOrder.setUserId(userId);
        save(voucherOrder);

        // 返回订单id
        return Result.ok(orderId);
    }
}
