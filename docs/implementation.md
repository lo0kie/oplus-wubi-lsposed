# 实现原理与架构

本文面向想读懂 / 改动本模块的开发者。各输入法宿主的反编译结论、混淆事实与真机验证清单见 [host-notes.md](host-notes.md)。

## 总体设计：为什么在 Java 层注入候选

官方路径（生成 `OWBD` 二进制词库 / iQQI 系 `*.bin`
词库）理论上最"原生"，但那些格式是输入法引擎的私有二进制词典，逆向成本高且极易随版本失效。所以本模块**不修改任何 native 文件、不修改 APK**，而是在 Java 层把自定义码表的候选插进引擎候选里：

1. **候选注入**：在引擎候选进入 Java 层的**公共汇合点**上替换列表，让候选栏与下游监听器拿到的就是我们的候选 —— 不需要改任何渲染逻辑。各输入法的具体汇合点见
   [host-notes.md](host-notes.md)。
2. **选中拦截**：注入的候选用**高位哨兵下标**（`900000 + i`）或**对象身份**（`IdentityHashMap`）标记，在"用户点了某个候选"的回调里认领自己注入的那一条，其余下标/对象原样放行，
   **引擎自身候选的行为完全不变**。
3. **提交接管**：空格选词、引擎自己的自动上屏发生在 native 引擎内部，拿不到我们的候选，因此再 Hook 引擎的"提交文本"出口：当引擎确实提交了文本、而我们刚为当前编码注入过候选时，把提交文本换成码表里的首选词。这是**纠正**而不是**发起**
   —— 模块不会为自定义码表主动制造一次四码上屏，固定行为、没有开关（四码为什么不自己补，见下一条）。
4. **满码顶屏**：引擎只对自己词库里的码自动上屏，自定义码表的码它根本不认识、永远不会顶屏。所以模块按输入法自身的顶屏开关，在「当前编码长度 = 码表最大码长 +
   1」时补一次顶屏（即打满码表最长码之后又按了一个字母）：把去掉最后一位的编码在码表里查一次，命中就把首选词上屏，并**把那个触发字母交回引擎**
   —— 它成为下一段输入的第一个字，不吞键、不多消耗（交回的路径各输入法不同：小布是 `Kernel.setInput`）。

## 目录结构

```
app/src/main/java/com/lookie/opluswubi/
├── WubiModule.kt                     # 模块入口（XposedModule + onPackageReady 安装 Hook）
├── Reflect.kt / XLog.kt              # 反射工具（带 Method/Field 缓存）、日志
├── table/
│   ├── CodeTable.kt                  # 码表解析 + 排序数组二分索引（十万级条目友好）
│   ├── TableStore.kt                 # 码表落盘 / 枚举 / 激活状态 / 索引缓存
│   ├── ModuleConfig.kt               # 模块配置（跨进程可读的纯文本文件 + 从 UI 进程同步）
│   └── ModuleSettingsProvider.kt     # 模块配置的只读 ContentProvider（UI 写、注入侧读，免 root）
├── ime/
│   ├── ImeAdapter.kt                 # 输入法适配器接口（多输入法隔离的抽象）
│   ├── ImeRegistry.kt                # 适配器注册表
│   ├── ImeEnv.kt                     # 被 Hook 进程的运行时环境（ClassLoader / Context / 进程名）
│   ├── oplus/
│   │   ├── OplusKeyboardAdapter.kt   # 小布输入法适配器
│   │   ├── OplusSettingsHook.kt      # 设置入口注入（三个注入点 + androidx 混淆规避）
│   │   ├── OplusFilePicker.kt        # SAF 文件选择
│   │   └── OplusCandidateHook.kt     # 候选注入 / 选中 / 提交接管
    ├── sogou/
    │   ├── SogouKeyboardAdapter.kt   # 搜狗输入法适配器
    │   ├── SogouSettingsHook.kt      # 「自定义五笔方案」页注入（无门槛、无动态显隐）
    │   ├── SogouCandidateHook.kt     # 「支持简词」的候选排序修正
    │   └── SogouClipboardHook.kt     # 解除剪贴板条数上限（常驻 Hook，开关实时门控）
    └── baidu/
        ├── BaiduKeyboardAdapter.kt   # 百度输入法适配器
        ├── BaiduSettingsHook.kt      # 「修复五笔方案」入口注入
        ├── BaiduWubiSchemeFix.kt     # 五笔方案对齐 / 下发
        └── BaiduClipboardHook.kt     # 剪贴板条数上限：裁剪值 + 落盘偏好 + 面板标题（三处）
└── ui/
    ├── SimpleDialog.kt               # 对话框封装（优先输入法自带样式，带 framework 兜底）
    ├── FilePickers.kt                # 文件选择契约（由输入法适配器提供实现）
    ├── EntryPanel.kt                 # 设置页底部那枚「自定义码表」胶囊（零 androidx 依赖）
    ├── ModulePage.kt                 # 模块自己画的整屏页面（码表管理；挂 decor + 消费返回键）
    └── TableManager.kt               # 码表管理弹窗（重命名 / 删除 / 导入）
```

