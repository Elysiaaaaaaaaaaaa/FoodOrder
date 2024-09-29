package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    public Result queryShopList(){
//        从redis中查询商铺类型,序列化JSON数据
        String shopTypeArray = stringRedisTemplate.opsForValue().get("shop-type");
//        把JSON数据反序列化成list数据
        List<ShopType> list = JSONUtil.toList(shopTypeArray, ShopType.class);
//        查询商铺类型是否为空
        if (!CollectionUtils.isEmpty(list)) {
//            不为空
            return Result.ok(list);
        }
//        为空
        List<ShopType> shopTypeInMysql = query().orderByAsc("sort").list();
        stringRedisTemplate.opsForValue().set("shop-type",JSONUtil.toJsonStr(shopTypeInMysql));
        return Result.ok(shopTypeInMysql);
    }
}
