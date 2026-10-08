<div align="center">

# 输入法增强

**给 Android 输入法注入自定义五笔码表，并解除剪贴板历史条数上限**

![Android](https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white)
![LSPosed](https://img.shields.io/badge/LSPosed-API%20101%2B-5C6BC0)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2.20-7F52FF?logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-yellow)

</div>

导入一份 txt 码表（例如「虎码字词」）后，切到五笔键盘即可用自定义编码出字，支持多份码表切换与管理。模块**没有桌面图标**，入口注入在输入法自己的设置页里。

## 支持的输入法

| 输入法                             | 包名                         | 依据版本        | 入口                               |
| ---------------------------------- | ---------------------------- | --------------- | ---------------------------------- |
| 小布输入法（OPPO / 一加 / realme） | `com.oplus.keyboard`         | `1.8.33.17-mkt` | 五笔输入法 → **五笔方案**          |
| 搜狗输入法                         | `com.sohu.inputmethod.sogou` | `20.17.0`       | 五笔设置 → **特殊习惯** → 支持简词 |
| 百度输入法                         | `com.baidu.input`            | `13.3.16.2`     | 五笔设置 → **修复五笔方案**        |

小布由模块注入完整的码表导入能力（导入 / 切换 / 重命名 / 删除，最多 3 份，按 MD5 去重）。搜狗与百度**本身就能导入自定义方案**，模块只打补丁：搜狗补一个「支持简词」开关（只把码表里靠前的简词挪到候选首位，**不造词**）；百度修它「从别的输入法切过来已启用方案不生效」的时序 bug。

## 使用

1. 从 [Actions](../../actions) 的 artifact 或 [Releases](../../releases)
   下载 APK 安装，在 LSPosed 中启用本模块并勾选目标输入法
2. **强行停止该输入法**（或重启手机）—— 模块只在进程启动时注入
3. 进输入法设置页导入码表，切到五笔键盘即可

模块自己也有一个入口（桌面图标「输入法增强」）：列出三个输入法并直达模块注入的那一页（目标页未导出，走 root），顶栏刷新 = 强停全部输入法，标题上标提示模块没在 LSPosed 里启用，桌面图标可隐藏。

**回车**（小布）上屏原始编码而非候选词；**剪贴板**历史条数上限被解除（小布 500 / 搜狗 500
/ 百度 300），不再自动删旧记录。

## 码表格式

列顺序自动识别，支持 `词<TAB>码`、`码<TAB>词`、`的,u`（搜狗 / 百度导出）。编码 1~6 位纯英文字母，`#` 或 `//`
开头按注释忽略；内容相同的码表自动去重，十几万条约 1~3 秒解析完。

## 已知限制

- 自定义候选固定排在引擎候选之前，不参与词频学习；不替换厂商自带的二进制词库
- **顶屏**：打满码表最长码后第五码由模块顶屏（去掉末位精确命中就上首选词，并把那一键按回键盘）
- 搜狗 / 百度只按反编译结论打补丁，不接管码表管理、不改它们的候选排序；换版本会自动逐项降级，不影响输入法启动

出问题先看 LSPosed 日志（TAG `OplusWubi`），每一步失败都会写明断在哪一环。细节：[实现原理](docs/implementation.md) ·
[宿主逆向笔记](docs/host-notes.md)

## 源码编译

需要 **JDK 17** 与 Android SDK（`compileSdk 36` / `build-tools 36.0.0`）：

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk
```

release 比 debug 多 R8 混淆与资源收缩、`debuggable=false`，且编译期消掉 `XLog.d` /
`XLog.i`（连日志字符串都不进包），只留 `XLog.w` / `XLog.e` 降级诊断。

正式签名读根目录的 `keystore.properties`（已 gitignore）：`storeFile` / `storePassword` / `keyAlias` /
`keyPassword`。**debug 与 release 共用这把签名**（只要文件在），所以本机包和 CI 出的包可以互相覆盖安装；没有该文件时才退回 Android Debug 签名 —— 那种包只能本机测试、不可发布。CI 见
[`.github/workflows/build.yml`](.github/workflows/build.yml)，从同名 secrets 读。

## 许可

[MIT](LICENSE)。仅供**个人学习与逆向研究**使用；模块通过 Hook 修改输入法运行时行为，请自行评估风险，勿用于商业用途。本项目与各输入法厂商、LSPosed 项目均无关联。
