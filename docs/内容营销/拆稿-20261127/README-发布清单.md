# 拆稿与发布清单（2026-11-27）

**状态：三平台已发布。**
- CSDN：https://blog.csdn.net/weixin_66219471/article/details/171493618 （审核中；用户手动补封面与插件名加粗）
- 知乎：https://zhuanlan.zhihu.com/p/2109663091604694014 （发布即公开，已进专栏，8 图 / 5290 字符）
- 掘金：https://juejin.cn/post/7712657463330603059 （已进专栏，5 图走 Gitee 外链直出）

底稿：[`../老版本承接-2024x-2025x-还能装谁.md`](../老版本承接-2024x-2025x-还能装谁.md)（2115 字，6 节，5 图）
配图：[`../img-20261127/upload/`](../img-20261127/upload/)（1200px 上传版）；线上地址走 Gitee raw：
`https://gitee.com/SongRenShuo/mybatis-plus-sql-visualizer/raw/master/docs/<percent-encoded 路径>`
（jsDelivr 不可用——该仓超过 jsDelivr 每包 50MB 限制，只有早前被缓存过的图能取）

## 一、三版差异

| 文件 | 平台 | 结构 | 语气 | 特有内容 |
|---|---|---|---|---|
| `zhihu.md` | 知乎专栏 | H3 分层（知乎专栏惯例），6 节 | 第一人称、读者提问视角 | 文末原创声明；创作声明「包含 AI 辅助创作」 |
| `juejin.md` | 掘金 | H2，6 节 | 工程/动作视角（「先做一次兼容体检」） | 可复制的 `curl + jq` 片段；四条路压成有序列表 |
| `csdn.md` | CSDN | H2 + H3，7 节 | 教程向 | 开头「这篇适合谁」；结尾「可执行检查清单」5 步；报错栈用代码块 |

三版共用同一套事实与同一组图，改了开合方式与收尾，避免整篇重复。表格统一压到 4 列（插件 / IDEA 区间 / 2026.3 / 2024.3~2025.x），版本号与下载量挪进正文一句话——6 列在手机端会被压成两行字。

## 二、发布顺序与设置

| 顺序 | 平台 | 稿件 | 必填设置 | 图 |
|---|---|---|---|---|
| 1 | 知乎专栏 | `zhihu.md` | 话题 `MyBatis`、创作声明「包含 AI 辅助创作」、收录「MyBatis 与 SQL 调试」、封面 16:9 | 5 张，顺序见正文 |
| 2 | 掘金 | `juejin.md` | 分类「后端」、标签 **`MyBatis`（必填，空标签点发布无提示）**、封面 3:2 | 5 张 |
| 3 | CSDN | `csdn.md` | 原创、公开、分类「后端」、标签 `MyBatis`/`IntelliJ IDEA`/`SQL`/`插件`、封面 `封面-csdn-16x9.png` | 5 张 |
| 4（可选） | 博客园 / 腾讯云 / 阿里云 | 复用 `csdn.md` | 合集「MyBatis 与 SQL 调试」 | 5 张 |
| — | 51CTO | **不发** | — | 该站对推广零容忍，本篇末尾有主张段，风险不值当 |

间隔建议：知乎先发，掘金/CSDN 隔 30 分钟以上，避免三家同时触发风控。

## 三、各平台的已知坑

- **知乎**：粘贴 Markdown 后会弹「是否转成 Markdown 格式」，必须点确认，否则加粗、表格、图片全部退化成纯文本。图宽压到 800px 以内最稳（本篇已压到 1200，若被拒再降到 800）。
- **掘金**：标签是红色星号必填项，空标签时点「确定并发布」**没有任何提示**，页面停在编辑页。
- **知乎发布后不要立刻 reload** 去验证——会把还能再发一次的内容刷掉；判定发布成功看「文章数 +1」或管理页列表。
- **CSDN**：摘要必须手填（编辑器不会从正文自动取），已在上方给出。

## 四、五张图的位置

| 图 | 知乎版 | 掘金版 | CSDN 版 |
|---|---|---|---|
| `fig5-ours-2024.3plus.png` | §一（跟着 `until-build` 留空那段） | §一 | §一 |
| `fig1-whats-new.png` | §一·上限为什么被写死 | §一·为什么上限总被写死 | §一·上限为什么会被写死 |
| `fig2-logfree-versions.png` | §二 | §二 | §二·路径二 |
| `fig3-review-noclassdeffound.png` | §四 | §四 | §四 |
| `fig4-retreat-ladder.png` | §五·退到旧版插件 | §五 | §五·路线二 |

## 五、发布后回填

- `../已发布内容清单.md`：知乎专栏 6 → 7 篇；掘金 3 → 4；CSDN 3 → 4；概览表篇数同步。
- 回填字段：链接、发布日期、摘要、字数、图数、标签、是否进专栏。
- 若知乎进了审核队列（阅读数 0、列表搜不到），按既往口径记为「审核中」，**不要删了重发**。

## 六、本次跑通的机器发布路径（下次可复用）

本次三平台全部由 Edge CDP 驱动完成，脚本留在 `.local/cdp/`（gitignored）。要点：

**共通前提**：图片必须先有公网地址。仓库超 jsDelivr 50MB 上限，用 Gitee raw：
`https://gitee.com/SongRenShuo/mybatis-plus-sql-visualizer/raw/master/docs/<URL 编码后的中文路径>`。
CSDN 与知乎会自己抓取并转存到各自 CDN；**掘金不会转存**，只保留外链。

**CSDN（CKEditor 4）**
- 编辑器在 iframe 里（`.cke_wysiwyg_frame`），正文用 `CKEDITOR.instances.editor.setData(html)` 灌 HTML 最稳；md 需先转 HTML（表格/代码块/图片各按语义标签）。
- 标题是 `#txtTitle`，用原生 setter + `input` 事件。
- 标签是受控组件，必须**先把「添加文章标签」用真实鼠标点开**（元素在视口外，要先 `scrollIntoView`），再用 CDP 发 Enter；JS 合成事件不生效。
- 点「发布博客」后跳 `/mp_blog/creation/success/<id>` 即成功。

**知乎（Draft.js）**
- **不要用剪贴板粘贴**：Ctrl+V 会被图片分支吃掉，只落进一张图。用工具栏「导入 → 导入文档 MD/Doc」，喂 `.md` 文件，一次成型。
- 「导入」菜单项文字带 `\u200b`，匹配前要剥零宽字符；`.md` 的 file input 有两个，取最后一个（前一个是「直答知识库」上传器，会永久卡在「上传中」）。
- 发布设置：封面 file input 的 accept 含 `.jpeg`；话题按正文自动识别；创作声明在 `y≈450` 的下拉里选「包含 AI 辅助创作」。

**掘金（CodeMirror 5 + byte-select）**
- 正文 `document.querySelector('.CodeMirror').CodeMirror.setValue(md)`。
- 标签是 `byte-select`，`input[type=text]` 选不中；先点 `.byte-select__input` 并**确认 `document.activeElement` 就是它**，再输入 `MyBatis`，然后点 `.byte-select__dropdown` 里的 `MyBatis` 选项。焦点没落对时 Ctrl+A 会全选 CodeMirror 正文。
- 封面 `input.file-input`；「确定并发布」按钮要点可见实例（隐藏模板按钮 `getBoundingClientRect()` 为 0）。
