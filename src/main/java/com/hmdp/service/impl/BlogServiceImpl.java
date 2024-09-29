package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jodd.util.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * 博客服务相关实现
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
//    查看获赞数最多的博客
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }
//    博客点赞
    @Override
    public Result likeBlog(Long id) {
//        1、获取登录用户
        Long userId = UserHolder.getUser().getId();
//        2、判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
//        3.1 如果没点赞，可以点赞
//        3.2 数据库点赞数+1
        if(score==null){
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
//        3.3 把用户信息存入到Redis，下次用户再想点赞时到Redis中查询点赞过没,使用时间戳作为score
            if (isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }
//        4、如果已经点过赞，再次点击取消点赞
        else {
//        4.1 数据库点赞数-1
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
//        4.2 把数据从Redis中移除去
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }
//实现查询点赞排行榜
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
//        1、查询top5的点赞用户，zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
//        如果top5为空就返回一个空集合
        if (top5==null||top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
//        2、解析出其中的用户id
//        先把top5转成流，再对流中的每个元素执行 Long.valueOf 方法，将每个元素从原始类型转换为 Long 类型，然后把流中的元素收集到列表中，生成一个新的列表对象
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
//        3、根据用户id查询用户
//        先在idStr中中查询含有"id"的选项，last用于拼接sql语句，list用于执行查询并返回结果列表的方法，
//        然后转为流，再对流中的每个用户把用户转为userDTO，保证用户信息安全，最后把stream流中的数据收集到一个新的List集合中，使查询结果返回一个List
        List<UserDTO> userDTOS = userService.query().in("id", ids).last("order by field(id," + idStr + ")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
//        4、返回
        return Result.ok(userDTOS);
    }

//    保存博客并推给笔记作者的粉丝
    @Override
    public Result saveBlog(Blog blog) {
//        1、获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
//        2、保存探店笔记
        boolean isSuccess = save(blog);
//        2.1、判断是否保存成功
        if (!isSuccess){
            return Result.fail("新建笔记失败");
        }

//        3、查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id=?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
//        4、推送笔记id给所有粉丝，对于这种推送所有粉丝的方案使用遍历
//        4.1推送视频到粉丝的收件箱中
        for (Follow follow : follows) {
//            4.2、获取粉丝id
            Long userId = follow.getUserId();
//            4.3、推送
            String key = FEED_KEY+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }

//        5、返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogByFollow(Long max, Integer offset) {
//        1、获取当前用户
        Long userId = UserHolder.getUser().getId();
//        2、查询Redis中的收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
//        如果集合为空就返回一个空集合
        if (typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }
//        创建一个存储用户id的集合
        List<Long> ids = new ArrayList<>(typedTuples.size());
//        3、解析数据：blogId，minTime(时间戳)，os:集合里分数等于minTime的所有元素的个数
        long minTime = 0;
        int os = 1;
//        3、分页查询
//        3.1、遍历集合中的元素，把元素的value值也就是id值存入到之前创建的那个集合中，
//        3.2、再把元素中的score值（时间戳）也取出来，用来和最小时间作比较，如果比最小时间小就赋值给最小时间，并且os重置为1
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            ids.add(Long.valueOf(typedTuple.getValue()));
            long time = typedTuple.getScore().longValue();
            if (time==minTime){
               os++;
            }else {
                minTime=time;
                os=1;
            }
        }
//        4、根据id查询blog
        List<Blog> blogs = listByIds(ids);
        String idStr = StrUtil.join(",", ids);
        for (Blog blog : blogs) {
//           4.1、查询blog有关用户
            queryBlogUser(blog);
//            4.2、查询blog是否被点赞
            isBlogLiked(blog);
        }
//        5、封装并返回
        ScrollResult result = new ScrollResult();
        result.setMinTime(minTime);
        result.setOffset(os);
        result.setList(blogs);
        return Result.ok(result);
    }


    //获取博客用户,最终获取的是一个博客

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryBlogById(Long id) {
//        1、查询用户Id
        Blog blog = getById(id);
        if (blog==null){
            return Result.fail("笔记不存在");
        }
//        2、查询blog相关的用户
        queryBlogUser(blog);
//        3、查询blog是否被点赞过
        isBlogLiked(blog);
        return Result.ok(blog);
    }
//判断博客是否被点赞过
    private void isBlogLiked(Blog blog) {
//        1、获取登录用户
//        防止没有登录账号导致查询博客时返回空指针异常
        UserDTO user = UserHolder.getUser();
        if (user==null){
            return;
        }
        Long userId = user.getId();

//        2、判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }

}
