[中文版](README.zh.md)

# Tiny Container (Chroot)

> [!NOTE]
> Linux is powerful. Linux can also be easy to use.

This is a chroot variant of [Tiny Container](https://github.com/Cateners/tiny_container).  
The original uses proot and works without root. This one uses chroot and requires root.

### Why chroot?

The original author [stated clearly](https://github.com/Cateners/tiny_container) that they won't support chroot:

> "I have no experience developing apps that use root (and managing root seems like a huge hassle), so I won't be supporting chroot."

Fair point. For most users proot works fine. But if your device is already rooted, chroot runs faster and has fewer quirks. So here we are.

### What's different

- chroot instead of proot. Better performance, fewer compatibility issues.
- Full filesystem mount (proc, sys, dev, dev/pts)
- Root detection on first launch
- Root permission is **required**. No root? Use the [original version](https://github.com/Cateners/tiny_container).

Everything else is the same as the original. See below.

---

## Features

### Designed for ordinary users. You don't need to know Linux...

1. Install the app and open it — no manual steps required*. After a 5-minute initialization, you'll be at the desktop.
2. Installation commands for common software are already prepared for you. Just tap, and the app handles the rest.
3. The UI is designed to be as friendly as possible, not just "good enough to have a UI". AI-powered translations are available in many languages. Even RTL layout is supported!
    - (Admittedly, I don't use RTL myself and can't verify the AI translations, so no guarantees on accuracy! But better to have it than not, right?)

### Also a toy for geeks — built-in terminal, rich container configuration options!

4. Containers and configurations can be freely shared!
5. Cutting-edge features from the Termux community are ready to go! No need to learn how to install containers, start graphical sessions, configure audio, and all that tedious stuff. The following features work out of the box:
    - Import containers via the import button, with graphical session startup included (if supported);
    - Built-in AVNC and Termux:X11 frontends — no extra app installation needed;
    - Audio and microphone forwarding;
    - virglrenderer and turnip+zink graphics acceleration...
6. Features only possible in a single app!
    - Audio and VNC transmitted over unix sockets, bypassing the network stack;
    - Browse container files via the SAF file manager;
    - Add .desktop files or even arbitrary commands as shortcuts to your Android launcher!
7. Won't conflict with Termux!

## Download

APKs are available on the [releases](https://github.com/12begin/tiny-container-chroot/releases) page.

## Build

After cloning the repository, download the prebuilt library jniLibs.zip from the [original releases](https://github.com/Cateners/tiny_container/releases) page and extract it to app/src/main/jniLibs/arm64-v8a. Then you can open and build the project normally in Android Studio.  
For automatic container installation on first launch, rename your container to rootfs.tar.zst and place it in app/src/main/assets.  
For container information, check out the [images repo](https://github.com/tiny-computer/images).

## Acknowledgments

Thanks to [Caten Hu](https://github.com/Cateners) for the original Tiny Container project.  
Thanks to the [termux](https://github.com/termux) community, [tmoe](https://github.com/2moe/tmoe), [avnc](https://github.com/gujjwal00/avnc), and all the open-source projects that made this possible.