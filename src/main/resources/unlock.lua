--比较线程标示与锁中标示是否一致
--if (redis.call('get', KEYS[1]) == ARGV[1]) then
--    return redis.call('del',KEYS[1]);
--end
--return 0;


--Redisson实现释放锁
local key = KEYS[1]
local threadId = ARGV[1]
local releaseTime = ARGV[2]
--判断锁是否还是自己的
if (redis.call('hexists',key,threadId)==0) then
    --已经不是自己的了
    return nil;
end
--是自己的锁，则重入次数-1
local count = redis.call('hincrby',key,threadId,-1);
--判断锁计数是否还是0
if (count > 0) then
    redis.call('expire',key,releaseTime);
    return nil;
    --锁计数等于0，说明可以释放锁，直接删除
else
    redis.call('del',key);
    return nil;
end