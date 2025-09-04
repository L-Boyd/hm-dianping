package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
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

    // 阻塞队列：当一个线程尝试从队列里获取元素时，如果没有元素，这个线程就会被阻塞，知道队列中有元素才会被幻唤醒
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    // 线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct  // 当前类初始化完毕后执行
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 线程任务
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                // 获取队列中的订单信息
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 创建锁对象
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 获取锁失败，代表一个用户在并发抢票
            log.error("不允许重复下单");// 理论上不可能存在这种情况，redis已经做过并发判断了
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
            return ;

        } finally {
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

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

        // 创建订单
        if (resultInt == 0) {
            // TODO 保存阻塞队列
            long orderId = redisIdWorker.nextId("order");
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(orderId);
            voucherOrder.setVoucherId(voucherId);
            voucherOrder.setUserId(userId);
            orderTasks.add(voucherOrder);

            proxy = (IVoucherOrderService) AopContext.currentProxy();

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

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单
        Long userId = voucherOrder.getId();

        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if (count > 0) {
            log.error("用户{}已购买过该优惠券", userId);  // 理论上不可能存在这种情况，redis已经做过并发判断了
            return;
        }

        // 扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .ge("stock", 1)
                .update();
        if (!success) {
            log.error("优惠券{}已售罄", voucherOrder.getVoucherId()); // 不太可能出现
            return;
        }

        // 创建订单
        save(voucherOrder);
    }
}
