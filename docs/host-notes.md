# 宿主逆向笔记与验证清单

本文记录各输入法的反编译结论、混淆事实、Hook 点与真机验证清单 —— 模块的每一个实现细节都以这里的结论为依据。整体架构与通用机制见
[implementation.md](implementation.md)。

## 小布输入法（`com.oplus.keyboard`）

目标 APK：`小布输入法_1.8.33.17-mkt.apk`（`versionCode=1518033`，`minSdk 33`，`targetSdk 34`）。

| 结论                    | 值                                                                                                                                                                                 |
| ----------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 包名                    | `com.oplus.keyboard`                                                                                                                                                               |
| Application             | `com.oplus.keyboard.ImeApp`                                                                                                                                                        |
| 五笔设置页              | `com.oplus.keyboard.settings.WubiInputFragment`                                                                                                                                    |
| 设置页宿主 Activity     | `com.oplus.keyboard.settings.SettingsActivity`（主进程，非独立进程）                                                                                                               |
| 设置页偏好树            | `PreferenceScreen` → `key_wubi_scheme`(五笔方案) / `key_wubi_vocabulary`(五笔词库) / `key_wubi_setting`(五笔设置)                                                                  |
| 「五笔词库」下已有条目  | `WubiVocabularyDownloadPreference`，key = `key_wubi_ext_vocabulary`（官方「五笔扩展词库」下载）                                                                                    |
| 官方扩展词库落盘位置    | `files/resources/wb_down.bin`（`OWBD` 二进制格式，由服务端下发）                                                                                                                   |
| 当前键盘类型            | 默认 SharedPreferences（`<pkg>_preferences`）的 `Key_KeyboardType`，五笔为 `wubi86` / `wubi98` / `wubi06`                                                                          |
| 候选链路                | `Engine.processKey()`(native) → `Engine.getCandidatesByRange()`(native) → **`Kernel.getProcessedLocalCandidateList()`**(Java) → `KernelInputProcessor.buildFinalCandidates()` → UI |
| 候选点击                | `Callback.s(index)` → `Kernel.selectCandidate(index, ...)` → native `Engine.selectCandidate(index)`（index 是**引擎内部下标**）                                                    |
| 空格选词 / 四码自动上屏 | 在 native 引擎内部完成，Java 层只能通过 `Kernel.getCommitText()` 拿到提交文本                                                                                                      |

### 三个必须知道的混淆事实

**1）资源名被混淆，但 `R` 字段名保留。** `res/-1.xml` 这种文件名是常态，`getIdentifier("wubi_input", ...)`
一定拿不到；要资源只能反射 `com.oplus.keyboard.R$xxx` 的字段名。

**2）androidx 的「类名」被混淆，但「成员名」全部保留。** 这是最容易踩的坑：

| 期望类                                         | 实际是否存在                                   |
| ---------------------------------------------- | ---------------------------------------------- |
| `androidx.preference.Preference`               | 存在                                           |
| `androidx.preference.PreferenceGroup`          | 存在                                           |
| `androidx.preference.PreferenceScreen`         | 存在                                           |
| `androidx.preference.PreferenceFragmentCompat` | **不存在**，被改名为 `androidx.preference.B`   |
| `androidx.preference.PreferenceManager`        | **不存在**，被改名为 `androidx.preference.L`   |
| `androidx.fragment.app.Fragment`               | **不存在**，被改名为 `androidx.fragment.app.E` |
| `androidx.activity.ComponentActivity`          | **不存在**，已改名                             |
| `Preference.OnPreferenceClickListener`         | **不存在**，被改名为 `androidx.preference.r`   |

（`Preference*` 三个类名能保留，是因为偏好 XML 里按类名引用它们。）

