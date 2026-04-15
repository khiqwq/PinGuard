<div align="center">

<img src="app/src/main/res/drawable/ic_launcher_foreground.xml" width="80" alt="icon" />

# PinGuard

**取消应用固定时要求指纹 / 密码验证的 LSPosed 模块**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android 15+](https://img.shields.io/badge/Android-15%2B-green.svg)](#环境要求)
[![LSPosed](https://img.shields.io/badge/LSPosed-Module-orange.svg)](#安装)

[English](README_EN.md)

</div>

---

## 功能

- 拦截「取消应用固定」操作，弹出系统凭证验证（指纹 / 密码 / 图案）
- 验证通过后直接回到桌面，**不经过锁屏**
- 仅保护 LSPosed 作用域中的应用，其他应用正常退出
- 可选隐藏系统的「如需取消固定此应用」退出提示
- 调试日志开关

## 环境要求

- Android 15+（API 35+）
- LSPosed / LSPosed Fork
- Root（Magisk / KernelSU / APatch）

## 安装

1. 从 [Releases](https://github.com/khiqwq/PinGuard/releases) 下载并安装 APK
2. 在 LSPosed 管理器中启用模块
3. 作用域勾选：
   - **系统框架 (android)** — 必选
   - **需要保护的应用** — 按需勾选
4. 重启设备
5. 打开 PinGuard，确认状态灯为绿色

## 使用

1. 打开受保护的应用，启用「应用固定 / Screen Pinning」
2. 尝试取消固定时，系统凭证验证弹出
3. 验证成功 → 取消固定，直接回桌面
4. 验证失败 / 取消 → 保持固定状态

## 设置

| 选项 | 说明 |
|------|------|
| 启用保护 | 总开关，关闭后所有应用正常退出 |
| 隐藏退出提示 | 屏蔽系统「如需取消固定此应用」Toast |
| 调试日志 | 输出详细日志到 logcat（搜索 `PinGuard`） |

## 工作原理

```
用户尝试取消固定
        │
        ▼
  stopSystemLockTaskMode()  ← Hook 拦截
        │
        ▼
  检查应用是否在保护列表 ──否──▶ 正常放行
        │是
        ▼
  广播通知固定中的应用
        │
        ▼
  弹出 KeyguardManager 凭证验证
        │
    ┌───┴───┐
    ▼       ▼
  成功     失败
    │       │
    ▼       ▼
 手动拆解   保持
 LockTask  固定状态
 内部状态
    │
    ▼
 直接回桌面
（不经过锁屏）
```

## 配套项目

- [钉图 (ImagePicker)](https://github.com/khiqwq/ImagePicker) — 极简图片查看器，选图后全屏展示，配合应用固定使用

## 📄 许可证

本项目基于 [MIT License](LICENSE) 开源，欢迎 Issue 与 PR。
