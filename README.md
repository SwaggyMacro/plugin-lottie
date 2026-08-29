# Halo Lottie 动画插件

让 Halo 支持 Lottie/TGS 矢量动画，支持在文章内、站点任意地方插入。

> 其实最主要的功能就是为了让我可以在 Halo 用上 Telegram 的动画贴纸包，省去转 `.avif` 这一步，直接显示矢量动画，仅需几十KB大小就有几十MB的效果。

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

