package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //判断手机号码是否有误，这里true是有误，根据方法不同选择返回不同的结果
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号码格式有误");
        }
        //生成随机验证码
        String code = RandomUtil.randomNumbers(6);
//        把验证码存放到Redis域里,对于一些固定的数据，可以把它们放到一个专门存放常量的类中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.debug("验证码为{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        /**
         * 自己写的，判断条件有点长
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())||!loginForm.getCode().equals(session.getAttribute("code"))){
            return Result.fail("手机号码或验证码有误");
        }*/
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        Object cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号码格式有误");
        }

        if(cacheCode==null||!code.equals(cacheCode.toString())){
            return Result.fail("验证码错误");
        }
        User user = query().eq("phone", phone).one();
        if(user==null){
            user=createUserWithPhone(phone);
        }
        /**
         * 保存用户信息到Redis中
         * 1.随机生成token，作为登录令牌,后面修改成了tokenKey
         * 2.将User对象转为Hash存储
         * 3.存储
         * 4.返回token
         */
        String token = UUID.randomUUID().toString();
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //把一个bean变成Map，便于下面的存储
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //设置了一个过期的时间
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
    }

    @Override
    public Result loginOut(LoginFormDTO loginForm,HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        String tokenKey = LOGIN_USER_KEY+token;
        String code = LOGIN_CODE_KEY+ loginForm.getPhone();
        stringRedisTemplate.delete(tokenKey);
        stringRedisTemplate.delete(code);
        UserHolder.removeUser();
        return Result.ok();
    }
//实现用户登录功能
    @Override
    public Result sign() {
//        1、获取当前登录用户
        Long userId = UserHolder.getUser().getId();
//        2、获取当前的时间
        LocalDateTime now = LocalDateTime.now();
//        格式化当前的日期
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
//        拼接用户的key
        String key = USER_SIGN_KEY+userId+keySuffix;
//        用来计算当前是这个月的第几天
        int dayOfMonth = now.getDayOfMonth();
//        用户签到一次就写入Redis，签到一次就在相应的位置置为1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    /**
     * 统计用户签到的总次数
     * @return
     */
    @Override
    public Result signCount() {
//        1、获取当前登录用户
        Long userId = UserHolder.getUser().getId();
//        2、获取当前的时间
        LocalDateTime now = LocalDateTime.now();
//        格式化当前的日期
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
//        拼接用户的key
        String key = USER_SIGN_KEY+userId+keySuffix;
//        用来计算当前是这个月的第几天
        int dayOfMonth = now.getDayOfMonth();
//        获取这个月到目前为止的签到记录，返回一个十进制数字
//        get后面加的是是否需要获取符号位，里面的参数是获取多少个符号位，valueAt是从哪里开始
        List<Long> results = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (results==null||results.isEmpty()){
            return Result.ok(0);
        }
        Long num = results.get(0);
        if (num==null||num==0){
            return Result.ok(0);
        }
//        循环遍历
//        让这个数字和1做与运算，得到数字的最后一个bit位
        int count = 0;
        while (true){
//        判断这个bit位是否为0
//        如果为0，说明未签到
            if ((num & 1)==0){
                break;
            } else {
                count++;
            }
        }
//        如果不为0，说明已经签到了，计数器加1
        num >>>= 1;
        return Result.ok(count);
    }

    /**
     * 根据手机号码创建用户
     * @param phone
     * @return
     */

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(4));
        save(user);
        return user;
    }

}
