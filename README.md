[English](README.zh.md)

# Tiny Container (Chroot)

> [!NOTE]
> Linux 很强大。Linux 也可以易于使用。

这是 [Tiny Container](https://github.com/Cateners/tiny_container) 的 chroot 变体。  
原版用 proot 运行容器，不需要 root。这个变体用 chroot，需要 root。

### 为什么做 chroot？

原版 Tiny Container 使用 proot 运行容器，对大多数用户来说够用了。  
但如果设备已经 root，chroot 能提供更好的性能和更完整的 Linux 兼容性——所以就有了这个项目。

### 区别

- 用 chroot 代替 proot，性能更好，兼容性更强
- 完整挂载文件系统（proc、sys、dev、dev/pts）
- 首次启动自动检测 root 权限
- **需要 root 权限**。没有 root？请用[原版](https://github.com/Cateners/tiny_container)。

其他功能与原版一致。往下看。

---

## 特点

### 为普通用户设计。你不需要懂 Linux......

1. 安装软件并打开，你不需要进行任何操作*，5 分钟的初始化后立刻进入电脑界面。
2. 常用软件的安装命令已为你准备好。点击，然后软件为你完成剩下的工作。
3. 软件的界面尽可能友好地设计了，而不是"有个界面就行"。让 AI 加上了许多语言的翻译。甚至支持 rtl 布局！
    - （虽然，因为我并不使用 rtl，也看不懂 AI 的翻译，不知道准不准确！但是有总比没有好吧？）

### 同时也是给极客的玩具，内置终端、拥有丰富的容器配置选项！

4. 容器和配置可以随意分享！
5. 取自 Termux 社区的前沿功能已准备就绪！你不需要学习如何安装容器、启动图形界面、配置音频等等繁琐工作。以下功能是即开即用的：
    - 安装容器通过导入按钮，图形界面的启动已包含在内（如果容器支持）；
    - 软件内置 AVNC 和 Termux:X11 前端，你不需要额外安装软件；
    - 音频和麦克风转发；
    - virglrenderer 和 turnip+zink 图形加速...
6. 单软件才方便做到的功能！
    - 音频和 VNC 通过 unix socket 传输，不经过网络栈；
    - 通过 saf 文件管理器浏览容器文件；
    - 可以将 .desktop 文件，甚至一般命令作为快捷方式放置到安卓启动器！
7. 不会和 Termux 冲突！

## 下载

apk 安装包见 [releases](https://github.com/12begin/tiny-container-chroot/releases) 页面

## 编译

克隆仓库后，在[原版 release](https://github.com/Cateners/tiny_container/releases) 页下载预编译库 jniLibs.zip 并解压到 app/src/main/jniLibs/arm64-v8a，然后就可以正常在 Android Studio 打开项目编译了。  
如果要做到启动时自动安装容器的效果，可把容器重命名为 rootfs.tar.zst 并放到 app/src/main/assets。  
容器信息见 [chroot-images 仓库](https://github.com/12begin/tiny-container-chroot-images)。

## 致谢

感谢 [Caten Hu](https://github.com/Cateners) 的原版 Tiny Container 项目。  
感谢 [termux](https://github.com/termux) 社区、[tmoe](https://github.com/2moe/tmoe)、[avnc](https://github.com/gujjwal00/avnc) 以及所有让这一切成为可能的开源项目。
