---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by jiang.
--- DateTime: 2024/5/21 23:02
---
--参数列表
--1.1优惠券id
local voucherId = ARGV[1]
--1.2用户id
local userId = ARGV[2]
--1.3订单id
local orderId = ARGV[3]
--2.数据key
--2.1库存key
--在Lua脚本中，拼接字符串用 ..
local stockKey ="seckill:stock:".. voucherId
--2.2订单key
local orderKey = 'seckill:order:'.. voucherId

--3、脚本业务
--3.1判断库存是否充足 get stockKey
--redis做存或取都是String类型，String类型不能和整数进行比较，所以使用tonumber()把字符串变成int作比较
local stock = redis.call('get',stockKey)
if (not stock or tonumber(stock)<=0)then
    return 1
    --判断用户是否下单
end
if (redis.call('sismember',orderKey,userId)==1) then
--存在这个用户，说明这个用户重复下单
    return 2
end
--扣库存
redis.call('incrby',stockKey,-1)
--下单保存用户
redis.call('sadd',orderKey,userId)
-- 发送消息到队列中，XADD命令
--这里使用id而不使用orderId是考虑到这个本来就是为了创建订单的，我们查看订单实体类可以发现订单的id就是id
redis.call('xadd','stream.orders','*','userId',userId,'voucherId', voucherId,'id',orderId)
return 0
