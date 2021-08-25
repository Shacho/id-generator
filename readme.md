![image](https://zhxhash-blog.oss-cn-beijing.aliyuncs.com/%E4%B8%80%E7%A7%8D%E7%AE%80%E6%98%93%E4%BD%86%E8%80%83%E8%99%91%E5%85%A8%E9%9D%A2%E7%9A%84ID%E7%94%9F%E6%88%90%E5%99%A8%E6%80%9D%E8%80%83/logo.jpg)

分布式系统中，全局唯一 ID 的生成是一个老生常谈但是非常重要的话题。随着技术的不断成熟，大家的分布式全局唯一 ID 设计与生成方案趋向于趋势递增的 ID，这篇文章将结合我们系统中的 ID 针对实际业务场景以及性能存储和可读性的考量以及优缺点取舍，进行深入分析。本文并不是为了分析出最好的 ID 生成器，而是分析设计 ID 生成器的时候需要考虑哪些，如何设计出最适合自己业务的 ID 生成器。

> 项目地址：https://github.com/JoJoTec/id-generator

![image](https://zhxhash-blog.oss-cn-beijing.aliyuncs.com/%E4%B8%80%E7%A7%8D%E7%AE%80%E6%98%93%E4%BD%86%E8%80%83%E8%99%91%E5%85%A8%E9%9D%A2%E7%9A%84ID%E7%94%9F%E6%88%90%E5%99%A8%E6%80%9D%E8%80%83/1.%E6%88%91%E4%BB%AC%E7%9A%84%E5%85%A8%E5%B1%80%E5%94%AF%E4%B8%80%20ID%20%E8%AE%BE%E8%AE%A1.jpg)

首先，先放出我们的全局唯一 ID 结构：

![image](https://zhxhash-blog.oss-cn-beijing.aliyuncs.com/%E4%B8%80%E7%A7%8D%E7%AE%80%E6%98%93%E4%BD%86%E8%80%83%E8%99%91%E5%85%A8%E9%9D%A2%E7%9A%84ID%E7%94%9F%E6%88%90%E5%99%A8%E6%80%9D%E8%80%83/structure.png)


这个唯一 ID 生成器是放在每个微服务进程里面的插件这种架构，不是有那种唯一 ID 生成中心的架构：
- 开头是时间戳格式化之后的字符串，可以直接看出年月日时分秒以及毫秒。由于分散在不同进程里面，需要考虑不同微服务时间戳不同是否会产生相同 ID 的问题。
- 中间业务字段，最多 4 个字符。
- 最后是自增序列。这个自增序列通过 Redis 获取，同时做了分散压力优化以及集群 fallback 优化，后面会详细分析。

![image](https://zhxhash-blog.oss-cn-beijing.aliyuncs.com/%E4%B8%80%E7%A7%8D%E7%AE%80%E6%98%93%E4%BD%86%E8%80%83%E8%99%91%E5%85%A8%E9%9D%A2%E7%9A%84ID%E7%94%9F%E6%88%90%E5%99%A8%E6%80%9D%E8%80%83/2.%E5%94%AF%E4%B8%80%E6%80%A7%E4%B8%8E%E9%AB%98%E5%8F%AF%E7%94%A8%E6%80%A7%E6%80%9D%E8%80%83.jpg)

序列号的开头是时间戳格式化之后的字符串，由于分散在不同进程里面，不同进程当前时间可能会有差异，这个差异可能是毫秒或者秒级别的。所以，**要考虑 ID 中剩下的部分是否会产生相同的序列**。

自增序列由两部分组成，第一部分是 Bucket，后面是从 Redis 中获取的对应 Bucket 自增序列，获取自增序列的伪代码是：

```
1. 获取当前线程 ThreadLocal 的 position，position 初始值为一个随机数。
2. position += 1，之后对最大 Bucket 大小（即 2^8）取余，即对 2^8 - 1 取与运算，获取当前 Bucket。
   如果当前 Bucket 没有被断路，则执行做下一步，否则重复 2。
   如果所有 Bucket 都失败，则抛异常退出
3. redis 执行： incr sequence_num_key:当前Bucket值，拿到返回值 sequence
4. 如果 sequence 大于最大 Sequence 值，即 2^18， 对这个 Bucket 加锁（sequence_num_lock:当前Bucket值），
   更新 sequence_num_key:当前Bucket值 为 0，之后重复第 3 步。否则，返回这个 sequence
   
-- 如果 3，4 出现 Redis 相关异常，则将当前 Bucket 加入断路器，重复步骤 2
```

在这种算法下，即使每个实例时间戳可能有差异，只要在**最大差异时间内，同一业务不生成超过 Sequence 界限数量的实体，即可保证不会产生重复 ID**。

同时，我们设计了 Bucket，**这样在使用 Redis 集群的情况下，即使某些节点的 Redis 不可用，也不会影响我们生成 ID**。


![image](https://zhxhash-blog.oss-cn-beijing.aliyuncs.com/%E4%B8%80%E7%A7%8D%E7%AE%80%E6%98%93%E4%BD%86%E8%80%83%E8%99%91%E5%85%A8%E9%9D%A2%E7%9A%84ID%E7%94%9F%E6%88%90%E5%99%A8%E6%80%9D%E8%80%83/3.%E6%95%B0%E6%8D%AE%E5%BA%93%E4%B8%BB%E9%94%AE%E6%8F%92%E5%85%A5%E6%80%A7%E8%83%BD%E6%80%9D%E8%80%83.jpg)

当前 OLTP 业务离不开传统数据库，目前最流行的数据库是 MySQL，MySQL 中最流行的 OLTP 存储引擎是 InnoDB。考虑业务扩展与分布式数据库设计，InnoDB 的主键 ID 一般不采用自增 ID，而是通过全局 ID 生成器生成。这个 ID 对于 MySQL InnoDB 有哪些性能影响呢？我们通过将 BigInt 类型主键和我们这个字符串类型的主键进行对比分析。

首先，由于 B+ 树的索引特性，主键越是严格递增，插入性能越好。越是混乱无序，插入性能越差。这个原因，主要是 B+ 树设计中，如果值无序程度很高，数据被离散存储，造成 innodb 频繁的页分裂操作，严重降低插入性能。可以通过下面两个图的对比看出：

**插入有序**：

![image](https://zhxhash-blog.oss-cn-beijing.aliyuncs.com/%E4%B8%80%E7%A7%8D%E7%AE%80%E6%98%93%E4%BD%86%E8%80%83%E8%99%91%E5%85%A8%E9%9D%A2%E7%9A%84ID%E7%94%9F%E6%88%90%E5%99%A8%E6%80%9D%E8%80%83/b%2Btree_ordered.gif)

**插入无序**：

![image](https://zhxhash-blog.oss-cn-beijing.aliyuncs.com/%E4%B8%80%E7%A7%8D%E7%AE%80%E6%98%93%E4%BD%86%E8%80%83%E8%99%91%E5%85%A8%E9%9D%A2%E7%9A%84ID%E7%94%9F%E6%88%90%E5%99%A8%E6%80%9D%E8%80%83/b%2Btree_not_ordered.gif)


如果插入的主键 ID 是离散无序的，那么每次插入都有可能对于之前的 B+ 树子节点进行裂变修改，**那么在任一一段时间内，整个 B+ 树的每一个子分支都有可能被读取并修改，导致内存效率低下**。**如果主键是有序的（即新插入的 id 比之前的 id 要大），那么只有最新分支的子分支以及节点会被读取修改，这样从整体上提升了插入效率**。

我们设计的 ID，由于是当前时间戳开头的，从**趋势上是整体递增**的。**基本上能满足将插入要修改的 B+ 树节点控制在最新的 B+ 树分支上，防止树整体扫描以及修改**。

![image](https://zhxhash-blog.oss-cn-beijing.aliyuncs.com/%E4%B8%80%E7%A7%8D%E7%AE%80%E6%98%93%E4%BD%86%E8%80%83%E8%99%91%E5%85%A8%E9%9D%A2%E7%9A%84ID%E7%94%9F%E6%88%90%E5%99%A8%E6%80%9D%E8%80%83/4.%E6%95%B0%E6%8D%AE%E5%BA%93%E7%B4%A2%E5%BC%95%E6%80%A7%E8%83%BD%E6%80%9D%E8%80%83.jpg)

和 SnowFlake 算法生成的 long 类型数字，在数据库中即 bigint 对比：bigint，在 InnoDB 引擎行记录存储中，无论是哪种行格式，都占用 **8 字节**。我们的 ID，char类型，字符编码采用 **latin1**（**因为只有字母和数字**），占用 27 字节，大概是 bigint 的 3 倍多。
- MySQL 的主键 B+ 树，如果主键越大，那么单行占用空间越多，即 B+ 树的分支以及叶子节点都会占用更多空间，造成的后果是：MySQL 是按页加载文件到内存的，也是按页处理的。这样一页内，可以读取与操作的数据将会变少。**如果数据表字段只有一个主键，那么 MySQL 单页（不考虑各种头部，例如页头，行头，表头等等）能加载处理的行数， bigint 类型是我们这个主键的 3 倍多**。但是数据表一般不会只有主键字段，还会有很多其他字段，**其他字段占用空间越多，这个影响越小**。
- MySQL 的二级索引，叶子节点的值是主键，那么同样的，单页加载的叶子节点数量，bigint 类型是我们这个主键的 3 倍多。但是目前一般 MySQL 的配置，都是内存资源很大的，造成其实二级索引搜索主要的性能瓶颈并不在于此处，**这个 3 倍影响对于大部分查询可能就是小于毫秒级别的优化提升。相对于我们设计的这个主键带来的可读性以及便利性来说，是微不足道的**。

![image](https://zhxhash-blog.oss-cn-beijing.aliyuncs.com/%E4%B8%80%E7%A7%8D%E7%AE%80%E6%98%93%E4%BD%86%E8%80%83%E8%99%91%E5%85%A8%E9%9D%A2%E7%9A%84ID%E7%94%9F%E6%88%90%E5%99%A8%E6%80%9D%E8%80%83/5.%E6%95%B0%E6%8D%AE%E5%BA%93%E6%9F%A5%E8%AF%A2%E6%80%A7%E8%83%BD%E4%BC%98%E5%8C%96.jpg)

业务上，其实有很多需要按创建时间排序的场景。比如说查询一个用户今天的订单，并且按照创建时间倒序，那么 SQL 一般是：

```
## 查询数量，为了分页
select count(1) from t_order where user_id = "userid" and create_time > date(now());
## 之后查询具体信息
select * from t_order where user_id = "userid" and create_time > date(now()) order by create_time limit 0, 10;
```
订单表肯定会有 user_id 索引，但是随着业务增长，下单量越来越多导致这两个 SQL 越来越慢，这时我们就可以有两种选择：

1. 创建 user_id 和 create_time 的联合索引来减少扫描，但是大表额外增加索引会导致占用更多空间并且和现有索引重合有时候会导致 SQL 优化有误。
2. 直接使用我们的主键索引进行筛选:
```
select count(1) from t_order where user_id = "userid" and id > "210821";
select * from t_order where user_id = "userid" and id > "210821" order by id desc limit 0, 10;
```
但是需要注意的是，第二个 SQL 执行会比创建 user_id 和 create_time 的联合索引执行原来的 SQL 多一步 `Creating sort index` 即将命中的数据在内存中排序，如果命中量比较小，即大部分用户在当天的订单量都是几十几百这个级别的，那么基本没问题，这一步不会消耗很大。否则还是需要创建 user_id 和 create_time 的联合索引来减少扫描。

如果不涉及排序，**仅仅筛选的话**，这样做基本是没问题的。

![image](https://zhxhash-blog.oss-cn-beijing.aliyuncs.com/%E4%B8%80%E7%A7%8D%E7%AE%80%E6%98%93%E4%BD%86%E8%80%83%E8%99%91%E5%85%A8%E9%9D%A2%E7%9A%84ID%E7%94%9F%E6%88%90%E5%99%A8%E6%80%9D%E8%80%83/6.%E4%B8%9A%E5%8A%A1%E6%95%8F%E6%84%9F%E4%BF%A1%E6%81%AF.jpg)

我们不希望用户通过 ID 得知我们的业务体量，例如我现在下一单拿到 ID，之后再过一段时间再下一单拿到 ID，对比这两个 ID 就能得出这段时间内有多少单。

我们设计的这个 ID 完全没有这个问题，因为最后的序列号：
1. 所有业务共用同一套序列号，每种业务有 ID 产生的时候，就会造成 Bucket 里面的序列递增。
2. 序列号同一时刻可能不同线程使用的不同的 Bucket，并且结果是位操作，很难看出来那部分是序列号，那部分是 Bucket。

![image](https://zhxhash-blog.oss-cn-beijing.aliyuncs.com/%E4%B8%80%E7%A7%8D%E7%AE%80%E6%98%93%E4%BD%86%E8%80%83%E8%99%91%E5%85%A8%E9%9D%A2%E7%9A%84ID%E7%94%9F%E6%88%90%E5%99%A8%E6%80%9D%E8%80%83/7.%E5%8F%AF%E8%AF%BB%E6%80%A7.jpg)

从我们设计的 ID 上，可以直观的看出这个业务的实体，是在什么时刻创建出来的：
- 一般客服受理问题的时候，拿到 ID 就能看出来时间，直接去后台系统对应时间段调取用户相关操作记录即可。简化操作。
- 一般的业务有报警系统，一般报警信息中会包含 ID，从我们设计的 ID 上就能看出来创建时间，以及属于哪个业务。
- 日志一般会被采集到一起，所有微服务系统的日志都会汇入例如 ELK 这样的系统中，从搜索引擎中搜索出来的信息，从 ID 就能直观看出业务以及创建时间。

![image](https://zhxhash-blog.oss-cn-beijing.aliyuncs.com/%E4%B8%80%E7%A7%8D%E7%AE%80%E6%98%93%E4%BD%86%E8%80%83%E8%99%91%E5%85%A8%E9%9D%A2%E7%9A%84ID%E7%94%9F%E6%88%90%E5%99%A8%E6%80%9D%E8%80%83/8.%E6%80%A7%E8%83%BD%E6%B5%8B%E8%AF%95.jpg)

在给出的项目源码地址中的单元测试中，我们测试了通过 embedded-redis 启动一个本地 redis 的单线程，200 线程获取 ID 的性能，并且对比了只操作 redis，只获取序列以及获取 ID 的性能，我的破电脑结果如下：
```
单线程
BaseLine(only redis): 200000 in: 28018ms
Sequence generate: 200000 in: 28459ms
ID generate: 200000 in: 29055ms

200线程
BaseLine(only redis): 200000 in: 3450ms
Sequence generate: 200000 in: 3562ms
ID generate: 200000 in: 3610ms
```
> **微信搜索“我的编程喵”关注公众号，每日一刷，轻松提升技术，斩获各种offer**：

![](https://zhxhash-blog.oss-cn-beijing.aliyuncs.com/%E5%85%AC%E4%BC%97%E5%8F%B7QR.gif)