package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static com.hmdp.utils.RedisConstants.LOCK_ORDER_KEY;

/**
 *优惠券
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService secKillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
//    注意:后面使用消息队列所以阻塞队列没用了
//    创建一个阻塞队列,用来存放订单
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

//    创建一个线程池
    private ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
//    在类初始化结束后执行
    @PostConstruct
    private void init(){
//        在类初始化完了后就提交给线程池
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
        private String queneName= "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
//                1、获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(Consumer.from("g1", queneName),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queneName, ReadOffset.lastConsumed()));
//                2、判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
//                2.1如果获取失败，说明没有消息，开始下一轮的循环
                        continue;
                    }
//                3、如果获取成功，可以下单
//                3.1、解析数据，因为是一个List而且只有一个数，所以get(0)即可
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
//                3.2、获得数据后我们需要把它转为我们需要的类型
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//                3.3、如果获取成功可以下单
                    handleVoucherOrder(voucherOrder);
//                4、ACK确认,使用SACK命令确认，SACK stream.orders g1
                    stringRedisTemplate.opsForStream().acknowledge(queneName,"g1",record.getId());
                }
                catch (Exception e){
                    log.error("处理订单异常");
                    handPendingList();
                }
            }
        }

////                1、获取队列中的订单信息
//                try {
//                    VoucherOrder voucherOrder = orderTasks.take();
////                2、创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (InterruptedException e) {
//                    log.error("处理订单异常");
//                    return;
//                }

        /**
         * 用来处理异常消息的方法，当有异常发生的时候，就会调用这个方法，在这个方法里面会不断地处理异常消息
         */
        private void handPendingList() {
            while (true) {
                try{
//                1、获取pengding-list队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(Consumer.from("g1", queneName),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queneName, ReadOffset.from("0")));
//                2、判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
//                2.1如果获取失败，说明pengding-list没有异常消息，结束循环
                        break;
                    }
//                3、如果获取成功，可以下单
//                3.1、解析数据，因为是一个List而且只有一个数，所以get(0)即可
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
//                3.2、获得数据后我们需要把它转为我们需要的类型
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//                3.3、如果获取成功可以下单
                    handleVoucherOrder(voucherOrder);
//                4、ACK确认,使用SACK命令确认，SACK stream.orders g1
                    stringRedisTemplate.opsForStream().acknowledge(queneName,"g1",record.getId());
                }
                catch (Exception e) {
                    log.error("处理pengding-list异常");
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }
    private IVoucherOrderService proxy;

//    创建函数来创建订单
    private void handleVoucherOrder(VoucherOrder voucherOrder){
//        1、获取用户Id
        Long userId = voucherOrder.getUserId();
//        创建锁对象
        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY+userId);
//        判断获取锁成功
        boolean isLock = lock.tryLock();
        if(!isLock){
//            获取锁失败，返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        try{
            proxy.createVoucherOrder(voucherOrder);
        }finally {
//            释放锁
            lock.unlock();
        }

    }
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
//        默认路径是resource目录下
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
////        1、获取用户Id
//        Long userId = UserHolder.getUser().getId();
//        long orderId = redisIdWorker.nextId("order");
////        2、执行Lua脚本
//        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(),userId.toString());
////        为了方便后面的步骤把long类型改为int类型
//        int r = result.intValue();
////        3、判断结果是否为0
//        if(r!=0){
////        3.1、不为0代表没有购买资格，没有购买资格分为两种
//            return Result.fail(r==1?"库存不足":"不能重复下单");
//        }
////        3.2、为0代表有购买资格,把下单信息保存到阻塞队列
////            获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
////        返回订单Id
//        return Result.ok(orderId);
//    }
    @Override
    public Result seckillVoucher(Long voucherId) {
//        1、获取用户Id
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
//        2、执行Lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString(),String.valueOf(orderId));
//        为了方便后面的步骤把long类型改为int类型
        int r = result.intValue();
//        3、判断结果是否为0
        if (r != 0) {
//        3.1、不为0代表没有购买资格，没有购买资格分为两种
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
//        3.2、为0代表有购买资格,把下单信息保存到阻塞队列
//            获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        返回订单Id
        return Result.ok(orderId);
    }


        /**
         * 下面是使用Java代码完成的代码
         * @param voucherId
         * @return
         */
    /**
    @Override
    public Result seckillVoucher(Long voucherId) {
//        1、查询优惠券信息
        SeckillVoucher voucher = secKillVoucherService.getById(voucherId);
//        2、判断秒杀是否开启
//        3、秒杀尚未开启
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀还没开始呢~");
        }
//        4、秒杀已经结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束了");
        }
//        5、秒杀正常期间
//        判断库存是否充足
        if (voucher.getStock()<1){
            Result.fail("库存不足了呢~");
        }
        Long userId = UserHolder.getUser().getId();
//        创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate,"order:"+userId);
        RLock lock = redissonClient.getLock("order:" + userId);
//        获取锁
//        有三种情况，有三个参数的方法第一个参数是等待时间，默认是-1，就是不等待，错误就立即返回，在等待时间会重试，所以有重试机制
        boolean isLock = lock.tryLock();
//        判断获取锁成功
        if(!isLock){
//            获取锁失败，返回错误或重试
            return Result.fail("一个人只能下一单");
        }
//        自己创建一个锁，不使用synchronized
//        synchronized (userId.toString().intern()){
//            如果直接这样做的话，这里的事务是不生效的，因为这里是this对象不是动态代理的对象，所以spring无法替我们完成事务
//            所以需要获得当前对象的代理对象
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            使用函数封装保证在事务提交后才释放锁
            return proxy.createVoucherOrder(voucherId);
        } finally {
//            释放锁
            lock.unlock();
        }
//        }
    }
    */
    //        实现一人一单
//        （1）查询订单
//    事务要想生效，需要当前对象得到动态代理对象，用动态代理对象实现事务
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
//        获取锁对象
        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY+userId);
//        尝试获取锁
        boolean isLock = lock.tryLock();
        if(!isLock){
            log.error("您已经购买过一次了");
            return;
        }

//        查询订单,是否有过这个订单
//            用户下过单

        try {
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                log.error("您已经购买过一次了");
                return;
            }
            //扣减库存
            boolean success = secKillVoucherService.update().setSql("stock = stock-1")
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0) //where id = ? and stock > 0
                    .update();
            //扣减库存失败
            if (!success) {
                log.error("库存不足~");
                return;
            }
//            创建订单
            save(voucherOrder);
        } finally {
            lock.unlock();
        }
    }
}
