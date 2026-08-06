---
name: coil-compose-cover-debug
description: "Use when diagnosing intermittent/missing image cover loading in Compose + Coil apps (covers randomly blank, only in release builds, works in debug, images fetch fine elsewhere). Triggers include: 封面间歇消失, 封面不显示, 图片偶尔为空, cover intermittently missing, blank image placeholder, image loading fails only in release. Teaches the Coil 3.x composition-layer failure modes (ConstraintsSizeResolver suspend, painter reuse, fetch storms) and the fix patterns."
license: MIT
---

# Coil Compose 封面间歇性消失排障指南

从 BoomingMusic 的真实案例总结(release-only 间歇性空封面,debug 正常,修了 19 轮才定位到组合层)。

## 核心教训

**先定位问题层,再修代码。** 用日志把管线切成三段:

| 层 | 证据 | 结论 |
|----|------|------|
| fetch 层 | 有 fetch 日志且成功 | 不是 fetcher 问题,别在这打补丁 |
| 组合层 | **无 fetch 日志 + 空封面** | painter 卡 Loading/Empty,请求从未执行到 fetcher |
| 解码层 | MediaImage error 日志 | decode/bitmap 问题 |

**判定技巧**:让 UI 组件在 Error 时打 `Log.w`(android.util.Log 不走 Coil Logger 过滤),fetcher 成功时打 `Log.d`。如果用户看到空封面但日志里**没有该封面的 fetch 行** → 100% 是组合层死锁,不是 fetch。

## 决策树

```
封面间歇性不显示
├─ 日志有 fetch 失败/异常 → fetch 层(fetcher 逻辑、TagLib、文件访问)
├─ 日志有 MediaImage error → 解码层(decode、bitmap 配置)
└─ 日志无对应 fetch + 无 error → 组合层死锁,检查:
    ├─ 同进程同一数据被反复 fetch(8 分钟 7 次)?→ memory cache 未命中 → LazyColumn 无 key + LiveData/Flow 高频重发射
    ├─ 同帧 2-3 个并发 fetch 同一 item?→ launchJob 不取消旧 job 的 fetch 风暴
    └─ item 处于 Loading 时触发 restart?→ ConstraintsSizeResolver.size() 挂起死锁(见下)
```

## Coil 3.x 组合层机制(反编译字节码确认)

- **`rememberAsyncImagePainter`**:painter 用无 key 的 remember,**跨重组复用同一 painter**;每次重组 `set_input`
- **`set_input`**:`AsyncImageModelEqualityDelegate.Default` 按字段比较(context/data/memoryCacheKey/diskCacheKey/sizeResolver/scale/precision)。**data 是 data class 时按字段比较** → 同数据不同实例会误判相等 → 不 restart → 残留旧状态
- **`restart()`**:`_input != null && isRemembered` 才 `launchJob()`;`onRemembered` 无条件 `launchJob()`
- **`launchJob()`**:`launchWithDeferredDispatch(scope)` 启动新 job,**从不取消旧 job** → 并发竞态,后完成的 updateState 覆盖
- **`ConstraintsSizeResolver.size()`**:**挂起直到 item 被测量**(`latestConstraints` 为 ZERO 时把 continuation 加入等待列表)。**只有挂载在布局中的 sizeResolver 才会被测量**
- **`updateRequest()`**:request 未定义 sizeResolver 时填 `SizeResolver.ORIGINAL`

## 致命陷阱:size() 挂起死锁

```kotlin
// ❌ 错误模式:Loading/Error 分支不挂载 sizeResolver
when (state) {
    is Loading -> MediaPlaceholder(modifier)          // 没有 .then(sizeResolver)!
    else -> Image(modifier.then(sizeResolver))        // 只有成功才挂载
}
```

状态为 Loading 时 sizeResolver 被移除布局 → 若此刻 `restart()`/`onRemembered()` 发起新请求 → 新请求的 `size()` **永远等不到测量 → 永久挂起 → 空封面且零 fetch 日志**(fetch 卡在 size() 解析之前)。

```kotlin
// ✅ 修复:所有分支挂载 sizeResolver
when (state) {
    is Loading -> MediaPlaceholder(modifier.then(sizeResolver))
    else -> Image(modifier.then(sizeResolver))
}
```

## 修复三件套(每层消除一类竞态)

1. **sizeResolver 全分支挂载** — 根治 size() 永久挂起
2. **painter 按模型 key 绑定**:`key(stableKey) { rememberAsyncImagePainter(...) }` — 换数据强制重建 painter,绕开 `set_input` 相等性误判
3. **LazyColumn items 加 key**:`itemsIndexed(list, key = { _, item -> item.id })` — 列表重排时同一 item 复用组合点、不同 item 干净重建,消灭 fetch 风暴和状态错位

## 其他已验证的坑

- **Room Flow + LiveData 组合**:`@Query` 返回 Flow 的表在每次 INSERT/UPDATE 都会重查询重发射。播放统计类列表在播放中高频更新 → 排序变化 → 无 key 的 itemsIndexed 位置复用 → 每次发射触发一波重新加载
- **release-only 排查方向**:R8 优化改变协程调度/GC 时序,竞态窗口放大;debug 时序宽松不触发。**不要据此怀疑 R8 规则**,先证明问题在哪个层
- **memory cache 命中判定**:同进程、间隔数秒以上,同一数据被再次 fetch = cache miss,优先怀疑 memory key 的组成部分不稳定(keyerKey 里的 lastModified 来自 DB 还是文件?)或尺寸解析不稳定
- **通知栏/ContentProvider 的请求**:`memoryCachePolicy(DISABLED)` 的请求每次都会 fetch,会产生"同一首歌重复 fetch"的假象,分析日志时先排除

## 诊断工具

反编译 Coil 源码 jar 确认机制(比读文档可靠):

```bash
# 从 gradle cache 找源 jar
# ~/.gradle/caches/.../coil-compose-core-runtime-*.jar
unzip -o coil-compose-core-runtime.jar -d /tmp/coilcompose/classes
javap -p -c coil3/compose/AsyncImagePainter.class    # set_input/restart/launchJob/onRemembered
javap -p -c coil3/compose/AsyncImageModelEqualityDelegate\$Companion\$Default\$1.class  # areEqual 字段比较
javap -p -c coil3/compose/ConstraintsSizeResolver.class  # size() 挂起逻辑
```