## 多输入法隔离怎么做

1. `ImeRegistry` 按包名 + 版本区间选适配器，不匹配就完全不碰目标进程；
2. `TableStore.storageDirName` 由适配器指定，每个输入法各写自己 `filesDir` 下的目录，不同输入法之间零共享、零污染；
3. 配置存在**目标输入法自己的**私有目录（`ModuleConfig` 的 `config.properties`，位于
   `<filesDir>/<storageDirName>/`），所以每个输入法有一份独立配置；
4. 适配器自己决定「这份 Hook 装在哪几个进程」（见 `ImeEnv.processName`）；
5. 公共层（解析 / 存储 / 弹窗骨架 / 文件选择契约）不做任何输入法相关假设。

## 新增一个输入法适配器

```kotlin
object MyImeAdapter : ImeAdapter {
    override val id = "my_ime"
    override val packageName = "com.example.ime"
    override val displayName = "示例输入法"
    override fun supports(versionName: String, versionCode: Long) = versionCode in 1..9999
    override fun install(module: XposedModule, loader: ClassLoader, versionName: String, versionCode: Long) {
        TableStore.storageDirName = storageDirName()
        // 1) 把入口注入到它自己的设置页
        // 2) 提供 FilePicker 实现（若它的宿主 API 可用）
        // 3) 装候选 Hook
    }
}
// 然后加进 ImeRegistry.adapters
```

公共代码不需要改动。若发现「不改公共层就接不进来」，说明抽象漏了 —— 先说明理由再动 `ImeAdapter` 契约。

## ⚠️ 铁律：不许直接引用目标 App 的任何类

模块自己的 ClassLoader 是 LSPosed 的
`InMemoryDexFile`，**看不到目标 App 的 dex**。哪怕类名没被混淆（`androidx.preference.PreferenceGroup`
在目标 APK 里确实存在），在模块代码里写 `PreferenceGroup::class.java` 也会在运行时抛：

```
NoClassDefFoundError: Failed resolution of: Landroidx/preference/PreferenceGroup;
Caused by: ClassNotFoundException ... on path: DexPathList[[dex file "InMemoryDexFile[...]" ]]
```

所以一律「从目标 ClassLoader（`param.classLoader`）解析出
`Class`，之后全部按方法名反射调用」；需要实现被改名的监听接口时，用 `Proxy` 按运行时拿到的 `parameterTypes[0]`
动态实现。

`androidx.preference:preference` 的 compileOnly 依赖已删除，让「误加直接引用」直接变成编译错误。
`io.github.libxposed:api` 也必须是 `compileOnly`（运行时由 LSPosed 提供）。

## 模块配置为什么不用 SharedPreferences

有些输入法把「设置界面」和「输入法服务」放进**不同进程**（小布输入法则都在同一进程）。Android 的 SharedPreferences 在每个进程里各自缓存一份，**别的进程写进去的值本进程看不到**
—— 设置页里切换了方案，输入法进程读到的还是旧值，必须重启输入法才生效。

所以 `ModuleConfig` 改成「目标输入法私有目录下的一个纯文本文件」，每次访问按 `(mtime, size)`
判断是否需要重读，跨进程天然可见；写回时先写临时文件再 `rename`，避免读写两个进程同时操作时读到半截文件。

### 那模块自己的界面怎么改这些开关？

模块界面跑在**另一个应用**（`com.lookie.opluswubi`）里，进不去输入法的私有目录，
所以不能直接改上面那个文件。两条路：

- ~~`su` 写文件~~ —— 被否决：一个纯本地开关不该被 root 绑住（还要处理 KernelSU 把未授权应用的
  `su` 藏起来导致的假 `ENOENT`）；
- **主通道：广播推送**（`ModuleSettingsBus`）。模块界面写完自己的真值，立刻用
  `Intent(ACTION).setPackage(<输入法包名>)` 把 `key/value` 推给各输入法进程；注入侧注册了
  `ACTION` 的接收器，收到就 `ModuleConfig.applyPushed()` 落盘。