**3）模块自己的 ClassLoader 看不到目标 App 的 dex
—— 所以上面这张表只说明「能不能反编译到」，不说明「能不能直接引用」。** 2026-10-07 真机日志实测：报错点是
`OplusSettingsHook.hookAddPreference`，
`NoClassDefFoundError: Failed resolution of: Landroidx/preference/PreferenceGroup;`，
`Caused by: ClassNotFoundException ... on path: DexPathList[[dex file "InMemoryDexFile[...]"]]`。铁律与规避方式见
[implementation.md](implementation.md#-铁律不许直接引用目标-app-的任何类)。

### 注入成什么样子

**五笔方案**
分组（`key_wubi_scheme`）里：**一个自定义方案一个条目**，与原生方案同处一个单选列表，底部常驻一条「添加方案…」，方案列表就是设置页本身，不再另开页面：

```
COUIPreferenceCategory  key_wubi_scheme
├─ 五笔86 / 五笔98 / 五笔新世纪 …                     （原生单选条目）
├─ 虎码字词      171431 条                            ← 模块注入，克隆原生条目样式
├─ 五笔新世纪      48213 条                            ← 模块注入
└─ 添加方案…     导入 txt 码表，内容相同会自动去重       ← 模块注入，永远在最后
```

**五笔设置** 分组（`key_wubi_setting`）里注入一条「自定义方案设置」，放模块自己的开关。

- **点条目 = 选中该方案**（radio 直接切换，与原生方案一致，选中状态用 `setChecked` 同步）
- **长按条目 = 二级菜单**：设为当前方案 / 重命名 / 删除 / 查看详情
- **radio 互斥**：选中自定义方案后原生方案会被取消选中；选中原生方案则自动停用自定义方案（COUI 的单选逻辑只覆盖它自己的条目，我们注入的条目由模块自己兜互斥）
- 点「添加方案…」→ 直接拉起文件选择器；导入后自动设为当前方案
- 条目样式靠**克隆分组里剩下的原生条目类**得到（radio 样式天然一致）；长按靠 `setOnPreferenceLongClickListener` + Hook
  `Preference.onBindView(View)` 给条目视图直接挂 `OnLongClickListener` 两层保险

### 设置入口的三个注入点（幂等，任一处成功即可）

| #   | Hook 点                                 | 说明                                                                                                                                                                      |
| --- | --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `WubiInputFragment.onCreatePreferences` | 偏好树 inflate 完就返回，最早能看到 `PreferenceScreen` 的时机                                                                                                             |
| 2   | `WubiInputFragment.onViewCreated`       | 页面视图创建完，兜底                                                                                                                                                      |
| 3   | `PreferenceGroup.addPreference`         | **完全独立于 Fragment 生命周期**：看到锚点分组 `key_wubi_scheme` / `key_wubi_setting` 被挂进偏好树时，延后 200ms 再重建一次（等 XML 子条目加完，保证「添加方案…」在最后） |

每次重建都先按记录的 key 删掉上一轮注入的条目再加，所以不会重复；方案增删改后由 `OplusEntryRefresher.refresh()`
触发就地重建。每一步失败都会打日志说明原因。

### 候选注入与提交接管

1. 在 `Kernel.getProcessedLocalCandidateList(List, boolean)`
   里把自定义码表的候选插到引擎候选前面。选它的原因：它是三条候选路径（输入处理、翻页取词、端云融合）的**公共汇合点**，且进出都是纯 Java 的
   `LocalCandidate`，覆盖面最广、副作用最小。
2. 注入的候选使用高位哨兵下标（`900000 + i`），并在 `Kernel.selectCandidate(int, String, boolean)` 里拦截：哨兵下标 → 先
   `Kernel.clear(true, false)`
   让引擎退出 composing，再把码表里的词返回给调用方提交；其余下标原样放行，**引擎候选行为完全不变**。
3. 空格选词、引擎自己的自动上屏都发生在 native 里，拿不到我们的候选，因此再 Hook
   `Kernel.getCommitText()`：当引擎确实提交了文本、而我们刚刚为当前编码注入过候选时，把提交文本换成码表里的首选词。
   这是**纠正**而不是**发起** —— 模块只接管引擎已经决定要提交的那一次，固定行为、没有开关（见下面第 5 条）。
4. **`LocalCandidate` 的字段语义（2026-10-07 真机验证，靠字段转储确认）**：
   - 第 1 个参数 = `pinyin`（引擎填的是大写编码，如 `J`），第 2 个 = `text`（汉字，如
     `是`）——照抄引擎的填法，构造器映射由模块在真机上探测（日志 `LocalCandidate 构造器映射：…`）；
   - **`tip` 会落到 UI 模型的 `comment` 字段，而候选栏在 `comment` 非空时显示的就是 `comment`**：早先拿 `tip`
     放编码当「编码提示」，结果候选栏所有条目都显示成编码（但空格上屏的仍是正确候选）。所以 `tip`
     必须留空，原来的「候选显示编码提示」开关因此删掉了；
   - 自定义方案生效时**只返回自定义候选**（固定行为、没有开关），否则内置方案的词和「原样提交编码」那条都会混进候选栏。
5. **四码边界不由模块发起上屏；顶屏只保留「五码顶屏」**（2026-10-08 定）：
   - 引擎侧的四码自动上屏按**它自己的词库**判定，跟我们的码表没关系：`fmfm`
     在新世纪词库里 → 引擎顶屏 → 被 `getCommitText` 接管成 `天天`；反过来，**自定义码表的码引擎一个候选都不给、永远不会顶屏**
     （日志里能看到打完 4 码输入还在继续涨：`eqhxe` / `eqhxeq` / `eqhxeqhx`）。而它真按内置方案上屏时又会上错字 —— `aaaq`
     在自定义码表里根本没有条目，引擎却上屏了「工区」（见 `OplusSettingsHook.disableFourCodeSwitch` 的注释）。
   - 要在四码边界上屏，只能赶在引擎处理按键之前抢（拦按键 / 吞提交 / 把编码塞回引擎），那是在重写输入法状态机：真机上会闪屏、吞键，
     2026-10-07 16:08 还出过改引擎字段把状态机掰断、输入法被系统关闭的事故。**代价大于收益，所以不做** —— 模块不补四码唯一上屏，
     为阻止引擎四码自动上屏写的兼容代码一并删掉（见 `changelog/2026-10-07-小布自动上屏接管.md`）。
   - 四码边界改为**手动选词**（空格 / 点候选），同时把设置页那条「四码上屏」开关**置灰 + 摘要「自定义方案不支持」+ 落成 false**
     （`OplusSettingsHook.disableFourCodeSwitch`），跟「编码提示」同一套处理 —— 免得引擎按内置方案替自定义方案上屏。
   - 保留的**五码顶屏**（`planAutoCommit`，参考 RIME / 小狼毫的满码顶屏）：**当前编码长度 = 码表最大码长 + 1
     且引擎的 `getWubi5CodeAutoCommit` 为真**时，把「去掉最后一位」的编码在码表里精确查一次，命中就上屏首选词（`commitWord`：
     `InputConnection.commitText` + `Kernel.clear(true, false)`），并用 `Kernel.setInput(触发字母, true)`
     把那个触发字母交回引擎当下一段输入的首字（不吞键、不多消耗）。它跟四码的区别是触发条件**没有歧义** —— 「打满码表最长码之后又按了一键」
     就是要首选词，而四码边界上用户往往还要翻页选词，自动上屏等于把他的选择抢走。这条只看**宿主自己**那个五码开关。

### 回车：为什么必须靠按键区分，以及键码在哪拿

用户要求「回车时始终上屏**原始输入**，空格/顶屏才上屏码表首选词」。内置方案回车本来就是原始输入，是模块的提交接管把它改坏的 —— 但**回车和空格走的是同一条提交路**，真机调用链证实两者都汇到：

```
com.oplus.keyboard.kernel.KernelInput$KernelInputProcessor$processCommitKeyOnly$2.invokeSuspend
```

光看提交的文本（都是那串编码）分不出来，所以只能在按键入口标一个时间窗，提交接管见到窗口就放行。

**按键入口**：`KernelInput.processKeyCodeV2(Integer num, Integer num2, Integer num3, …)`，第一个参数在协程任务里叫
`$x11KeyCode`，就是键码。

⚠️ **它用的是安卓标准键码，不是 OPlus 那套 `com.oplus.keyboard.base.enums.KeyCode` 的 value**。真机键码采样（日志
`按键键码采样`）：`j=106 x=120`（ASCII 字母）、**回车 = 13**、退格 = 8、空格 = 32。早先按
`KeyCode.ENTER.getValue() == 66` 判定，一次都没命中 —— 这个坑记在这里。

另外 `Kernel.processKeyCode(int,int,String,A,p)` 不是按键入口（整段日志里一次都没被调用），排查时先走
`KernelInput.processKeyCodeV2`。

### 关于「LSPosed 是否需要 Activity」

查过 LSPosed 源码（`app/src/main/java/org/lsposed/manager/util/ModuleUtil.java`）：现代模块的判定只要求 APK 里存在
`META-INF/xposed/java_init.list`，**不要求任何 Activity**。所以本模块可以完全不声明组件（无桌面图标、无独立界面）。

### 为什么不去改 `wb_down.bin`

官方路径（生成 `OWBD`
二进制词库）理论上最"原生"，但该格式是 iQQI 引擎的私有二进制词典（头部为若干 4KB 对齐的段偏移表 + 段数据），逆向成本高且极易随版本失效。顺带记录已探明的信息，方便以后真要啃这个格式时少走弯路：

- 列表接口：`GET https://kbd-api-cn.heytapmobi.com/base/language-keyboard/list?type=wubi&pageIndex=0&pageSize=20` → 返回
  `downloadUrl = public/language-keyboard/wb_down.bin`（27,356,620 字节，md5 `3605351e88569236c0a4a3cbf947e850`）
- 实际下载地址：`https://kbd-api-cn.heytapmobi.com/base/download/` + `downloadUrl`
- 落盘位置：`files/resources/wb_down.bin`；内置五笔库是 `assets/resources/wb.bin`，**同一个格式** （文件头
  `OWBD`，之后是若干 `(段偏移, 段长度)` 的 4 字节小端对）

### 候选栏数据是怎么确认的（排查用）

目标 App 的候选数据源是它自己的类 `CandidatesLivaData`（不是 androidx LiveData，既没有
`setValue/postValue`，`androidx.lifecycle.Observer`
也拿不到），所以模块直接 Hook 它的「参数是集合的写入方法」和「返回集合的读取方法」，把内容打出来：

```
候选栏数据更新：共 3 个，其中自定义 2 个，前 3 个=['你好'#900000…, '您好'#900001…, 'jxbh'#0…]
```

- `自定义 0 个` → 候选没走到候选栏，注入点选错了；
- `自定义 N 个` → 数据已到候选栏，问题在渲染（显示字段、排序、过滤）。

### 导入的文件选择

androidx 的 `ActivityResult*`
体系在这里用不了（类名全被混淆，既不能继承也不能实现），所以走最朴素、对混淆最免疫的一条路（见 `OplusFilePicker`）：

1. 从任意 Context 向上解包出 `Activity`，调 `startActivityForResult` 拉起 SAF 选择器（`ACTION_OPEN_DOCUMENT`）；
2. 提前 Hook **`SettingsActivity`** 类链上解析出的 `onActivityResult(int, int, Intent)`
   收结果—— 这个类名没被混淆，是设置页唯一的宿主 Activity；`Reflect.method` 会自动沿父类链找到真正会被调用的那一份实现；
3. 请求码用不常见的 `0x7A31`，避免与目标 App 自己的 requestCode 撞车。

---

## 搜狗输入法（`com.sohu.inputmethod.sogou`）

按 `20.17.0`（`versionCode 2620`）反编译验证：

- 设置页是标准
  `androidx.preference.PreferenceFragmentCompat`（搜狗**没有混淆 androidx**，这点和小布完全不同，类名/方法名都是真名，反射代码可以直接按名字找）；
- 五笔设置页 = `com.sogou.imskit.feature.settings.preference.WubiSettingFragment`，由
  `addPreferencesFromResource(R.xml.xxx)` 膨胀（资源名被混淆成 `r/ai/aw.xml`）；
- 搜狗自己的条目控件：`com.sogou.lib.preference.SogouSwitchPreference`（继承
  `androidx.preference.SwitchPreferenceCompat`）、`SogouPreference`、`SogouCategory`、 `SogouDividerPreference`
  —— 注入时直接克隆这些类，样式天然一致；
- 设置 UI（`com.sohu.inputmethod.sogou.SogouIMESettings`）与输入法本体**同进程** （manifest 里没有
  `android:process`），配置读写不需要跨进程。

### 设置入口注入

五笔设置页（资源 `r/ai/aw.xml`）原生结构：

```
PreferenceScreen
├─ SogouCategory「基本设置」
│   ├─ SogouSwitchPreference  wubi_hybird_input_enabled        五笔拼音混输
│   └─ SogouPreference        wubi_setting_plan_manager        管理五笔方案
├─ SogouDividerPreference
├─ SogouCategory「候选排序」
│   ├─ wubi_setting_user_dict / wubi_setting_smart_make_word / wubi_setting_dynamic_fm
├─ SogouDividerPreference
├─ SogouCategory「特殊习惯」
│   ├─ wubi_show_code_enabled            编码逐键提示
│   ├─ wubi_input_pinyin_show_code       拼音提示五笔编码
│   ├─ wubi_input_four_code_commit       四码唯一时自动上屏
│   ├─ wubi_input_five_code_commit_first 第五码将首选上屏
│   ├─ wubi_setting_z_wildcard           Z键作为五笔通配按键
│   └─ ★ 支持简词（本模块注入）★          ← 追加在「特殊习惯」末尾
```

实现要点：

1. 开关**克隆搜狗自己的** `com.sogou.lib.preference.SogouSwitchPreference`，样式/动画与原生条目完全一致；
2. 开关值不写进搜狗的偏好体系（`setPersistent(false)`），改存模块自己的配置，避免污染搜狗的设置项、也避免被搜狗自己的
   `PreferenceDataStore` 覆盖；
3. 取值靠 Hook
   `TwoStatePreference.setChecked(boolean)`：用户点开关时 androidx 一定会走到这里（`TwoStatePreference.onClick` /
   `SwitchCompat` 的 `OnCheckedChangeListener` 两条路径都调它），我们只认自己的 key；
4. 注入点：Hook 搜狗所有设置页的基类 `com.sogou.lib.preference.base.AbstractSogouPreferenceFragment` 的
   `onCreatePreferences` / `onViewCreated`（搜狗**重写**了
   `onCreatePreferences`，ART 的方法 Hook 只拦截解析到该类的调用，子类重写后不会走父类实现，所以必须挂到基类上），外加
   `PreferenceGroup.addPreference` 作为不依赖 Fragment 生命周期的兜底注入点；
5. 条目补 `setIconSpaceReserved(false)`：原生条目在 XML 里都写了 `app:iconSpaceReserved=false`
   （不预留图标位），而我们是从代码 `new` 出来的、没有 AttributeSet，默认会多缩进一截。

### 简词排序修正

简词是虎码方案里的**简码词**（官方定义：一简词取词的第一码、二简词取两个字的首码、三简词取三个字的首码），属**可选项**，所以由开关控制。

数据源就是搜狗自己那份已导入的码表：

```
<搜狗 filesDir>/wubi/dict/custom_dict_1.txt   （导入的原始码表）
<搜狗 filesDir>/wubi/dict/custom_dict_1.zip   （txt 缺失时兜底）
```

目录常量来自 `com.sogou.lib.common.content.a`（`/data/data/<pkg>/files/` + `wubi/dict/`），文件名来自
`com.sogou.bu.basic.data.support.env.g`。

搜狗自己的导入规则（App 内《使用说明》原文）：

> 1、支持导入 TXT 格式的文本文件。2、文件编码格式支持：ANSI、UTF-8、UTF-16
> LE。3、每行包含 1 个编码及 1 个或多个候选，通过空格或 Tab 键分隔；编码长度：1-4；候选长度：1-20；
> **编码相同时，候选项排序依据导入时的顺序排列**；候选项仅支持中文字词；不符合规则会直接舍弃整行。

最后一条就是「修正排序」的依据：**码表里的顺序才是权威顺序**。

要修的是：搜狗引擎给出的候选排序被它的词频 / 动态调频带偏，简词被压在后面（打 `u`
首选是「工作」，应该首选「的」）。所以这里做的事是**只调序、不造词**：在候选里挑出「在码表里排得最靠前」的那个，挪到首位。

Hook 点 `CandsInfo.H(boolean)` —— 反编译确认它是候选组装的收口：

```java
public final void H(boolean z) {
    M(true);
    mIMEInterface.appendCandidateWords(this.b, this.a, this.g, z);   // 组装候选
    d(); h(); ...
}
```

`CandsInfo.a` / `CandsInfo.b`
是两个**并行的 ArrayList**（词 / 码），所以只要找到目标词在其中一个里的下标，两个列表一起搬到 0 位即可。当前编码从
`IMEInterface.getUnCommittedText(StringBuilder)` 取。注意：列表元素不一定就是 `String`（真机实测一个列表是词、另一个是
`...engine.base.model.d` 对象），所以一律按 `toString()` 比对。

⚠️
**只搬列表是不够的（2026-10-07 真机）**：搬完之后**界面**对了（首位显示「的」），但**空格 / 点候选上屏的仍是「工作」**。也就是说：那份列表只是**给界面看的**，引擎自己那份顺序没动 —— 提交时引擎按**它的**下标把词交出来。（列表元素的真实类型也从 dex 里核对了：`CandsInfo.e(I)`
返回 `b` 列表第 `i` 个元素并 cast 成 `CharSequence`； `CandsInfo.n(I)` / `g(I)` 读的是 `a` 列表里的
`engine.base.model.d`。两个列表用同一个下标，`h` 是分页偏移。）

所以还要在**提交口**做一次置换改写：

| 项       | 值                                                                                                                                                                             |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 提交口   | `com.sogou.imskit.core.input.inputconnection.CachedInputConnection.commitText(CharSequence, int)`（**类名没被混淆**，`BaseInputLogic` 里有 22 处调用它，候选选取那条也在其中） |
| 改写规则 | 置顶时记下「引擎顺序 `engine[]` / 界面顺序 `shown[]`」；提交文本命中 `engine[i]` 且 `shown[i] != engine[i]` 时换成 `shown[i]`                                                  |
| 时效     | 只认置顶后 8 秒内、且**编码没变**的提交（编码一变就丢掉置换记录），避免影响别的输入                                                                                            |

### 码表解析必须在后台，不能在候选帧里（2026-10-08）

三份 `custom_dict_*.txt` 是**全量解析并合并**的（`MAX_CUSTOM_DICTS = 3`，1~3 号累进同一份 map，
跟「当前方案是哪一个」无关），所以开销完全等于用户导进搜狗的那几份码表之和。真机实测
（188KB + 485KB + 2.78MB，共 221,380 行）：

```
12:07:18.610 读到 custom_dict_1.txt（188230 字节）
12:07:18.650 custom_dict_1.txt 解析：14643 行          +40ms
12:07:18.651 读到 custom_dict_2.txt（484540 字节）
12:07:18.717 custom_dict_2.txt 解析：36791 行          +66ms
12:07:18.718 读到 custom_dict_3.txt（2781934 字节）
12:07:19.044 custom_dict_3.txt 解析：169946 行         +326ms
12:07:19.044 已加载自定义五笔方案 → 简词 13483 个编码
```

**434ms**，而这段原本是**同步**跑在候选帧里的（`CandsInfo.H` → `reorder` → `dictFor`）——
用户报的「强停输入法后第一次敲键要等 1 秒左右」就是它。冷进程里更慢：另一次实测同一批文件
单个要 64ms / 121ms，是热态的两倍（进程刚起来、ART 还是冷的）。

规矩：**输入路径只读缓存，解析只在后台线程做**（`loadAsync` / `load`：单飞 + 解析完整体换缓存），
预热时机在 `Application.onCreate` —— 每个进程里最早拿到 Context 的地方，用户真正敲键前基本已经跑完。
代价是极早期的一两帧可能拿不到码表、简词不置顶（比卡 1 秒划算）。

---

## 百度输入法（`com.baidu.input`）

反编译对象：**百度输入法 13.3.16.2**（`versionCode=1177`，`minSdk 23` / `targetSdk 34`）。

百度**自带**自定义五笔方案（设置 → 五笔输入 → 管理五笔方案），所以这个适配器**不注入任何入口、不接管码表管理**，只修它自己的一个时序 bug。

### 反编译结论

| 结论             | 值                                                                                                                                 |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| 包名             | `com.baidu.input`                                                                                                                  |
| 内置五笔词库     | `assets/dict/wb486.bin` / `wb498.bin` / `wb4new.bin`（86 / 98 / 新世纪）                                                           |
| 五笔引擎库       | `assets/libwbsafeedit*`（与搜狗同源，说明两家的五笔引擎来自同一个供应商）                                                          |
| 方案索引落盘 key | `pref_key_wb_mode_new`：`0=86五笔` / `1=98五笔` / `2=新世纪` / `3=自定义`                                                          |
| 索引合法区间     | `0..3`，由 `com.baidu.input.util.WbPrefUtilKt$rangePredict$1` 判定，越界一律回落成 `0`                                             |
| 方案读写封装     | `com.baidu.input.util.WbPrefUtilKt`（`b()` 读、`d(int)` 写）、设置页条目 `…pref.WbPref`                                            |
| 偏好表           | `com.baidu.input.manager.PreferenceManager.b`（`IPreference`，键就是上面那个字符串）                                               |
| IME 侧缓存       | `com.baidu.input.ime.ImePref.a0`（`PUBLIC STATIC int`，**非 final、可写**）                                                        |
| 核心配置下发     | `com.baidu.input.ime.newcore.operator.CoreConfigHandler2.d()`（整包刷进 `CoreConfig`）                                             |
| 单键下发方案     | `CoreConfigHandler2.c(int)`：`ImeCoreManager.e().e(ConfigKey.p0, 映射后的 id)`                                                     |
| 核心处理器单例   | `com.baidu.input.pub.Global.u()` → `ICoreConfigHandler`（实际是 `CoreConfigHandler2`）                                             |
| 设置条目基类     | `com.baidu.input.pref.ImePreference extends android.preference.Preference`（**framework 的 `android.preference`，不是 androidx**） |
| 方案条目拦截器   | `com.baidu.input.pref.WbPref`（实现 `settings.di.IPrefCustomInterceptor`），由 `WbPrefFactory.b(key)` 按 key 命中                  |
| 输入法服务       | `com.baidu.input.ImeService`（`onStartInputViewInternal(EditorInfo, boolean)`）                                                    |

`CoreConfigHandler2.d()` 里把方案索引映射成引擎方案 id 再下发：

```java
int v4 = ImePref.a0;          // 0/1/2/3
if      (v4 == 0) v4 = 1;     // 86 五笔
else if (v4 == 1) v4 = 0;     // 98 五笔
else if (v4 == 2) v4 = 4;     // 新世纪
else if (v4 == 3) v4 = 3;     // 自定义方案
else              v4 = -1;    // 非法 → 不下发
if (v4 != -1) coreConfig.set(ConfigKey.p0, v4);
```

### Bug 与修法：切过来时自定义方案不生效

**现象**：从其它输入法切到百度时，已启用的自定义方案不生效（用的是内置方案）；手动切一次「拼音 → 五笔」才恢复。

**根因（真机日志 2026-10-07 修正过一次）**：方案值本身**一直是对的** —— 日志里 `ImePref.a0=3`、
`pref_key_wb_mode_new=3`，`d()` 那行打的是「五笔方案已是 3，无需纠正」，配置从头到尾没错过。问题在**下发时机**：

`CoreConfig.e(int,int)`（单键）和 `CoreConfig.c(MultiCoreConfigurations)`（整包）的**第一句都是**：

```java
if (!IptCoreInterface.get().isCoreOpened()) return;   // 核心没打开 → 直接 return，不抛异常、也不缓存
IptCoreInterface.get().setInt(key, value);
```

值**不缓存**，核心没打开就等于白写。而 `ImePref.i()` → `CoreConfigHandler2.d()`
是在核心起来**之前**跑的（真机日志：`[onStartInputViewInternal] … isCoreOpened=false`），方案就这么被丢掉，之后再没人补推。手动切一次拼音 → 五笔之所以有效，就是它让
`i()` → `d()` 在核心已打开时又跑了一遍。

> 早先的猜测（`a0` 异步加载、初始化时还是默认值 0）**已被真机日志证伪**：`a0` 从来都是 3。

**真机定论（2026-10-07，修好的那一版）**：核心**每次打开时 native 里的方案键都是 1（86 五笔），而且不跨会话保留**
—— 同一个进程里连续三次 `openCore` 读回来都是
`82=1，84=0`。引擎是在**开核心那一刻**读方案的，所以任何「晚一点再补推」都是马后炮（500ms 后的补推已经赶不上）。手动切走再切回之所以偶尔有效，也是因为重新开核心时那次时序恰好赶上了。

**主修法**：挂 `IptCoreInterface.openCore(Context, String, PackageInfo, int)`（`ImeCoreManager.h()`
调它，是**最早能拿到已打开核心**的时机），`chain.proceed()` 返回 true 后**立刻**执行 `ImePref.a0 = WbPrefUtilKt.b()` →
`((CoreConfigHandler2) Global.u()).c(a0)`，赶在引擎用它之前把方案写进去。顺带把 native 的 `82` / `84`
读回来打日志，方便版本变化时对照。

**辅修法（保留）**：`ImeService.onStartInputViewInternal`
起一段补推：每 500ms 执行一次同样的下发，最多 20 次，`isCoreOpened()` 变 true 就停。

⚠️ 关键是**每个 tick 都真推一次**，而不是「等 `isCoreOpened()` 变 true 再推」。真机日志（2026-10-07
13:02）里出现过键盘已经在画、这个标志却仍是 false 的情况：只等标志的话，补推会一直不落地。反正核心没打开时 `setInt`
是静默 no-op，推了不亏。

核心确认打开后，再补一次 **`ImePref.i(true)`**
—— 那是用户「手动切一次拼音 → 五笔」时真正跑的方法（重读全量偏好，末尾自己调 `CoreConfigHandler2.d()`
下发整包）。真机日志（2026-10-07 13:05）证明单键 `c(3)` 在 `isCoreOpened=true`
时确实到了 native，但方案没换，所以把整包这条路也补上。每个进程只做一次。

再往前一步：`i(true)` 之前先按百度自己的写法**写回偏好** —— `WbPrefUtilKt.d(int)` （clamp 到 `0..3` +
`PreferenceManager.b.e(key, v).apply()`）。模块此前只改静态字段
`ImePref.a0`，从不写偏好，也就不会触发百度自己的偏好变更监听；而「方案真正生效」很可能挂在那个监听上。两步都在
`reloadPrefs()` 里，每个进程只做一次。

### 排查时用过、已摘除的临时跟踪器

排查过程中临时挂过 `CoreConfig.e(int,int)` + `CoreConfig.c(MultiCoreConfigurations)`
（通往 native 的唯二入口，`IptCoreInterface.setInt`
只有这两个调用方），把每次写进 native 的键值打出来。它证明了两件事：`82=3`
确实到了 native、且**没有任何写入把它覆盖回 0**——所以「配置值不对」这条线被排除，真正的变量只剩「核心什么时候读它」。**定位到根因后已删除**（每个会话 480 多行日志，太吵）。以后再遇到类似的「配置写了不生效」，值得第一时间把它加回来。

### 核心打开那一刻：读回 native 的方案键

`IptCoreInterface.openCore(Context, String, PackageInfo, int)`（`ImeCoreManager.h()` 调它）是**最早能拿到已打开核心**
的时机，所以除了在那里下发一次，还会读回 native 的值：

```
[openCore] 核心已打开：native 里的方案键 82=3，84=3
```

这条回答最后一个未知量：核心刚打开时 native 里到底是不是 3。

- 是 3 却仍用内置方案 → 82 不是决定项，得往候选链路查；
- 是 0（或别的）→ native 不保留我们的写入，方案必须在核心打开**之前**就落进去。

### 每次切入都会打一条不去重的状态行

```
[onStartInputViewInternal] 切入：持久化方案=3 a0=3 isCoreOpened=false
```

专门为了对照「切进来（坏）→ 切走再切回（好）」两次切入的状态差 —— 其余日志都做了去重，只有这一条每次都打。

`CoreConfigHandler2.d()` 那条 Hook 保留（对齐 `a0`，防住真正的脏值），但它不是主修法。

**修法（手动入口）**：在五笔设置页注入一个开关「修复五笔方案」，默认打开；关掉则上面两条都不做。从关切到开时会立刻执行一次
`applyNow()`（核心没打开则排队等它起来）。开关值存模块自己的 `ModuleConfig` （key
`baidu_fix_wb_scheme`），不写进百度的偏好体系。

### 为什么重选同一个方案没用：`WbPref$1.onClick` 的短路

百度自己的「五笔方案」对话框回调（`com.baidu.input.pref.WbPref$1.onClick`）是这样的：

```java
if (WbPref.b != which) {                 // ← 只在「选中项真的变了」时才动手
    ImePref.a0 = WbPrefUtilKt.d(which);  // d(int) = 收敛到 0..3 并落盘
    ((CoreConfigHandler2) Global.u()).c(ImePref.a0);   // 下发（代码里连着调了两次）
    WbPref.b = which;
    ((CoreConfigHandler2) Global.u()).c(ImePref.a0);
    if (Global.z0 != null) Global.z0.b((short) 422);
}
// 之后弹一个「已切换」提示对话框
```

而 `WbPref.b` 字段是构造时由 `WbPref.f(Preference)` 从 `WbPrefUtilKt.b()`
读来的，**永远等于持久化方案**。所以「重新选中当前方案」是空操作，用户只能切到别的方案再切回来 —— 这正是「必须手动切一次拼音 → 五笔」的由来。

注意 `c(int)` 与 `d()` 的映射表**不一样**：`c()` 里 `1 → 2`，`d()` 里
`1 → 0`（98 五笔）。两者各自都是百度自己的代码，照抄即可，不要互相「纠正」。

### 设置入口怎么注入

百度设置页每个条目都是 `ImePreference extends android.preference.Preference`，构造期会跑 `ImePreference.a()`：

```
ImePreference.<init>(Context…)
  └─ ImePreference.a()                            // PUBLIC FINAL
       ├─ key = getKey()
       ├─ 在 SettingsComponent.f.b() 里找 b(key) == true 的 IPrefCustomInterceptorFactory
       ├─ this.a = factory.a()                    // 本页「五笔方案」命中的是 WbPrefFactory
       └─ this.a.f(this)                          // ← WbPref.f(Preference)
```

**主注入点**：`android.preference.PreferenceGroup.addPreference(Preference)`（framework 方法）。百度设置页的条目是逐条
`addPreference`
进分组的，所以「五笔方案」被加进去的那一刻父分组和 Context 都齐了，可以**同步**把我们的条目塞进同一个分组 —— 此刻页面还在装配、ListView 还没绑定，条目是**跟页面一起出现**的。

**兜底注入点**：`WbPref.f(Preference)`。它只在「五笔方案」条目**构造期**被调一次（`ImePreference.a()`
由基类构造函数调用，不受子类重写影响），但那一刻条目还没挂进分组，只能延迟轮询
`getParent()`。⚠️ 早先只用了这一个注入点，观感就是「设置页加载完之后才冒出来一条」—— 主注入点就是为了消掉这个延迟。

注入的条目克隆百度自己的
**`com.baidu.input.pref.ImeCheckBoxPreference`**（`extends android.preference.CheckBoxPreference`，构造函数
`(Context)`），勾选框、字体（`onBindView` 里的 `PrefUtil.a`）与原生开关条目一致。

⚠️ 两个坑：

1. **别沿用锚点的 `layoutResource`**。百度资源里根本没有自定义的设置行布局（`res/layout`
   搜不到 preference/item 行布局），它那些带勾选的条目就是 `ImeCheckBoxPreference` 的默认样子。早先版本用普通
   `ImePreference` 克隆锚点布局，结果页面上只有一行标题、没有勾选框 —— 因为普通 `Preference` 压根不带
   `preference_widget_checkbox`。
2. **不要在监听器里按 `isChecked()` 推新值**。真机实测：勾着点一下，监听器拿到的 `isChecked()`
   已经是翻转后的值（早先按「监听器先于翻转」写，结果勾着点一下还提示「已重新应用」）。正确做法是直接翻转模块自己存的值（`!ModuleConfig.baiduFixEnabled(ctx)`），与时序无关；视觉上的勾选由框架的
   `setChecked()` → `notifyChanged()` 完成，我们不用手动设。

另外 Hook `ImeService.onStartInputViewInternal` 只做诊断：打印「持久化方案 vs
`ImePref.a0`」，用来判断根因是否真在这条链上。

### 已知限制（百度输入法）

- **待真机验证**：基于 `13.3.16.2` 的静态分析实现，尚未上机跑过。
- **只修时序，不改行为**：不接管码表管理，也不动它的候选排序；自定义方案里有哪些词、排序如何，仍由百度自己决定。「修复五笔方案」也只是把当前方案重下发一遍，改不了方案本身。
- **设置页与输入法必须同进程**：开关从关切到开时执行的是
  `Global.u().c(...)`，只有在设置页与引擎同进程时才有意义。百度自己的
  `SettingsActivityLifecycleCallbacks.onActivityCreated` 就在设置页里直接调 `ImePref.i()` /
  `CoreConfigHandler2.d()`，说明同进程；若将来拆进程，这条要重新确认。
- **版本敏感**：`ImePref.a0` / `CoreConfigHandler2.d`、`c` / `PreferenceManager.b` / `Global.u` / `WbPref.f`
  都是 R8 混淆后的短名，换大版本可能失效（失效时只打日志、不影响输入法本身）。

---

## 剪贴板历史条数上限（四个输入法，2026-10-07）

四个输入法的上限各自藏在不同层，找法和改法都不一样。

| 输入法 | 上限 | 位置                                                                                                           | 改法                                                                              |
| ------ | ---- | -------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| 小布   | 500  | `com.oplus.keyboard.db.dao.k`（Room 合成 lambda）case 2 的 `count() == 500`                                    | 挂「取最旧一条」查询 `db.dao.m` 的 case 1 让它返回 null；**面板文案**另改（见下） |
| 百度   | 300  | `ClipboardConfig.c()` **读偏好表** `clipboard.config.max_query_count`；界面也读它                              | getter 返回 100000 **且**把偏好表写成 100000                                      |
| 搜狗   | 500  | `com.sogou.clipboard.repository.manager.a.c()`（KV `clipboard_settings_mmkv` 的 `clipboard_max_item_count`）   | 挂 getter，返回 100000                                                            |

⚠️ **界面显示的值和「裁剪用的值」可能不是同一个来源，改之前先确认**（用户要求界面显示真实上限 100000）：

- **搜狗**：界面和裁剪读的是同一个 getter `c()` → 挂 getter 两边一起变，界面显示 100000 ✅；
- **百度**：`c()`
  读的是**偏好表**里的值，而面板标题上的 300 是**第三份**常量（`com.baidu.input.ime.front.ClipboardPanelListView` 里
  `String.format(getString(0x7f1203cb), list.size(), 300)`，字面量，既不读偏好表也不调 `c()`）。
  **但不用去改那个字面量**：界面在**启动时**读偏好表并缓存，所以只要把偏好表写成 100000，下次进面板就显示
  `剪贴板(N/100000)` —— 真机已确认（写完偏好那次仍显示 300，重启后才变）。写法见
  `ClipboardConfig.a(int,boolean)`：`PreferenceManager.c.h(value, key)` （`h` 是 `IPreference`
  的「按字符串键写 int」）。

  排查时曾挂过三层文本改写（`String.format` / `TextView.setText` /
  `ImeTextView.setText`）想直接改文案，真机日志里**一次都没命中**
  —— 证明显示值确实走偏好表。三层已删除（`TextView.setText` 还在热路径上，留着纯属负担）。

**教训**：同一个「上限」在输入法里往往有好几份互不相干的副本（DB 裁剪阈值、偏好表、UI 字面量），改之前先把**界面上那个数字从哪来**找清楚，否则会出现「功能放开了、界面还写着旧值」。

### 为什么小布不是「把 500 改掉」

`db/dao/C0829k.java:44` 是：

```java
if (count() == 500 && 该条不存在 && (oldest = oldestOne()) != null) delete(oldest);
```

- 改**计数**（`db/dao/j` 的 case 1）要伪造 `SELECT COUNT(*)`，风险是别的调用方也读它；
- 改**删除分支**（`C0829k` 的 case 1）会连用户手动删除一起堵掉 —— `ClipboardManager$deleteClipboardData$1`
  删单条走的也是同一个 case 1；
- 改**「取最旧一条」**（`db/dao/m` 的 case 1）最干净：全仓库只有 `C0829k.java:44`
  一处调用它，返回 null 就让裁剪条件不成立。跳过 lambda 体也不会破坏事务（begin/end 由 `androidx.room.util.a.k(...)`
  负责）。

### 小布：面板文案里的 500 是**另一份**常量

`ClipBoardView` 的 `tvCount` 直接写死：

```java
tvCount.setText(context.getString(R.string.clip_length, 当前条数, 500));
```

这个 500 和 DB 层 `C0829k` 里那个裁剪阈值是两份互不相干的常量，所以掐掉裁剪后**文案不会自己变**。拿不到
`R.string.clip_length` 的资源 id（内部类名被混淆、R 字段被内联），所以在**渲染结果**上认：挂
`Resources.getString(int, Object…)`，只重写含 `/500` 的文案。

### 顺带记一个坑：小布的分页不是上限

`ClipboardManager`（混淆名 `i`）里那串 `new C0323m0(20, 0, 20, 50, false)` 看着像 `PagingConfig(…, maxSize = 50)`，其实
`androidx.paging.m0` 的构造里 `maxSize` 写死 `Integer.MAX_VALUE`，那个 `50`
是 Kotlin 默认参数的**掩码位**。按「分页上限」去改会白改一轮 —— 真正的裁剪在 DB 层。

## 模块自己的界面（`ui/MainActivity`）

模块原先**没有任何组件**（没有桌面图标、全部功能都注入到输入法设置页里）。2026-10-07 按要求加了一个 launcher
Activity，用 **Compose +
Miuix**（`top.yukonga.miuix.kmp:miuix-android`，HyperOS 那套观感）写，两件事：跳到注入页 / 强停输入法（都走 root，见下文「界面上的动作走 root」）。

几条踩过的坑：

| 现象                                                        | 原因                                                                                                                                                                                                                 | 做法                                                                                                                                                                                                                                                                 |
| ----------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 四个输入法全显示「未安装」                                  | Android 11 起**包可见性**收紧，没声明 `<queries>` 时 `getPackageInfo` / `getLaunchIntentForPackage` 直接抛 `NameNotFoundException`                                                                                   | manifest 里把 4 个输入法包名 + LSPosed 管理器写进 `<queries>`                                                                                                                                                                                                        |
| 页面顶部出现两个「输入法增强」                              | 默认主题带 ActionBar，系统那条标题栏和 Miuix 的 `SmallTopAppBar` 撞了                                                                                                                                                | 自定义 `Theme.OplusWubi`（`parent=Theme.Material.NoActionBar`）                                                                                                                                                                                                      |
| 「强停」点了没反应                                          | `ActivityManager.killBackgroundProcesses` 只对后台进程有效，输入法是系统绑定的常驻服务 —— **调用返回成功但进程照旧活着**（搜狗实测）                                                                                 | 直接用 root：`su -c am force-stop <pkg>`（首次弹 Magisk 授权）。没有 root 时如实提示用户手动停                                                                                                                                                                       |
| 作用域勾选状态读不到                                        | 记在 LSPosed 自己的库（`/data/adb/lspd/...`，要 root）                                                                                                                                                               | **不读了**：作用域面板后来整个删掉（用户明确不要），勾选由用户自己在管理器里做。曾用 PING/PONG 反推「已生效」，那套也于 2026-10-08 删了（见下节）                                                                                                                    |
| 点「打开设置」进不去注入那一页                              | 只有小布把自己的设置页声明成了**深链 action**（`com.oplus.keyboard.action.setting_input_wubi` 直达五笔页，实测可用）；搜狗的注入页是个 fragment，外面进不去    | 按「深链 action → **root `am start`** → 导出 Activity → 首页」四级降级；注入页全是 `exported=false`，root 那档才是主力（见下表）                                                                                                                                     |
| 隐藏桌面图标后没有入口                                      | 图标就是 `LauncherAlias`，禁用后 `getLaunchIntentForPackage` 返回 null                                                                                                                                               | Activity 本体不禁用：`adb shell am start -n com.lookie.opluswubi/.ui.MainActivity` 仍可打开（界面与 README 都写明）                                                                                                                                                  |
| **隐藏桌面图标后，LSPosed 里那颗「打开模块」的 FAB 也没了** | FAB 走 `AppHelper.getSettingsIntent`，它按 ① `ACTION_MAIN` + `de.robv.android.xposed.category.MODULE_SETTINGS` ② `+ CATEGORY_INFO` ③ `+ CATEGORY_LAUNCHER` 的顺序找；模块原先只挂了 ③，而且挂在被开关的那个 alias 上 | 给 `MainActivity` 本体挂上 ①（那个历史遗留的 Xposed category）。FAB 从此与桌面图标解耦 —— 验证：alias 处于 disabled 时 `cmd package query-activities -a android.intent.action.MAIN -c de.robv.android.xposed.category.MODULE_SETTINGS` 仍能解析出 `.ui.MainActivity` |
| 启动 / 切换动画那一帧露出桌面壁纸                           | 主题里 `android:windowBackground` 写的是 `@android:color/transparent` —— Compose 要等第一帧才画，之前那段空窗就是透明                                                                                                | 改成纯色 `@color/window_bg`（`values` `#FFF6F6F6` / `values-night` `#FF000000`）。Miuix 自己会铺满整页，纯色只在动画那一瞬间可见                                                                                                                                     |

界面还有几条约定：

- 板块标题（输入法 / 桌面图标 / 关于）的 `SmallTitle` 上方各留 8dp 间距（不然标题和上一张卡片贴在一起）；
- 「隐藏桌面图标」这个 `SuperSwitch`
  **不给 summary**，「版本」那条也**不给 summary**（说明已经写在分类标题的语义里，再加一行就成了噪音）；
- 输入法行的图标右边留 **16dp**（Miuix 自己的标准间距）；行尾是一个右箭头（`MiuixIcons.Basic.ArrowRight`，
  `Modifier.size(10.dp, 16.dp)` + `onSurfaceVariantActions` 着色 —— 跟 Miuix 的 `SuperArrow` 内部一致）；
- **顶栏是自己拼的，没用 `SmallTopAppBar`**：Miuix 的小顶栏标题是**居中**的（MIUI 观感），而 `SmallTopAppBar`
  的参数里没有能改标题对齐方式的口子（只有 title / navigationIcon / actions / titlePadding / navigationIconPadding /
  actionIconPadding / subtitle / bottomContent）。自己用 `Row` + `background(surface)` + `statusBarsPadding()` +
  `heightIn(min = 64.dp)` + 起始 26dp 拼一个即可，字号取
  `title1`（32sp，最大一档）—— 底色 / 起始边距 / 高度都照 Miuix 的 token 来，观感一致。 `title2/3/4` 分别是 24 / 20 /
  18sp（`SmallTopAppBar` 原生用的是 `title3`）。

「申请作用域」这件事**没有官方接口**：LSPosed 2.2.1 的管理器只导出 `MainActivity` （`MAIN` /
`APPLICATION_PREFERENCES`），dex 里既没有旧的 `AppListActivity`，也没有 `module_package_name`
这个 extra（实测把包名塞进去只会打开管理器的设置页）。早期版本因此在界面上放了一个「打开 LSPosed 管理器」的入口，
**后来去掉了**（用户明确不要）；再后来连**整个作用域面板都删了**
—— 现在「已生效」直接跟在每一行的主标题右边，勾选仍由用户自己在管理器里做。

> `BasicComponent` 的 `title` 是
> `String?`，**没法在里面塞带颜色的第二个 Text**。要让「已生效」跟在标题右边，得走它的**自定义内容重载**（`leftAction` +
> `endActions` + 末尾 `content: @Composable ColumnScope.() -> Unit`），自己按 `headline1` / `body2`
> 两个样式画标题与摘要（这两个就是它内部用的样式，颜色分别是 `onBackground` / `onSurfaceVariantSummary`）。两个 Text 用
> `Alignment.Top` 对齐（试过 `alignByBaseline`，小字会显得偏下）。
>
> 另外 `BasicComponent` 给内层容器加的是
> `heightIn(min = 56.dp)`，而里面那列内容是**顶对齐**的 ——只有一行字（没有 summary）时会明显偏上。「版本」那条因此给了个 summary，别做单行。

## 界面不再有「已生效」这个状态

沿革，别退回去：

1. 第一版：注入侧装好 Hook 回一条广播，界面记下时间戳 ——
   **假状态**（用户在 LSPosed 里停用模块、或把输入法踢出作用域之后，那条记录还在）。
2. 第二版（2026-10-07）：改成一问一答 —— 界面 PING、注入侧在宿主进程里当场 PONG，「这一轮回过 PONG 的包」就是此刻真的挂着模块的。比第一版真，但仍然只是**反推**，输入法进程没跑就显示未生效。
3. 现在（2026-10-08）：**逐行状态整个删了**，行里只留「未安装」。唯一的状态是标题右上角那个「未启用」上标 —— 那条走 libxposed 官方 service
   API（见下节），跟广播不是一回事。「哪些进程此刻挂着模块」这个信息没人再需要：跳转与强停都走 root，都不看输入法进程在不在。

第二版当初踩过的坑（代码已删，坑还在，别再踩一遍）：

- **应答器要尽早注册**。原先只在 `ImeEnv.bindContext`
  里注册，而各适配器绑定 Context 的时机差很远（小布在 Application.onCreate、搜狗在候选 Hook、百度要等键盘弹出来）—— 结果百度一直不回包。真要再走广播，得在
  `WubiModule.onPackageReady` 里统一钩一次宿主 `Application.onCreate` （类名取自
  `applicationInfo`、按名字从宿主 ClassLoader 解析，不直接引用，红线 1）。
- **`RECEIVER_EXPORTED` 不能省**：广播来自模块自己的进程（不同 UID），Android
  13+ 不导出就直接不投递（13 之前动态注册默认导出）。
- **动态注册的接收器只在进程活着时才存在** —— 这正是「重启数量会抖」的根源（真机日志见下节）。
- **测的时候注意进程新旧**：`adb install -r` 之后没重启过的输入法进程跑的还是旧代码 —— 先强停一次。

### 模块启用状态：用 libxposed 的 **service** API（官方、不需要 root）

一开始以为「Xposed/LSPosed 都没有查询接口」，只能反推或者 Hook 自己 —— **都不对**。libxposed 有一个独立的构件
**`io.github.libxposed:service`**，专门给**模块自己的 app 进程**用：

```kotlin
XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
    override fun onServiceBind(service: XposedService) { /* 模块已启用 */ }
    override fun onServiceDied(service: XposedService) { /* 模块被停用 */ }
})
```

机制：这个 AAR 自带一个 `io.github.libxposed.service.XposedProvider`
（`android:authorities="${applicationId}.XposedService"`，**manifest 会自动合并，不用手写**）；LSPosed 调它，把框架的 binder 通过
`SendBinder` 递过来， `XposedServiceHelper.onBinderReceived` 再回调 `onServiceBind`。 **模块没启用就不会有这次调用。**

判据就一条：**binder 在不在**
—— 没有超时、没有轮询、没有反推。启用时框架立刻推过来（真机实测：App 启动后 1 秒内界面已经是「已启用」，看不到闪），停用时立刻断开走
`onServiceDied`。所以状态要放**进程级单例**里（[`LsposedBridge`]）—— 放 Activity 实例字段里的话，Activity 一重建就丢，界面又变回「未启用」（踩过）。

`XposedService` 上还有一堆现成能力：`getScope()` / `requestScope()` / `removeScope()`、 `getRemotePreferences()` /
`listRemoteFiles()` / `openRemoteFile()` / `deleteRemoteFile()`、 `getApiVersion()` / `getFrameworkName()` /
`getFrameworkVersion()`。 **`getRunningTargets()`**（带 `HookedTarget.State`：UP_TO_DATE / STALE / RELOADING /
FAILED）与 **`hotReloadModule()`** 是 **102 才有的**，本项目编的是 `service:101.0.0`，用不了。

> **官方文档在哪**（2026-10-08 实测）：LSPosed 这边**没有给现代 API 的文档站**。
>
> - `https://libxposed.github.io`（POM 里 developer url 写的那个）→ **404**，站没了；
> - `https://lsposed.org` = `https://lsposed.github.io` → 只有五行：下载 / changelog
>   / 报错邮箱 / 模块仓库，**没有开发者文档**；
> - LSPosed 主仓库 README 的「For Developers」只把开发者指向 `https://api.xposed.info/`（**老 XposedBridge API**
>   的站，还在，但跟 libxposed 无关）；
> - LSPosed wiki 只有三页开发者内容（New XSharedPreferences / Module Scope / Native Hook），最后编辑停在 **2022-05 /
>   2022-12**，讲的全是 meta-data 那套老东西，**没有一个字提到 libxposed 或 `service`**。
>
> 现代 API 唯一的文档就是 **Maven Central 上的 `-javadoc.jar`**（Dokka 生成），比如
> `https://repo1.maven.org/maven2/io/github/libxposed/service/101.0.0/service-101.0.0-javadoc.jar`，解出来是全套类/方法说明，`-sources.jar`
> 是配套源码。查 API 直接下这个。好处是它跟着 artifact 一起发布，**不会跟代码漂移**；坏处是只有 API 参考，没有教程、没有索引、没法搜索。
>
> ⚠️ 这台机器上 `raw.githubusercontent.com` / `api.github.com` 对 **`libxposed/*` 路径整体 404** （连 POM 里明确存在的
> `LICENSE` 都 404，而 `LSPosed/*`
> 正常），所以 libxposed 仓库里到底有没有 README 之类**没法从这台机器验证**，别据此下结论。

几个坑：

- **版本要用 `101.0.0`**：`service:102.0.0` 的 AAR 元数据要求 `compileSdk 37`，本项目是 36（AGP 8.13.1 的上限），会直接
  `checkDebugAarMetadata` 失败。101.0.0 要求 36。⚠️ 两版**不是**「API 一模一样」：102 多了 `getRunningTargets()` 和
  `hotReloadModule()`，真要热重载得先解决 compileSdk 37 的问题。
- 它只依赖 `interface`，**不会**把 `api` 带进 APK（`api` 必须保持 `compileOnly`，AGENTS.md 红线 1）。
- ⚠️ **回调在 binder 线程**：`onServiceBind` / `onServiceDied` 里改的状态要回主线程，中间用到的标志位必须
  `@Volatile`（踩过：普通变量让主线程看不到已置上的 true）。
- 之前试过的两条路都**撤了**：① 把模块自己写进 `scope.list` + Hook 自己 —— LSPosed 的静态作用域是**缓存**的，改完
  `scope.list` 要重启手机（切模块开关也不重读）才生效；② `su` 读 `/data/adb/lspd/config/modules_config.db`
  —— 每次弹 KernelSU 授权提示，而且表结构是内部实现。

### 界面上的动作走 root（2026-10-08 从广播退回）

| 动作                   | 现在                                                                                              | 以前（已撤）                                    |
| ---------------------- | ------------------------------------------------------------------------------------------------- | ----------------------------------------------- |
| 打开注入页（未导出）   | `su -c am start --user current -n <pkg>/<cls>`；百度走 `-a android.intent.action.VIEW -d '<uri>'` | 广播给注入侧，它同 UID 直接 `startActivity`     |
| 强停输入法（重载模块） | `su -c am force-stop <pkg>` —— 一条 `su` 里连停全部已安装的，按 `echo` 出来的标记数个数           | 广播给注入侧，它 `Process.killProcess(myPid())` |
| 判断进程在不在         | 不需要了（两个动作都不看输入法进程在不在）                                                        | `su -c pidof` + PING/PONG                       |

为什么退回来：广播那版只对「上一轮回过 PONG 的包」动手，而应答器是**动态注册**的（宿主进程没起就没人接），于是同一个按钮点两次数量不一样 —— 真机日志自证：`11:55:37 已请求重启 1 个 [com.oplus.keyboard]`
→ `11:55:45 活性探测：问 4 个，答 2 个` →
`11:56:06 已请求重启 2 个`。LSPosed 模块本来就装在 root 环境里，「不需要 root」没换来任何东西。

⚠️ 代价：`am force-stop` 会把应用置成 stopped、系统随即把当前输入法切走（用户为此发过火），强停完重选一次即可。另外 `su`
是阻塞的，界面上的两个 root 动作都必须放后台线程、结果回主线程再 toast。

## 输入法图标要自己裁一遍

`getApplicationIcon` 各家给的东西不一样：小布返回的位图**自带一圈透明内边距**（真机量出来可见内容 104×106
px），另外三家是 140×140 px 铺满。都塞进同一个 40dp 的框里，小布那个就明显小一圈（用户报的「图标没统一长宽」）。

`MainActivity.squareByAlpha()`：按 alpha 找出可见内容的包围盒 → 以它的中心按长边补成正方形 → 边长压到 192px 以内，再配
`ContentScale.Fit` 画进 40dp。四家实测都是 140×140 px。

⚠️ 量的时候别用固定阈值比背景色：小布图标那圈深色圆角方块和卡片底色（#242424）很接近，阈值定高了会只量到里面那个亮键盘、得出「还是 104×106」的错误结论。

## 「点一行跳到注入页」：四个输入法的入口组件

注入页在 manifest 里**全是 `exported=false`**（普通应用 `startActivity` 会被 `SecurityException`
挡掉），而模块本来就依赖 LSPosed、设备必然有 root，所以直接 `su -c am start --user current -n <pkg>/<cls>` —— uid
0 能绕过组件导出检查。真机四个都跑通（`finished=true code=0`）：

| 输入法 | 目标组件                                                                                | 怎么找到的                                                                                                                                                                                                                                                                                                                                                                                             |
| ------ | --------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 小布   | `com.oplus.keyboard.action.setting_input_wubi`（action，不需要 root）                   | 它自己声明的深链                                                                                                                                                                                                                                                                                                                                                                                       |
| 搜狗   | `-n com.sohu.inputmethod.sogou/com.sogou.imskit.feature.settings.activity.WubiSettings` | `WubiSettingFragment` 的宿主 Activity（aapt2 看 manifest：`exported=false`）。**别用 `SogouIMESettings`** —— 那是 Kuikly 画的设置首页，`onCreate` 里把 `pageName` 硬写成 `SettingPage`，进不到五笔那一页                                                                                                                                                                                               |
| 百度   | `-a android.intent.action.VIEW -d bdinput://openinputsettings`                          | 它自己的 scheme（`ImeSchemeActivity` 的 `openinputsettings` host）。**别用 `com.baidu.input.ImeFrontSettingActivity`** —— 那是键盘上「前置设置」快捷面板的代理，`onCreate` 里第 26 行直接 `finish()`（真机 dumpsys 的栈：`ImeFrontSettingActivity.finish:7 ← onCreate:26`，起了就退，界面上什么都没发生）。真正的设置页是 `ImeMainConfigActivity`（没导出、还要带 extras 才画得对），所以宁可走 scheme |

**百度的「五笔设置」到不了**（2026-10-08 试过一轮注入侧自动钻页，**已撤掉**）：

它的设置是**一个** `PreferenceActivity` + 一棵嵌套 `PreferenceScreen`
的偏好树，没有任何 Intent 能直达；层级是 设置首页 → `general_setting`(输入设置) → `pref_key_input_type_wb`(五笔输入) →
`pref_key_wb_setting`(五笔设置) → `pref_key_wb_mode_new`(五笔方案)。所以现在**只跳到设置页**，再点两下才到。

想让注入侧自己点进去，这几条是试出来的（留给以后）：

- `PreferenceScreen.performClick()` **点了等于没点** —— 无参那个重载最终走到 `PreferenceManager` 的
  `OnPreferenceTreeClickListener`，嵌套屏场景下监听者就是 `PreferenceScreen` 自己，它对「屏中屏」只会
  `showDialog()`（AOSP `PreferenceScreen.onClick()`）。
- 得走
  `PreferenceScreen.onItemClick(AdapterView, View, int, long)`（ListView 的 item 点击落到这里）。第一个参数**必须是真的 AdapterView**，传屏自己会被
  `Method.invoke` 用 `IllegalArgumentException` 顶回来；而它对 `ListView` 会减掉 header 数，所以位置要**加上**
  header 数再传。这一条**确实能翻页**。
- `activity.getPreferenceScreen()` **翻页后还是返回根屏**，拿它当「当前屏」会每轮都在根屏里找到同一个 key、原地打转。
- 但 `activity.getListView().getAdapter()` 又**不是** PreferenceScreen 绑定的那个 ListView（`performItemClick`
  不生效），两条路都走不通 —— 想继续做的话，得先弄清百度这棵树的 ListView 到底在哪。
- 钻页请求的时序也不稳：设置页那个进程可能是**广播之后**才起来的（要连发几次），而且 `com.baidu.input` 有多个进程。

`--user current` 不能省：root 跑 `am` 默认落在 user 0，模块装在次要用户里就白跳了。

⚠️ 读 `am start`
的输出，**别只看退出码**：它失败时（`Error: Activity not started…`）退出码往往还是 0。百度「成功但没反应」就是这么来的 —— 现在
`rootStart` 会要求输出里有 `Starting: Intent` 且没有 `Error`。

⚠️ `su` 是**阻塞**的（首次还会弹 Magisk 授权框），`openSettings` / `forceStopAll`
都必须在**后台线程**里跑，结果回主线程再 toast —— 否则就是 ANR。

### 真机调试：别对当前输入法用 `am force-stop`

`adb shell am force-stop com.baidu.input` 会把应用置成 **stopped**
状态，系统随即把当前输入法切走 ——用户得重新选一次输入法（用户为此发过火）。注入侧改了代码又必须让宿主进程重启才生效，所以调试时：

```bash
# 1. 先记下当前输入法
adb shell settings get secure default_input_method      # com.baidu.input/.ImeService
# 2. 重启宿主
adb shell am force-stop <pkg>
# 3. 立刻把输入法还回去
adb shell settings put secure default_input_method com.baidu.input/.ImeService
```

`am kill <pkg>` 更温和（不置 stopped 标记），但**杀不掉还在提供输入法服务的进程**，对当前输入法没用。

**依赖版本要压住**：Miuix `0.8.0` 起依赖 Compose 1.10 / kotlin-stdlib 2.3，`0.9.x` 更是拉进 androidx.compose 1.12
—— 那要求 AGP ≥ 9.1 且 `compileSdk 37`。本项目 AGP 8.13 + Kotlin 2.2 + compileSdk 36，所以停在 **0.7.2**（Compose 1.9.3
/ Kotlin 2.2.21），并显式补上 Miuix 声明成 runtime scope 的 compose 构件（`runtime`/`foundation`/`ui` 1.9.4、`material3`
1.4.0）。

---

## 真机验证清单

模块日志在 LSPosed → 日志，TAG `OplusWubi`（历史原因，三个适配器共用）。
**每装一次模块都要强行停止一次输入法**，否则目标进程里还是旧代码。

### 小布输入法

1. 强行停止小布输入法后随便打开一次输入法，日志应出现 `模块已加载`、`命中输入法适配器：小布输入法`、以及三条
   `已 Hook ...`；
2. 进入五笔设置页，日志应出现 `已注入方案列表：N 个方案 + 添加方案` 和 `已注入自定义方案设置条目`；
3. 页面上「五笔方案」分组里出现各方案条目 + 底部「添加方案…」，「五笔设置」里出现「自定义方案设置」；
4. 点「添加方案…」导入一份 txt，日志出现
   `码表导入成功`（含 md5），条目数应与文件行数接近；再导入一次同一个文件，日志应出现
   `导入去重：内容与已有方案「…」完全一致`，列表不新增条目；
5. 切到五笔键盘输入完整编码，日志应出现 `候选列表 Hook 第 1 次被调用：引擎候选 N 个，当前编码='xxx'` 与
   `已注入 M 个自定义候选：code='xxx' -> [...]（内置候选 N 个，已丢弃）`，候选栏里应该**只有自定义方案的词**；—— 若只有
   `候选注入未生效：<原因>`，那句话直接指出断在哪一环（不是五笔键盘 / 没有当前方案 / 方案里没有该编码 / 拿不到 Context
   …）；若候选栏显示的是编码而不是词，先确认 `tip` 没被塞东西（会落到 `comment`，候选栏优先显示它）；
6. 点击自定义候选，上屏的是码表里的词；
7. 按空格，上屏的也是码表首选词（这条没有开关可关，是固定行为；不对就看日志里那条 `接管提交` 记录，它带提交前后的原文与替换词）；
8. 切到拼音键盘输入，候选与未装模块时一致（说明没有误注入）。

### 百度输入法

1. 强行停止百度输入法后随便打开一次输入法，日志应出现 `模块已加载`、`命中输入法适配器：百度输入法`、
   `已 Hook CoreConfigHandler2.d()（下发配置前对齐五笔方案）`、`已 Hook WbPref.f()（五笔设置页注入「修复五笔方案」入口）`、
   `已 Hook ImeService.onStartInputViewInternal（切入时强制下发方案 + 诊断）`；
2. 打开百度设置 → 五笔设置，日志应出现
   `已注入「修复五笔方案」开关（当前=true，分组=…，控件=ImeCheckBoxPreference）`，页面上「五笔方案」下面多出这一条，**带勾选框**且默认勾上，样式与原生开关条目一致；
3. 在百度设置里选好自定义五笔方案，**从其它输入法切到百度**，日志应出现
   `[openCore] 核心已打开：native 里的方案键 82=1，84=0`（**1 = 内置 86，这是常态，不是故障**），紧跟
   `已把方案 3 下发给引擎（CoreConfigHandler2.c，isCoreOpened=true）` 与 `已把方案 3 推给引擎（a0=3）`；若核心打开时
   `isCoreOpened` 还是 false（偶发），会由 `onStartInputViewInternal` 的补推兜住，日志里是
   `核心还没确认打开，边补推边等（每 500ms 一次，最多 20 次）`；
4. 直接切到五笔键盘打字，出字应与自定义方案一致 —— **不需要**再手动切一次拼音→五笔；
5. 若日志里连 `[onStartInputViewInternal]` 那行都没有，先看有没有 `Global.u() 还没就绪` 或
   `读不到持久化方案`；这两句分别指向「核心还没起来」和「偏好表读法不对」，把日志原文发出来；
6. 回设置页把「修复五笔方案」关掉，日志应出现 `「修复五笔方案」开关已关闭，不干预`，且模块不再改动方案；
7. 切到拼音键盘输入，行为应与未装模块时一致。

## 入口没出现时怎么定位

- **日志里完全没有 `模块已加载`** → 模块没被注入：先看 LSPosed 模块列表有没有「该模块基于 Xposed API 100，与 API
  101 的变更不兼容」这类提示（LSPosed 2.0 起不再加载 API 100 模块，需 `targetApiVersion=101`
  以上）；再确认 LSPosed 里已启用本模块、已强行停止过输入法进程。
- **有 `模块已加载` 但没有 `命中输入法适配器`** → 作用域不对（应为 `com.oplus.keyboard` / `com.sohu.inputmethod.sogou` /
  `com.baidu.input`）。
- **有 `命中输入法适配器` 但没有 `已注入自定义码表入口`** → 看同一条日志附近的 `注入设置入口失败(...)` /
  `getPreferenceScreen() 返回空` / `创建 Preference 失败`，这几条会直接指出是哪一步断的。
- **百度：有 `已 Hook WbPref.f()` 但页面上没有「修复五笔方案」** → 看日志里有没有
  `五笔方案条目一直没挂到分组上`（页面装配比预期慢）、`造 com.baidu.input.pref.ImePreference 失败`、或
  `addPreference 失败`；也可能是这条目在部分版本/机型上被隐藏了（那 `WbPref.f()` 就不会被调）。
- **报 `NoClassDefFoundError: Failed resolution of: Landroidx/preference/...`**
  → 模块代码里又出现了对目标 App 类型的直接引用，改回「从目标 ClassLoader 解析 + 反射」（见
  [implementation.md](implementation.md#-铁律不许直接引用目标-app-的任何类)）。
