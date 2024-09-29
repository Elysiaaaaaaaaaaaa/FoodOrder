package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
//        要想判断是否关注，首先需要我们获得用户Id
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
//        1、判断关注还是取关
        if (isFollow){
//        2、关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
//            判断是否保存成功
            if (isSuccess){
//                保存成功就存入Redis
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else{
//        3、取关，删除数据
//            delete from tb_follow where userId=? and followUserId=?
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
//            判断是否删除成功
            if (isSuccess){
//                删除成功就把它从Redis中删除
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
//        查询是否关注,不需要知道关注了谁，只需要知道有没有关注，所以只需要计算数量就可以了
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count>0);
    }
//实现查询共同关注，传入的id是被关注的用户的id
    @Override
    public Result followCommons(Long id) {
//        1、获取当前用户
        Long userId = UserHolder.getUser().getId();
        String user_key ="follows:"+userId;
//        2、求交集
        String follow_key="follows:"+id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(user_key, follow_key);
//        2.1、无交集的情况
        if (intersect==null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
//        3、解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
//        4、查询用户
        List<UserDTO> userDTOS = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

}
