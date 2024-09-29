package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 商铺实现接口
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    //    尝试获取锁
    private boolean tryLock(String key) {
//        用来判断是否有线程先获得锁了
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    public Result queryById(Long id) {
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }


    public Shop queryWithMutex(Long id) throws InterruptedException {
//        缓存的key
        String CacheKey = CACHE_SHOP_KEY + id;
//        1、从Redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CacheKey);
//        2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
//        3.存在,直接返回,反序列化，把JSON数据转化为对象
            return JSONUtil.toBean(shopJson, Shop.class);
        }
//        isNotBlank判断为空时也是false，这样的话就会向下运行了，会查询数据库了，所以要在这里添加一个条件,只要它是null就让它执行下面的查询数据库
        if (shopJson != null) {
            return null;
        }
//        实现缓存重建：1.获取互斥锁 2.判断是否获取成功 3.失败就休眠重试 4.成功就根据id查询数据库 5.写入Redis 6.释放互斥锁
//        4.不存在,根据id查询数据库
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (!isLock) {
            Thread.sleep(50);
            queryWithPassThrough(id);
        }
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        Shop shop = getById(id);
        if (shop == null) {
//        5.不存在,返回false
//            使用缓存null值解决缓存穿透
            stringRedisTemplate.opsForValue().set(CacheKey, "", CACHE_SHOP_TTL, TimeUnit.MINUTES);
            unlock(lockKey);
        }
        return shop;
    }


    //    封装缓存穿透
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
//        1、从Redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
//        3.存在,直接返回,反序列化，把JSON数据转化为对象
            return JSONUtil.toBean(shopJson, Shop.class);
        }
//        isNotBlank判断为空时也是false，这样的话就会向下运行了，会查询数据库了，所以要在这里添加一个条件,只要它是null就让它执行下面的查询数据库
        if (shopJson != null) {
            return null;
        }
//        4.不存在,根据id查询数据库
        Shop shop = getById(id);
        if (shop == null) {
//        5.不存在,返回false
//            使用缓存null值解决缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return null;
        }
//        6.存在,写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        返回店铺信息
        return shop;
    }


    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺的id不能为空呢~");
        }
//        1、更新数据库
        updateById(shop);
//        2、删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}

    /**
     * 由于Redis版本的问题距离查询被搁置了
     * @param typeId
     * @param current
     * @param x
     * @param y
     * @return

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
//        1、判断是否需要使用坐标查询
        if (x==null||y==null){
//         不需要坐标查询，按数据库查询
            Page<Shop> page = query().eq("type_id", typeId).page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
//        2、计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current*SystemConstants.DEFAULT_PAGE_SIZE;
//        3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(key, GeoReference.fromCoordinate(x, y), new Distance(5000),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().limit(end));
//        4.解析出id
        if (results==null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
//        没有下一页了，结束
        if (list.size()<from){
            return Result.ok(Collections.emptyList());
        }
//        4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());
//        4.2.获取店铺id
        list.stream().skip(from).forEach(result->{
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
//        4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
//        5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
//        6.返回
        return Result.ok(shops);
    }

}
*/