- **兜底通道：`ModuleSettingsProvider` 轮询**。注入侧每 5 秒 `query()` 一次模块的只读
  ContentProvider，把变化抄回本地 `config.properties`。

provider **对外只读**（`insert`/`update`/`delete` 一律拒绝），只暴露三四个开关键。
⚠️ 别用 `Binder.getCallingUid() == applicationInfo.uid` 来判断"是不是模块自己"——
真机实测 `adb shell content update` 能绕过它写进去。

### ⚠️ 为什么主通道是广播而不是 provider（2026-10-10 真机踩坑）

两个坑叠在一起，导致「改完开关不生效」看起来像模块坏了：

1. **同步起点错了（懒同步）**。早期 `ModuleConfig.sync()` 只挂在功能热路径上
   （小布=插剪贴板记录时、搜狗=候选重排时）。用户在模块界面改完开关后若不再触发那条路径，
   本地文件就永远停在旧值。→ 改为在 `ImeEnv.bindContext()` 里 `startPolling()`（幂等），
   只要注入进程拿到 Context 就把同步拉起来。
2. **`ContentProvider` 被 Android 11+ 包可见性挡住**。跨应用 `ContentResolver.query()`
   要求调用方 manifest 里有 `<queries><provider android:authorities="…"/></queries>`，
   而目标输入法的 manifest 我们改不了。真机日志：

   ```
   E ActivityThread: Failed to find provider info for com.lookie.opluswubi.settings
   配置同步[轮询]：provider={clipboard_unlimited=null, …} 本地={clipboard_unlimited=true}
   ```

   注意它报的是「**找不到 provider**」而不是「权限拒绝」，极具误导性（像极了"模块没装"）。
   实测规律：模块界面热着的时候 query 能成，**冷启动一律 null**。

   广播不受包可见性限制：`setPackage()` 显式指定目标包名即可投递，不需要调用方"看得见"对方。
   接收方注册时**必须**用 `Context.RECEIVER_EXPORTED`（`NOT_EXPORTED` 只收本应用广播，静默失败）。
   两条通道并存：广播负责"即时"，轮询负责"兜底/自愈"。

同步留痕一律**无条件打**（`配置同步[启动/轮询/手动]：provider=… 本地=… 待更新=…`），
成功 / 无变化 / 全查不到都要看得见 —— 只有"成功才打"会让失败路径一个字都不留，
日志看起来像"函数没被调用"。

## 文件选择怎么做

androidx 的 `ActivityResult*`
体系在混淆过的宿主里用不了（类名被混淆，既不能继承也不能实现），所以走最朴素、对混淆最免疫的一条路：

1. 从任意 Context 向上解包出 `Activity`，调 `startActivityForResult` 拉起 SAF 选择器（`ACTION_OPEN_DOCUMENT`）；
2. 提前 Hook 宿主设置页 Activity 的 `onActivityResult(int, int, Intent)` 收结果—— `Reflect.method`
   会自动沿父类链找到真正会被调用的那一份实现；
3. 请求码用不常见的值（小布用 `0x7A31`），避免与宿主自己的 requestCode 撞车。

好处：不需要任何存储权限，也不用在 manifest 里声明任何组件。

## 码表解析与排序

`CodeTable` 负责把 txt 解析成条目并按编码排序、建索引（十万级条目友好）； `TableStore`
负责落盘、枚举、激活状态与索引缓存。

- **列顺序自动识别**：`编码\t词`、`词\t编码`、逗号/空格分隔都能吃下；
- **去重**：解析结果规范化成 `编码\t词` 后算 MD5，与已有方案比对，内容一致直接启用已有方案；
- **前缀候选**：编码没打全时补前缀候选（默认最多 8 个，可调），避免只敲一个字母就刷屏；
- 十几万条（如虎码 17 万条）导入约 1~3 秒。

## 降级纪律（改代码时必须遵守）

- 每一步 Hook 都必须 `try/catch` 包住：任何一环失败只打日志、逐项降级， **绝不影响输入法自身启动**；
- 失败日志必须说清「断在哪一环」（哪个类 / 方法 / 字段没找到、拿不到 Context、版本不匹配），禁止静默 `return`；
- 未验证过的版本区间不得乱 Hook（`ImeAdapter.supports` 是第一道闸）；
- 找不到的类/方法就跳过该功能并在日志里说明，不要用反射硬凑一个「看起来能用」的行为。
