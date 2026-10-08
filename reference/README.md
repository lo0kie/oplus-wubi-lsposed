# 宿主包核对（reference/）

## 微信输入法（真机底包）

- 文件：`reference/wxkb_3.5.4.apk`（224,668,058 字节，md5 `5d79d0e9fd5917ac9ded03614b05d543`）
- 已被 `.gitignore` 排除（`reference/*.apk`）—— 体积大，只留本地。
- `versionName=3.5.4` / `versionCode=57201`。注意：官网发的 `4.0.0` 是同一份源码、不同 R8 映射，
  **同一个方法短名不一样**（`O0` vs `I0`、`Q3` vs `P3`、`y1` vs `x1`，`utils.i1` 在 3.5.4 里挪成了 `utils.a1`）。
  所以任何按短名硬找的 Hook 点都不可靠，一律走「候选名 + 签名校验」或「按签名找」。

## dexfind.py —— 认混淆名的权威工具

自写 dex 解析器（不依赖 jadx/dexdump），按方法名反查它所属的类与完整签名：

```bash
python reference/dexfind.py reference/wxkb_3.5.4.apk classes3.dex setKeyboardsSettingInfo
# == 方法 setKeyboardsSettingInfo 命中 1 处
#    Lcom/tencent/wetype/plugin/hld/reactnative/module/CallNativeMethodWithCallBack;
#      (Landroid/app/Activity;, Lcom/facebook/react/bridge/ReadableMap;, Lcom/facebook/react/bridge/Promise;) -> V
```

类的**短名**（`i0`、`a1`、`q1`、`P3`…）查不了 —— 它们是 R8 产物，只能靠签名或真机日志。
但**不被混淆的名字**（RN 桥接、JS 反射调用的入口、资源键名）都能直接查，这正好覆盖设置页那条路。

## dexcode.py —— 看某个类里到底调了谁

`dexfind.py` 只给「方法属于哪个类」；要判断**一个方法内部做了什么**（有没有发通知、
是不是纯 setter），用 `dexcode.py`：它把 `code_item` 里的 `invoke-*` 按顺序解出来。

```bash
# 列一个类的全部方法（含 direct/virtual、有无字节码）
python reference/dexcode.py reference/wxkb_3.5.4.apk 'Lcom/tencent/wetype/plugin/hld/model/i0;'

# 看 q1 / o3 / N5 各自调了谁
python reference/dexcode.py reference/wxkb_3.5.4.apk 'Lcom/tencent/wetype/plugin/hld/model/i0;' q1 o3 N5
```

**它解决过的问题**：`q1(List, scene)` 的方法体只有 `p.m()` / `new ArrayList()` / `list.addAll(...)`
—— 三句赋值、**没有任何通知调用**，所以模块直调 `q1` 时候选栏根本不会更新
（真正通知 UI 的是下游的 `N5` → `listener.A(...)`）。这个结论靠真机日志永远看不出来：
日志只能证明「我们调了 `q1`」，证明不了「界面收到了」。**类名短名照样能查 —— 只要给出完整类名**
（`Lcom/tencent/wetype/plugin/hld/model/i0;`），因为只有**简单名**被混淆、包路径不变。

⚠️ 它只解 `invoke-*` 的目标，**不给寄存器/参数对应关系** —— 能回答「调了谁、按什么顺序」，
回答不了「传的是哪个变量」。需要后者时得上真正的反汇编器。

## 已核对的结论（2026-10-07）

| 项 | 实测值 |
|---|---|
| RN 桥接入口 | `reactnative.module.CallNativeMethodWithCallBack.setKeyboardsSettingInfo(Activity, ReadableMap, Promise)` |
| RN 写五笔方案落点 | `reactnative/model/WubiInfo.setWubiInfo(ReadableMap)` —— **不经过 KV** |
| 方案 KV 键 | `ime_wubi_input_solution`（int），读写器 `utils.a1.e(String,boolean)` / `k0(String,int)` / 写入 `x2(String,int…)` —— ⚠️ `utils.a1` 是**包装类**（一行转发到真实设置类 `utils.j1`），真正被调用方直接使用的是 `utils.j1` |
| 真实设置类 | `utils.j1`：`B(String,boolean)->boolean` 读、`B3(String,boolean)->void` 写、`J1()->int` 读方案、`U3(int)` 写方案。**挂钩子要挂它**（挂包装类 `utils.a1` 不会触发） |
| RN props 键 | `wubiInfo` → `wubiSolution`（驼峰） |
| RN drawable 底色 | `getColor()`（**没有** `getSolidColor`） |
| RN drawable 圆角 | `getBorderRadius()` 返回 **`float[8]`**（不是对象、不是单值） |
| 方案页条目树 | `HldWubiSolutionSettingActivity.I0()`（`4.0.0` 是 `O0`）返回 `List` |
| 键盘类型（会话层） | 五笔 = 5（布局层是 3，别搞混） |

## 教训

猜包路径 / 猜 API 名字连续错了三轮（`reactnative.calling`、`getSolidColor`、按 float 取圆角）。
**凡是能从 dex 里查出来的，一个都不要猜**；先查再写。
