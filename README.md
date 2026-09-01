# Halo Lottie 动画插件

让 Halo 支持 Lottie/TGS 矢量动画，支持在文章内、站点任意地方插入。

> 其实最主要的功能就是为了让我可以在 Halo 用上 Telegram 的动画贴纸包，省去转 `.avif` 这一步，直接显示矢量动画，仅需几十KB大小就有几十MB的效果。  
> 动画效果预览: [https://b.ncii.cn/archives/yllDoVlz](https://b.ncii.cn/archives/yllDoVlz)
## 功能

- 使用 `@lottiefiles/dotlottie-web` 播放 `.json`、`.lottie`、`.tgs`。
- TGS 以及 普通 Lottie Json 文件导入时自动 gzip 解压并转换(压缩)为 dotLottie(.lottie) 格式。
- 单文件或 ZIP 批量导入，递归识别目录分组，限制 100 个文件、50 MB 压缩包和 200 MB 解压内容。
- 动画名称、分组、宽高、自动播放、循环、速度、画布适配和无障碍标签均可配置。
- SHA-256 去重，支持跳过或复制导入。
- 控制台管理动画页面和默认富文本编辑器扩展点已接入。
- 主题运行时注册 `<halo-lottie>` 自定义元素，支持在站点任意位置使用。


## 录屏
https://github.com/user-attachments/assets/df760d3b-3e83-4579-bca6-16917e4b7e42

## Lottie 文件下载
**更多请查看 Issue: [Lottie 动画文件收集](https://github.com/SwaggyMacro/plugin-lottie/issues/1)**
| 文件名/下载链接                                                                                         | 主题/IP          | 内容描述                                                       |
| :------------------------------------------------------------------------------------------------------ | :--------------- | :------------------------------------------------------------- |
| [AnimatedHarryPotter.zip](https://github.com/user-attachments/files/31677957/AnimatedHarryPotter.zip)   | 哈利·波特       | 《哈利·波特》系列电影角色的动态贴纸。                         |
| [Minions.zip](https://github.com/user-attachments/files/31677933/Minions.zip)                           | 小黄人           | 《神偷奶爸》系列中的小黄人（Minions）贴纸。                    |
| [HANGSEED_Stitch.zip](https://github.com/user-attachments/files/31677965/HANGSEED_Stitch.zip)           | 星际宝贝史迪奇   | 迪士尼经典动画角色史迪奇（Stitch）。                           |
| [JinxPowder.zip](https://github.com/user-attachments/files/31677970/JinxPowder.zip)                     | 金克丝 / 爆爆    | 《英雄联盟》及动画《双城之战》（Arcane）中的超人气角色金克丝。 |
| [JudyAlvarez.zip](https://github.com/user-attachments/files/31677950/JudyAlvarez.zip)                   | 朱迪·阿尔瓦雷兹 | 游戏《赛博朋克 2077》中的高人气NPC朱迪（Judy）。               |
| [MarvelSpiderManEmoji.zip](https://github.com/user-attachments/files/31677945/MarvelSpiderManEmoji.zip) | 漫威蜘蛛侠       | 漫威《蜘蛛侠》主题的表情或Q版贴纸。                            |
| [DeathNote.zip](https://github.com/user-attachments/files/31677937/DeathNote.zip)                       | 死亡笔记         | 经典日本动漫《死亡笔记》（夜神月、L、琉克等）贴纸。            |
| [CodeGeass.zip](https://github.com/user-attachments/files/31677962/CodeGeass.zip)                       | 叛逆的鲁路修     | 经典日本动漫《叛逆的鲁路修》贴纸。                             |
| [AmongUsGuys.zip](https://github.com/user-attachments/files/31677938/AmongUsGuys.zip)                   | Among Us         | 热门太空狼人杀游戏《Among Us》中的角色贴纸。                   |
| [crewmate_amongus.zip](https://github.com/user-attachments/files/31677966/crewmate_amongus.zip)         | Among Us         | 《Among Us》中的船员/内鬼（Crewmates/Impostors）贴纸。         |
| [BabyYoda.zip](https://github.com/user-attachments/files/31677974/BabyYoda.zip)                         | 尤达宝宝         | 《星球大战：曼达洛人》中的尤达宝宝（Grogu/Baby Yoda）。        |
| [SquidGame.zip](https://github.com/user-attachments/files/31677975/SquidGame.zip)                       | 鱿鱼游戏         | Netflix 热门韩剧《鱿鱼游戏》相关角色与符号（如椪糖、面具人）。 |
| [StarPatrick.zip](https://github.com/user-attachments/files/31677982/StarPatrick.zip)                   | 派大星           | 《海绵宝宝》中的经典角色派大星（Patrick Star）。               |
| [TheRing.zip](https://github.com/user-attachments/files/31677990/TheRing.zip)                           | 午夜凶铃         | 恐怖电影《午夜凶铃》主题（贞子等）。                           |
| [PokeAnim.zip](https://github.com/user-attachments/files/31677995/PokeAnim.zip)                         | 宝可梦           | 各种宝可梦（精灵宝可梦/神奇宝贝）的动态贴纸。                  |
| [VaultBoy.zip](https://github.com/user-attachments/files/31677978/VaultBoy.zip)                         | 避难所小子       | 经典游戏《辐射》（Fallout）系列标志性吉祥物 Vault Boy。        |
| [RickAndMorty.zip](https://github.com/user-attachments/files/31677981/RickAndMorty.zip)                 | 瑞克和莫蒂       | 热门成人科幻动画《瑞克和莫蒂》贴纸。                           |
## 开发

环境要求：Java 21、Node.js 20.19+、pnpm 10.33+。
Gradle 会自动使用 Node 22.14.0；


```bash
./gradlew haloServer
cd ui
pnpm install
pnpm dev
```

构建插件：

```bash
./gradlew build
```

前台模板中可直接使用：

```html
<halo-lottie
  src="/upload/lottie-file.lottie"
  width="96"
  height="96"
  autoplay
  loop>
</halo-lottie>
```
需要加载 `lottie-runtime.js`
```
<script type="module" src="/plugins/lottie/assets/lottie-runtime.js"></script>
```

